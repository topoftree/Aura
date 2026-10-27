/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.xserver;

import android.util.SparseArray;

import com.winlator.core.Callback;
import com.winlator.renderer.Texture;
import java.nio.ByteBuffer;

public class DrawableManager extends XResourceManager implements XResourceManager.OnResourceLifecycleListener {
    private final XServer xServer;
    private final SparseArray<Drawable> drawables = new SparseArray<>();

    public DrawableManager(XServer xServer) {
        this.xServer = xServer;
        xServer.pixmapManager.addOnResourceLifecycleListener(this);
    }

    public Drawable getDrawable(int id) {
        return drawables.get(id);
    }

    public Drawable createDrawable(int id, short width, short height, byte depth) {
        return createDrawable(id, width, height, xServer.pixmapManager.getVisualForDepth(depth));
    }

    public Drawable createDrawable(int id, short width, short height, Visual visual) {
        if (id == 0) return new Drawable(id, width, height, visual);
        if (drawables.indexOfKey(id) >= 0) return null;
        Drawable drawable = new Drawable(id, width, height, visual);
        drawables.put(id, drawable);
        return drawable;
    }

    public Drawable createDrawableWithData(int id, short width, short height, byte depth, ByteBuffer storage) {
        if (id != 0 && drawables.indexOfKey(id) >= 0) return null;
        Drawable drawable = new Drawable(id, width, height, xServer.pixmapManager.getVisualForDepth(depth), storage);
        if (id != 0) drawables.put(id, drawable);
        return drawable;
    }

    public Drawable createGpuDrawable(int id, short width, short height, byte depth) {
        if (id != 0 && drawables.indexOfKey(id) >= 0) return null;
        Drawable drawable = Drawable.forGpuImage(id, width, height, xServer.pixmapManager.getVisualForDepth(depth));
        if (id != 0) drawables.put(id, drawable);
        return drawable;
    }

    public void removeDrawable(int id) {
        Drawable drawable = drawables.get(id);
        if (drawable == null) return;
        synchronized (drawable.renderLock) {
            final Texture texture = drawable.getTexture();
            if (texture != null) {
                if (texture.getOwner() == drawable) texture.setOwner(null);
                if (xServer.getRenderer() != null) xServer.getRenderer().xServerView.queueEvent(texture::destroy);
                else if (texture instanceof com.winlator.renderer.GPUImage && !texture.isAllocated()) texture.destroy();
            }

            Callback<Drawable> onDestroyListener = drawable.getOnDestroyListener();
            if (onDestroyListener != null) onDestroyListener.call(drawable);
            drawable.setOnDestroyListener(null);
            drawable.setData(null);
            drawable.setOnDrawListener(null);
            drawables.remove(id);
        }
    }

    /** X clients are stopped first; exclude an in-flight GL upload before unmapping. */
    public void releaseSharedMemory() {
        for (int i = 0; i < drawables.size(); i++) {
            Drawable drawable = drawables.valueAt(i);
            synchronized (drawable.renderLock) { drawable.releaseSharedData(); }
        }
    }

    /** Allocate first: an allocation failure must leave the existing root usable. */
    public Drawable resizeDrawable(Drawable previous, short width, short height) {
        Drawable replacement = new Drawable(previous.id, width, height, previous.visual);
        replacement.setOffscreenStorage(previous.isOffscreenStorage());
        replacement.setOnDrawListener(previous.getOnDrawListener());
        removeDrawable(previous.id);
        drawables.put(replacement.id, replacement);
        return replacement;
    }

    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Pixmap) removeDrawable(((Pixmap)resource).drawable.id);
    }

    public Visual getVisual() {
        return xServer.pixmapManager.visual;
    }
}
