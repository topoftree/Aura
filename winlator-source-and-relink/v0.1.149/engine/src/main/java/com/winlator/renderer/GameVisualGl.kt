/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.renderer

import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import com.winlator.core.GameVisualTrace
import com.winlator.core.VisualStage
import com.winlator.core.VisualValues
import com.winlator.renderer.effects.ColorEffect
import com.winlator.xserver.Drawable

/** Read-only GL queries on the existing owning context. Never drains glGetError or reads pixels. */
object GameVisualGl {
    private fun integer(name: Int): Int? = IntArray(1) { Int.MIN_VALUE }.let {
        GLES20.glGetIntegerv(name, it, 0); it[0].takeUnless { value -> value == Int.MIN_VALUE }
    }
    private fun es31(): Boolean = (integer(GLES30.GL_MAJOR_VERSION) ?: 0) > 3 ||
        integer(GLES30.GL_MAJOR_VERSION) == 3 && (integer(GLES30.GL_MINOR_VERSION) ?: 0) >= 1
    private fun texture(v: VisualValues, prefix: String, texture: Texture) {
        v.flag(prefix + "_gpu_image", texture is GPUImage)
        v.flag(prefix + "_flip_y", texture.isFlipY)
        v.flag(prefix + "_imported", (texture as? GPUImage)?.isImportedHardwareBuffer)
        val desc = (texture as? GPUImage)?.hardwareBufferDescription
        listOf("width", "height", "stride", "format", "layers", "usage").forEachIndexed { i, key ->
            v.put(prefix + "_ahb_" + key, desc?.get(i))
        }
        val supported = es31()
        v.flag(prefix + "_tex_query_supported", supported)
        for ((key, parameter) in listOf("internal_format" to 0x1003, "width" to 0x1000, "height" to 0x1001)) {
            val out = IntArray(1) { Int.MIN_VALUE }
            if (supported) GLES31.glGetTexLevelParameteriv(GLES20.GL_TEXTURE_2D, 0, parameter, out, 0)
            v.put(prefix + "_tex_" + key, out[0].takeUnless { it == Int.MIN_VALUE })
        }
    }
    private fun framebuffer(v: VisualValues) {
        val binding = integer(GLES30.GL_DRAW_FRAMEBUFFER_BINDING)
        v.flag("framebuffer_default", binding?.let { it == 0 })
        val out = IntArray(1) { Int.MIN_VALUE }
        if (binding != null) GLES30.glGetFramebufferAttachmentParameteriv(GLES30.GL_DRAW_FRAMEBUFFER,
            if (binding == 0) GLES30.GL_BACK else GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING, out, 0)
        v.put("framebuffer_encoding", out[0].takeUnless { it == Int.MIN_VALUE })
        v.flag("blend_enabled", GLES20.glIsEnabled(GLES20.GL_BLEND))
        v.put("blend_src_rgb", integer(GLES20.GL_BLEND_SRC_RGB)).put("blend_dst_rgb", integer(GLES20.GL_BLEND_DST_RGB))
        v.put("blend_src_alpha", integer(GLES20.GL_BLEND_SRC_ALPHA)).put("blend_dst_alpha", integer(GLES20.GL_BLEND_DST_ALPHA))
    }
    @JvmStatic fun draw(owner: Any, drawable: Drawable, texture: Texture, transparent: Boolean, sceneFlags: Int) {
        val ticket = GameVisualTrace.ticket(owner, VisualStage.DRAW, drawable.id) ?: return
        ticket.sample {
            VisualValues().put("width", drawable.width).put("height", drawable.height).put("x11_depth", drawable.visual.depth)
                .flag("transparent_branch", transparent).put("no_alpha_uniform_argument", if (transparent) 0 else 1)
                .put("scene_flags", sceneFlags.takeIf { it >= 0 }).also { texture(it, "draw", texture); framebuffer(it) }
        }
    }
    @JvmStatic fun copy(owner: Any, drawable: Drawable, source: Texture, target: Texture) {
        val ticket = GameVisualTrace.ticket(owner, VisualStage.COPY, drawable.id) ?: return
        ticket.sample {
            val previous = integer(GLES20.GL_TEXTURE_BINDING_2D)
            val v = VisualValues().flag("copy_succeeded", true)
            if (previous != null) try {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source.textureId); texture(v, "source", source)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, target.textureId); texture(v, "target", target)
            } finally { GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previous) }
            v.flag("texture_binding_observed", previous != null)
        }
    }
    @JvmStatic fun output(owner: Any, effects: EffectComposer, menuBlurRadius: Float) {
        val ticket = GameVisualTrace.ticket(owner, VisualStage.OUTPUT, 0) ?: return
        ticket.sample {
            val v = VisualValues().put("gles_major", integer(GLES30.GL_MAJOR_VERSION)).put("gles_minor", integer(GLES30.GL_MINOR_VERSION))
            framebuffer(v)
            val color = effects.getEffect(ColorEffect::class.java)
            v.flag("effects_enabled", effects.hasEffects()).flag("color_effect_present", color != null)
                .put("brightness", color?.brightness).put("contrast", color?.contrast).put("gamma", color?.gamma)
                .put("menu_local_blur_radius", menuBlurRadius)
            val display = EGL14.eglGetCurrentDisplay()
            val surface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS)
            val supported = extensions?.split(' ')?.contains("EGL_KHR_gl_colorspace")
            val encoding = IntArray(1)
            val ok = supported == true && surface != EGL14.EGL_NO_SURFACE &&
                EGL14.eglQuerySurface(display, surface, 0x309D /* EGL_GL_COLORSPACE_KHR */, encoding, 0)
            v.flag("egl_colorspace_supported", supported).flag("egl_colorspace_query_ok", ok)
                .put("egl_surface_colorspace", encoding[0].takeIf { ok })
            for ((name, attribute) in listOf("width" to EGL14.EGL_WIDTH, "height" to EGL14.EGL_HEIGHT)) {
                val value = IntArray(1)
                v.put("egl_surface_$name", value[0].takeIf {
                    surface != EGL14.EGL_NO_SURFACE && EGL14.eglQuerySurface(display, surface, attribute, value, 0)
                }?.let { value[0] })
            }
            v
        }
    }
}
