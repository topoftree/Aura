/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.renderer

import android.opengl.GLES30.*

/** Menu-only fallback for devices with system blur disabled. GL thread only; no pixel readback. */
class MenuBackdropBlur {
    @Volatile var radius = 0f
    private var width = 0
    private var height = 0
    private var program = 0
    private val textures = IntArray(2)
    private val buffers = IntArray(2)
    private var failed = false
    private var plan: MenuBlurKernel.Plan? = null

    /** The previous context already owns/deletes its objects. Never delete its IDs in a new context. */
    fun contextCreated() { width = 0; height = 0; program = 0; textures.fill(0); buffers.fill(0); failed = false; plan = null }
    fun release() {
        glDeleteTextures(2, textures, 0); glDeleteFramebuffers(2, buffers, 0)
        if (program != 0) glDeleteProgram(program)
        contextCreated()
    }
    private fun integer(key: Int) = IntArray(1).also { glGetIntegerv(key, it, 0) }[0]
    private fun shader(kind: Int, text: String): Int {
        val id = glCreateShader(kind)
        glShaderSource(id, text); glCompileShader(id)
        val status = IntArray(1); glGetShaderiv(id, GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) { glDeleteShader(id); return 0 }
        return id
    }
    private fun prepare(w: Int, h: Int): Boolean {
        if (program == 0) {
            val vertex = shader(GL_VERTEX_SHADER, VERTEX)
            val fragment = shader(GL_FRAGMENT_SHADER, FRAGMENT)
            if (vertex == 0 || fragment == 0) { if (vertex != 0) glDeleteShader(vertex); if (fragment != 0) glDeleteShader(fragment); return false }
            program = glCreateProgram()
            glAttachShader(program, vertex); glAttachShader(program, fragment); glLinkProgram(program)
            glDeleteShader(vertex); glDeleteShader(fragment)
            val status = IntArray(1); glGetProgramiv(program, GL_LINK_STATUS, status, 0)
            if (status[0] == 0) return false
        }
        if (w == width && h == height) return true
        glDeleteTextures(2, textures, 0); glDeleteFramebuffers(2, buffers, 0)
        glGenTextures(2, textures, 0); glGenFramebuffers(2, buffers, 0)
        repeat(2) { i ->
            glBindTexture(GL_TEXTURE_2D, textures[i])
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, buffers[i])
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textures[i], 0)
            if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) return false
        }
        width = w; height = h
        return true
    }

    fun render(surfaceWidth: Int, surfaceHeight: Int) {
        val blur = radius.takeIf { it.isFinite() }?.coerceIn(0f, 64f) ?: 0f
        if (blur <= 0f) { if (program != 0 || textures[0] != 0) release(); return }
        if (failed || surfaceWidth <= 0 || surfaceHeight <= 0) return
        val viewport = IntArray(4).also { glGetIntegerv(GL_VIEWPORT, it, 0) }
        val read = integer(GL_READ_FRAMEBUFFER_BINDING); val draw = integer(GL_DRAW_FRAMEBUFFER_BINDING)
        val oldProgram = integer(GL_CURRENT_PROGRAM); val unit = integer(GL_ACTIVE_TEXTURE)
        glActiveTexture(GL_TEXTURE0)
        val texture = integer(GL_TEXTURE_BINDING_2D)
        val blend = glIsEnabled(GL_BLEND); val scissor = glIsEnabled(GL_SCISSOR_TEST)
        try {
            glDisable(GL_BLEND); glDisable(GL_SCISSOR_TEST)
            val kernel = plan?.takeIf { it.surfaceWidth == surfaceWidth && it.surfaceHeight == surfaceHeight && it.radius == blur }
                ?: MenuBlurKernel.plan(surfaceWidth, surfaceHeight, blur).also { plan = it }
            val w = kernel.width; val h = kernel.height
            if (!prepare(w, h)) { release(); failed = true; return }
            // Copy the composed host frame, including letterbox, once, entirely on the GPU.
            glBindFramebuffer(GL_READ_FRAMEBUFFER, draw)
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, buffers[0])
            glBlitFramebuffer(0, 0, surfaceWidth, surfaceHeight, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_LINEAR)
            glUseProgram(program)
            glUniform1i(glGetUniformLocation(program, "image"), 0)
            val step = glGetUniformLocation(program, "stepUV")
            val center = glGetUniformLocation(program, "centerWeight")
            val pairs = glGetUniformLocation(program, "offsetWeight[0]")
            val count = glGetUniformLocation(program, "pairCount")
            fun axis(value: MenuBlurKernel.Axis) {
                glUniform1f(center, value.centerWeight)
                glUniform1i(count, value.pairCount)
                if (value.pairCount > 0) glUniform2fv(pairs, value.pairCount, value.pairs, 0)
            }
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, buffers[1]); glViewport(0, 0, w, h)
            glBindTexture(GL_TEXTURE_2D, textures[0])
            axis(kernel.horizontal)
            glUniform2f(step, 1f / w, 0f)
            glDrawArrays(GL_TRIANGLES, 0, 3)
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, buffers[0])
            glBindTexture(GL_TEXTURE_2D, textures[1])
            axis(kernel.vertical)
            glUniform2f(step, 0f, 1f / h)
            glDrawArrays(GL_TRIANGLES, 0, 3)
            // Both axes run on the same texel grid. Upscale only after filtering;
            // no sparse full-resolution pass and no accumulation of old frames.
            glBindFramebuffer(GL_READ_FRAMEBUFFER, buffers[0])
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw)
            glBlitFramebuffer(0, 0, w, h, 0, 0, surfaceWidth, surfaceHeight, GL_COLOR_BUFFER_BIT, GL_LINEAR)
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, read); glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw)
            glBindTexture(GL_TEXTURE_2D, texture); glActiveTexture(unit); glUseProgram(oldProgram)
            glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            if (blend) glEnable(GL_BLEND) else glDisable(GL_BLEND)
            if (scissor) glEnable(GL_SCISSOR_TEST) else glDisable(GL_SCISSOR_TEST)
        }
    }
    companion object {
        const val VERTEX = """#version 300 es
out vec2 uv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    uv = p; gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""
        const val FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D image;
uniform vec2 stepUV;
uniform float centerWeight;
uniform int pairCount;
uniform vec2 offsetWeight[48];
in vec2 uv;
out vec4 color;
void main() {
    color = texture(image, uv) * centerWeight;
    for (int i = 0; i < 48; ++i) {
        if (i >= pairCount) break;
        vec2 tap = offsetWeight[i];
        color += (texture(image, uv + stepUV * tap.x) +
                  texture(image, uv - stepUV * tap.x)) * tap.y;
    }
}
"""
    }
}
