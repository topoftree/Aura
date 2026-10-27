/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.renderer;

import com.winlator.xserver.Drawable;

class RenderableWindow {
    final Drawable content;
    short rootX;
    short rootY;
    final boolean transparent;
    final int sceneFlags;
    final FullscreenTransformation fullscreenTransformation;

    public RenderableWindow(Drawable content, int rootX, int rootY, int sceneFlags, FullscreenTransformation fullscreenTransformation) {
        this.content = content;
        this.rootX = (short)rootX;
        this.rootY = (short)rootY;
        this.sceneFlags = sceneFlags;
        this.transparent = sceneFlags != 0;
        this.fullscreenTransformation = fullscreenTransformation;
    }
}
