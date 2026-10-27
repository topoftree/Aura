/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
#include <jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <stdint.h>
#include <string.h>

/* Read the host Surface's actual Android timestamps. Never waits, swaps, or sets presentation time. */
JNIEXPORT jlongArray JNICALL Java_com_winlator_core_DisplayTimestampNative_open(JNIEnv *env, jobject self) {
    (void)self;
    EGLDisplay d = eglGetCurrentDisplay(); EGLSurface s = eglGetCurrentSurface(EGL_DRAW);
    if (d == EGL_NO_DISPLAY || s == EGL_NO_SURFACE) return NULL;
    const char *ext = eglQueryString(d, EGL_EXTENSIONS);
    if (!ext || !strstr(ext, "EGL_ANDROID_get_frame_timestamps")) return NULL;
    PFNEGLGETFRAMETIMESTAMPSUPPORTEDANDROIDPROC supported = (void *)eglGetProcAddress("eglGetFrameTimestampSupportedANDROID");
    if (!supported || !supported(d, s, EGL_DISPLAY_PRESENT_TIME_ANDROID)) return NULL;
    EGLint previous = EGL_FALSE;
    if (!eglQuerySurface(d, s, EGL_TIMESTAMPS_ANDROID, &previous)) return NULL;
    if (previous != EGL_TRUE && !eglSurfaceAttrib(d, s, EGL_TIMESTAMPS_ANDROID, EGL_TRUE)) return NULL;
    jlong values[3] = {(jlong)(intptr_t)d, (jlong)(intptr_t)s, previous};
    jlongArray out = (*env)->NewLongArray(env, 3);
    if (out) (*env)->SetLongArrayRegion(env, out, 0, 3, values);
    else if (previous != EGL_TRUE) eglSurfaceAttrib(d, s, EGL_TIMESTAMPS_ANDROID, previous);
    return out;
}
JNIEXPORT jlongArray JNICALL Java_com_winlator_core_DisplayTimestampNative_read(JNIEnv *env, jobject self, jlong frame) {
    (void)self;
    EGLDisplay d = eglGetCurrentDisplay(); EGLSurface s = eglGetCurrentSurface(EGL_DRAW);
    PFNEGLGETNEXTFRAMEIDANDROIDPROC next = (void *)eglGetProcAddress("eglGetNextFrameIdANDROID");
    PFNEGLGETFRAMETIMESTAMPSANDROIDPROC times = (void *)eglGetProcAddress("eglGetFrameTimestampsANDROID");
    PFNEGLGETFRAMETIMESTAMPSUPPORTEDANDROIDPROC supported = (void *)eglGetProcAddress("eglGetFrameTimestampSupportedANDROID");
    jlong values[3] = {-3, -3, -3};
    if (frame == 0) {
        EGLuint64KHR id = 0;
        if (next && next(d, s, &id)) values[0] = (jlong)id;
    } else if (times && supported) {
        const EGLint keys[3] = {EGL_DISPLAY_PRESENT_TIME_ANDROID, EGL_RENDERING_COMPLETE_TIME_ANDROID, EGL_COMPOSITION_LATCH_TIME_ANDROID};
        for (int i = 0; i < 3; ++i) {
            EGLnsecsANDROID value = -3;
            if (supported(d, s, keys[i]) && times(d, s, (EGLuint64KHR)frame, 1, &keys[i], &value)) values[i] = value;
        }
    }
    jlongArray out = (*env)->NewLongArray(env, 3);
    if (out) (*env)->SetLongArrayRegion(env, out, 0, 3, values);
    return out;
}
JNIEXPORT jboolean JNICALL Java_com_winlator_core_DisplayTimestampNative_same(JNIEnv *env, jobject self, jlong display, jlong surface) {
    (void)env; (void)self;
    return (EGLDisplay)(intptr_t)display == eglGetCurrentDisplay() && (EGLSurface)(intptr_t)surface == eglGetCurrentSurface(EGL_DRAW);
}
JNIEXPORT void JNICALL Java_com_winlator_core_DisplayTimestampNative_close(JNIEnv *env, jobject self, jlong display, jlong surface, jlong previous) {
    (void)env; (void)self;
    EGLDisplay d = (EGLDisplay)(intptr_t)display; EGLSurface s = (EGLSurface)(intptr_t)surface;
    /* GL-thread cleanup only. A destroyed/replaced surface must not be touched. */
    if (d == eglGetCurrentDisplay() && s == eglGetCurrentSurface(EGL_DRAW))
        eglSurfaceAttrib(d, s, EGL_TIMESTAMPS_ANDROID, (EGLint)previous);
}
