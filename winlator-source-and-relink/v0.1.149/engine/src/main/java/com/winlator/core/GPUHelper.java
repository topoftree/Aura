/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.EGL14;

import androidx.collection.ArrayMap;
import androidx.preference.PreferenceManager;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

import dalvik.annotation.optimization.CriticalNative;

public abstract class GPUHelper {
    public enum VkPresentMode {
        IMMEDIATE, MAILBOX, FIFO, FIFO_RELAXED;

        public String value() {
            return name().toLowerCase(Locale.ENGLISH);
        }
    }
    public static int VK_API_VERSION_1_3 = GPUHelper.vkMakeVersion(1, 3, 0);

    static {
        System.loadLibrary("winlator");
    }

    private static ArrayMap<String, String> loadGPUInformation(Context context) {
        final java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);
        final ArrayMap<String, String> gpuInfo = new ArrayMap<>();
        gpuInfo.put("renderer", "");
        gpuInfo.put("vendor", "");
        gpuInfo.put("version", "");
        Thread probe = new Thread(() -> {
            EGL10 egl = (EGL10)EGLContext.getEGL();
            EGLDisplay display = EGL10.EGL_NO_DISPLAY;
            EGLContext eglContext = EGL10.EGL_NO_CONTEXT;
            javax.microedition.khronos.egl.EGLSurface surface = EGL10.EGL_NO_SURFACE;
            try {
                display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
                if (!egl.eglInitialize(display, new int[2])) return;
                EGLConfig[] configs = new EGLConfig[1];
                int[] count = new int[1];
                int[] attributes = { EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                    EGL10.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL10.EGL_NONE };
                if (!egl.eglChooseConfig(display, attributes, configs, 1, count) || count[0] == 0) return;
                eglContext = egl.eglCreateContext(display, configs[0], EGL10.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE});
                surface = egl.eglCreatePbufferSurface(display, configs[0],
                    new int[]{EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE});
                if (eglContext == EGL10.EGL_NO_CONTEXT || surface == EGL10.EGL_NO_SURFACE ||
                    !egl.eglMakeCurrent(display, surface, surface, eglContext)) return;
                GL10 gl = (GL10)eglContext.getGL();
                String renderer = Objects.toString(gl.glGetString(GL10.GL_RENDERER), "");
                String vendor = Objects.toString(gl.glGetString(GL10.GL_VENDOR), "");
                String version = Objects.toString(gl.glGetString(GL10.GL_VERSION), "");
                synchronized (gpuInfo) {
                    gpuInfo.put("renderer", renderer);
                    gpuInfo.put("vendor", vendor);
                    gpuInfo.put("version", version);
                }
                PreferenceManager.getDefaultSharedPreferences(context).edit().putString("gpu_renderer", renderer)
                    .putString("gpu_vendor", vendor).putString("gpu_version", version)
                    .putString("gpu_fingerprint", android.os.Build.FINGERPRINT).apply();
            } catch (RuntimeException | LinkageError ignored) {
                // A failed GPU query falls back to the system driver; it must not strand startup.
            } finally {
                try {
                    if (display != EGL10.EGL_NO_DISPLAY) {
                        egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                        if (surface != EGL10.EGL_NO_SURFACE) egl.eglDestroySurface(display, surface);
                        if (eglContext != EGL10.EGL_NO_CONTEXT) egl.eglDestroyContext(display, eglContext);
                        egl.eglTerminate(display);
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    // Query/cleanup failure is handled by the same system-driver fallback.
                } finally { completed.countDown(); }
            }
        }, "aura-gpu-probe");
        probe.setDaemon(true);
        probe.start();
        try { completed.await(3, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        synchronized (gpuInfo) { return new ArrayMap<>(gpuInfo); }
    }

    public static String glGetRenderer(Context context) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String value = preferences.getString("gpu_renderer", "");
        if (!value.isEmpty() && currentHardware(preferences)) return value;

        ArrayMap<String, String> gpuInfo = loadGPUInformation(context);
        return gpuInfo.get("renderer");
    }

    public static String glGetVendor(Context context) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String value = preferences.getString("gpu_vendor", "");
        if (!value.isEmpty() && currentHardware(preferences)) return value;

        ArrayMap<String, String> gpuInfo = loadGPUInformation(context);
        return gpuInfo.get("vendor");
    }

    public static String glGetVersion(Context context) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String value = preferences.getString("gpu_version", "");
        if (!value.isEmpty() && currentHardware(preferences)) return value;

        ArrayMap<String, String> gpuInfo = loadGPUInformation(context);
        return gpuInfo.get("version");
    }

    private static boolean currentHardware(SharedPreferences preferences) {
        // Android backup may restore GPU preferences onto a different phone.
        return android.os.Build.FINGERPRINT.equals(preferences.getString("gpu_fingerprint", ""));
    }

    public static short getAdrenoModelId(Context context) {
        Matcher matcher = Pattern.compile("adreno[^678]*([678][0-9]{2})", Pattern.CASE_INSENSITIVE).matcher(glGetRenderer(context));
        return (short)(matcher.find() ? Integer.parseInt(matcher.group(1)) : 0);
    }

    public static int vkMakeVersion(String value) {
        final Pattern pattern = Pattern.compile("([0-9]+)\\.([0-9]+)\\.?([0-9]+)?");
        Matcher matcher = pattern.matcher(value);
        if (matcher.find()) {
            try {
                int major = matcher.group(1) != null ? Integer.parseInt(matcher.group(1)) : 0;
                int minor = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
                int patch = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;
                if (matcher.group(1) == null && patch == 0) patch = minor;
                return vkMakeVersion(major, minor, patch);
            }
            catch (NumberFormatException e) {
                return 0;
            }
        }
        else return 0;
    }

    public static int vkMakeVersion(int major, int minor, int patch) {
        return  ((major) << 22) | ((minor) << 12) | (patch);
    }

    public static int vkVersionMajor(int version) {
        return (version) >> 22;
    }

    public static int vkVersionMinor(int version) {
        return ((version) >> 12) & 0x3FF;
    }

    public static int vkVersionPatch(int version) {
        return (version) & 0xFFF;
    }

    public static native String[] vkGetDeviceExtensions();

    @CriticalNative
    public static native int vkGetApiVersion();

    public static native void setGlobalEGLContext();

    public static native long createOffscreenEGLContext(boolean sharedContext);

    public static native void destroyOffscreenEGLContext(long contextPtr);
}
