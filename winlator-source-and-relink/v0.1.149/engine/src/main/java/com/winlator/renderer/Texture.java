/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.renderer;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES32;

import com.winlator.xserver.Drawable;

import java.nio.ByteBuffer;

public class Texture {
    protected int textureId = 0;
    protected int wrapS = GLES20.GL_CLAMP_TO_EDGE;
    protected int wrapT = GLES20.GL_CLAMP_TO_EDGE;
    protected int magFilter = GLES20.GL_LINEAR;
    protected int minFilter = GLES20.GL_LINEAR;
    protected int format = GLES11Ext.GL_BGRA;
    protected final TextureDamage damage = new TextureDamage();
    private final long[] uploadRegions = new long[TextureDamage.MAX_REGIONS];
    private boolean flipY = false;
    protected Drawable owner;

    public Texture(Drawable owner) {
        this.owner = owner;
    }

    protected void generateTextureId() {
        int[] textureIds = new int[1];
        GLES20.glGenTextures(1, textureIds, 0);
        textureId = textureIds[0];
    }

    protected void setTextureParameters() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrapS);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrapT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, magFilter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, minFilter);
    }

    public void allocateTexture(short width, short height, ByteBuffer data) {
        generateTextureId();

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);

        if (data != null) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, width, height, 0, format, GLES20.GL_UNSIGNED_BYTE, data);
        }

        setTextureParameters();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    public Drawable getOwner() {
        return owner;
    }

    public void setOwner(Drawable owner) {
        this.owner = owner;
    }

    public boolean isFlipY() {
        return flipY;
    }

    public void setFlipY(boolean flipY) {
        this.flipY = flipY;
    }

    public int getWrapS() {
        return wrapS;
    }

    public void setWrapS(int wrapS) {
        this.wrapS = wrapS;
    }

    public int getWrapT() {
        return wrapT;
    }

    public void setWrapT(int wrapT) {
        this.wrapT = wrapT;
    }

    public int getMagFilter() {
        return magFilter;
    }

    public void setMagFilter(int magFilter) {
        this.magFilter = magFilter;
    }

    public int getMinFilter() {
        return minFilter;
    }

    public void setMinFilter(int minFilter) {
        this.minFilter = minFilter;
    }

    public int getFormat() {
        return format;
    }

    public void setFormat(int format) {
        this.format = format;
    }

    public boolean isNeedsUpdate() {
        return damage.isPending();
    }

    public void setNeedsUpdate(boolean needsUpdate) {
        if (needsUpdate) damage.markAll(); else damage.clear();
    }

    public void markRowsDirty(int first, int end) {
        damage.markRows(first, end, owner == null ? 0 : owner.height);
    }

    public void updateFromDrawable() {
        if (owner == null || owner.getData() == null) return;

        ByteBuffer data = owner.getData();
        if (!isAllocated()) {
            // glTexImage2D already uploaded these bytes. A clean second draw
            // must not immediately upload the same full surface again. Clear
            // before uploading so a concurrent producer can dirty it again.
            damage.clear();
            allocateTexture(owner.width, owner.height, data);
        }
        else {
            int count = damage.takeRegions(owner.height, uploadRegions);
            if (count == 0) return;
            int position = data.position();
            try {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
                for (int i = 0; i < count; ++i) {
                    int first = (int)(uploadRegions[i] >>> 32), end = (int)uploadRegions[i];
                    data.position(first * owner.width * 4);
                    GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, first, owner.width, end - first, format, GLES20.GL_UNSIGNED_BYTE, data);
                }
            } catch (RuntimeException failure) {
                damage.markAll();
                throw failure;
            } finally {
                data.position(position);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            }
        }
    }

    public boolean isAllocated() {
        return textureId > 0;
    }

    public int getTextureId() {
        return textureId;
    }

    /** Access hooks run under the drawable's renderLock; ordinary textures need no cross-context fence. */
    public boolean beforeRead() { return true; }

    public void afterRead() {}

    public boolean copyFromSource(Texture source) {
        return copyFromSource(source, null);
    }

    public boolean copyFromSource(Texture source, com.winlator.core.FramePipelineCapture.Frame trace) {
        return copyFromSource(source, trace, null);
    }

    public interface SourceCompletion { void await(Texture source, com.winlator.core.FramePipelineCapture.Frame trace); }

    public boolean copyFromSource(Texture source, com.winlator.core.FramePipelineCapture.Frame trace, SourceCompletion completion) {
        if (!source.isAllocated()) source.allocateTexture(source.owner.width, source.owner.height, null);
        if (!this.isAllocated()) this.allocateTexture(source.owner.width, source.owner.height, null);
        if (!source.isAllocated() || !isAllocated()) return false;
        for (int i = 0; i < 8 && GLES20.glGetError() != GLES20.GL_NO_ERROR; i++) {}
        if (trace != null) trace.mark(6);
        GLES32.glCopyImageSubData(
            source.textureId, GLES20.GL_TEXTURE_2D, 0, 0, 0, 0,
            this.textureId, GLES20.GL_TEXTURE_2D, 0, 0, 0, 0,
            source.owner.width, source.owner.height, 1
        );
        if (trace != null) trace.mark(7);
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return false;
        // The Wrapper can recycle the source as soon as Present IdleNotify arrives.
        // A flush only submits the copy; it does not finish reading the imported AHB.
        boolean imported = source instanceof GPUImage && ((GPUImage)source).isImportedHardwareBuffer();
        if (trace != null) { trace.finishKind(imported); trace.mark(8); }
        if (imported) {
            if (completion != null) completion.await(source, trace);
            else GLES20.glFinish();
        }
        else GLES20.glFlush();
        if (trace != null) trace.mark(9);
        return true;
    }

    public void copyFromReadBuffer(short width, short height) {
        if (!isAllocated()) allocateTexture(width, height, null);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glCopyTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 0, 0, width, height, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glFlush();
    }

    public void destroy() {
        if (textureId > 0) {
            int[] textureIds = new int[]{textureId};
            GLES20.glDeleteTextures(textureIds.length, textureIds, 0);
            textureId = 0;
        }
    }
}
