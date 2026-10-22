/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.renderer;

import com.winlator.math.XForm;

public class ViewTransformation {
    public int viewOffsetX;
    public int viewOffsetY;
    public int viewWidth;
    public int viewHeight;
    public float aspect;
    public float sceneScaleX;
    public float sceneScaleY;
    public float sceneOffsetX;
    public float sceneOffsetY;

    public void update(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        aspect = Math.min((float)outerWidth / innerWidth, (float)outerHeight / innerHeight);
        viewWidth = (int)Math.ceil(innerWidth * aspect);
        viewHeight = (int)Math.ceil(innerHeight * aspect);
        viewOffsetX = (int)((outerWidth - innerWidth * aspect) * 0.5f);
        viewOffsetY = (int)((outerHeight - innerHeight * aspect) * 0.5f);

        sceneScaleX = (innerWidth * aspect) / outerWidth;
        sceneScaleY = (innerHeight * aspect) / outerHeight;
        sceneOffsetX = (innerWidth - innerWidth * sceneScaleX) * 0.5f;
        sceneOffsetY = (innerHeight - innerHeight * sceneScaleY) * 0.5f;
    }

    /** Inverse of the aspect-preserving displayed viewport. */
    public void pointerTransform(float[] result, int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        update(outerWidth, outerHeight, innerWidth, innerHeight);
        XForm.makeTranslation(result, -viewOffsetX, -viewOffsetY);
        XForm.scale(result, 1.0f / aspect, 1.0f / aspect);
    }
}
