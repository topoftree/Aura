/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.renderer;

import androidx.annotation.Keep;

import com.winlator.xserver.Drawable;
import com.winlator.xserver.XServer;

import java.nio.ByteBuffer;

public class GPUImage extends Texture {
    private long hardwareBufferPtr;
    private long imageKHRPtr;
    private ByteBuffer virtualData;
    private short stride;
    private long[] hardwareBufferDescription;
    private long hardwareBufferId; // private allocation identity; never exported in diagnostics

    @Keep
    private void setHardwareBufferId(long id) { hardwareBufferId = id; }

    public long getHardwareBufferId() { return hardwareBufferId; }

    /** Actual AHardwareBuffer_describe values; never inferred from requested allocation flags. */
    public long[] getHardwareBufferDescription() {
        return hardwareBufferDescription == null ? null : hardwareBufferDescription.clone();
    }

    @Keep
    private void setHardwareBufferDescription(int width, int height, int stride, int format, int layers, long usage) {
        hardwareBufferDescription = new long[] {width, height, stride, format, layers, usage};
    }
    private boolean locked = false;
    private int nativeHandle = -1;
    private boolean importedHardwareBuffer;
    private EglReadFence presentationReadFence;

    static {
        System.loadLibrary("winlator");
    }

    public GPUImage(Drawable owner) {
        this(owner, true, true);
    }

    public GPUImage(Drawable owner, boolean cpuAccess) {
        this(owner, cpuAccess, true);
    }

    public GPUImage(Drawable owner, boolean cpuAccess, boolean useHALPixelFormatBGRA8888) {
        super(owner);
        hardwareBufferPtr = createHardwareBuffer(owner.width, owner.height, cpuAccess, useHALPixelFormatBGRA8888);
        if (cpuAccess && hardwareBufferPtr != 0) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
            locked = virtualData != null;
        }
    }

    private GPUImage(Drawable owner, int socketFd) {
        super(owner);
        importedHardwareBuffer = true;
        hardwareBufferPtr = receiveHardwareBuffer(socketFd, owner.width, owner.height);
    }

    /** Consumes one received AHB reference; the caller retains ownership of socketFd. */
    public static GPUImage importFromSocket(Drawable owner, int socketFd) {
        GPUImage image = new GPUImage(owner, socketFd);
        return image.hardwareBufferPtr != 0 ? image : null;
    }

    public boolean isImportedHardwareBuffer() { return importedHardwareBuffer; }

    /** Called under the destination's renderLock. GPU writes must not target the
     * permanently CPU-locked image used by the ordinary shared-memory path. */
    public static GPUImage createOrObtainGpuTarget(XServer xServer, Drawable drawable) {
        Texture previous = drawable.getTexture();
        if (previous instanceof GPUImage && ((GPUImage)previous).presentationReadFence != null &&
            !((GPUImage)previous).locked &&
            ((GPUImage)previous).hardwareBufferPtr != 0) return (GPUImage)previous;
        GPUImage next = new GPUImage(drawable, false, true);
        if (next.hardwareBufferPtr == 0) return null;
        // Start with a fresh destination: an earlier, unfenced draw of a reused
        // generic GPUImage cannot be made safe retroactively by adding a fence.
        next.presentationReadFence = new EglReadFence();
        drawable.setTexture(next);
        if (previous != null) {
            if (xServer.getRenderer() != null) xServer.getRenderer().xServerView.queueEvent(previous::destroy);
            else if (!previous.isAllocated()) previous.destroy();
        }
        return next;
    }

    public static native void abortHardwareBufferImport(int fd);

    @Override
    public void allocateTexture(short width, short height, ByteBuffer data) {
        if (isAllocated()) return;
        super.allocateTexture(width, height, null);
        imageKHRPtr = createImageKHR(hardwareBufferPtr, textureId);
        if (imageKHRPtr == 0) super.destroy();
    }

    @Override
    public void updateFromDrawable() {
        if (!isAllocated() && owner != null) allocateTexture(owner.width, owner.height, null);
        damage.clear();
    }

    @Override
    public boolean beforeRead() {
        return presentationReadFence == null || presentationReadFence.beforeAccess();
    }

    @Override
    public void afterRead() {
        if (presentationReadFence != null) presentationReadFence.afterRead();
    }

    @Override
    public boolean copyFromSource(Texture source) {
        return copyFromSource(source, null);
    }

    @Override
    public boolean copyFromSource(Texture source, com.winlator.core.FramePipelineCapture.Frame trace) {
        return copyFromSource(source, trace, null);
    }

    @Override
    public boolean copyFromSource(Texture source, com.winlator.core.FramePipelineCapture.Frame trace, SourceCompletion completion) {
        if (trace != null) trace.mark(4);
        boolean access = presentationReadFence == null || presentationReadFence.beforeAccess(trace);
        if (trace != null) trace.mark(5);
        if (!access) return false;
        // Texture still finishes the imported-source copy before Present Idle.
        return super.copyFromSource(source, trace, completion);
    }

    public short getStride() {
        return stride;
    }

    @Keep
    private void setStride(short stride) {
        this.stride = stride;
    }

    public int getNativeHandle() {
        return nativeHandle;
    }

    @Keep
    private void setNativeHandle(int nativeHandle) {
        this.nativeHandle = nativeHandle;
    }

    public ByteBuffer getVirtualData() {
        return virtualData;
    }

    @Override
    public void destroy() {
        if (presentationReadFence != null) presentationReadFence.close();
        destroyImageKHR(imageKHRPtr);
        destroyHardwareBuffer(hardwareBufferPtr, locked);
        virtualData = null;
        imageKHRPtr = 0;
        hardwareBufferPtr = 0;
        hardwareBufferDescription = null;
        super.destroy();
    }

    public long getHardwareBufferPtr() {
        return hardwareBufferPtr;
    }

    /** Diagnostic caller must hold the source Present until this read-only mapping is unlocked.
     * Only imported buffers may be read here; ordinary CPU-write-locked images are excluded. */
    public int[] diagnosticHardwareBufferPixels(int width, int height) {
        if (!importedHardwareBuffer || locked || hardwareBufferPtr == 0) return null;
        return readHardwareBufferPixels(hardwareBufferPtr, width, height);
    }

    private static native int[] readHardwareBufferPixels(long pointer, int width, int height);

    public static GPUImage createOrObtain(XServer xServer, Drawable drawable, boolean cpuAccess, boolean useHALPixelFormatBGRA8888) {
        final Texture texture = drawable.getTexture();
        if (!(texture instanceof GPUImage)) {
            xServer.getRenderer().xServerView.queueEvent(texture::destroy);
            GPUImage gpuImage = new GPUImage(drawable, cpuAccess, useHALPixelFormatBGRA8888);
            drawable.setTexture(gpuImage);
            return gpuImage;
        }
        else return (GPUImage)texture;
    }

    private native long createHardwareBuffer(short width, short height, boolean cpuAccess, boolean useHALPixelFormatBGRA8888);

    private native long receiveHardwareBuffer(int fd, int width, int height);

    private native void destroyHardwareBuffer(long hardwareBufferPtr, boolean locked);

    private native ByteBuffer lockHardwareBuffer(long hardwareBufferPtr);

    private native long createImageKHR(long hardwareBufferPtr, int textureId);

    private native void destroyImageKHR(long imageKHRPtr);
}
