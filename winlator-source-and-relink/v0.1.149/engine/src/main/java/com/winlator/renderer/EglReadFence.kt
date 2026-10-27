/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.renderer

import android.opengl.EGL14
import android.opengl.EGL15
import android.opengl.EGLDisplay
import android.opengl.EGLSync
import android.opengl.GLES20
import com.winlator.core.FramePipelineCapture

/**
 * One Wrapper copy destination, serialized by its Drawable.renderLock.
 * The CPU lock orders submissions, but not completion on two EGL contexts.
 * EGL fences belong to the display (unlike GLsync names in a share group).
 */
class EglReadFence {
    private data class Pending(val display: EGLDisplay, val sync: EGLSync)
    private var pending: Pending? = null
    private var closed = false
    private var checkedDisplay: EGLDisplay? = null
    private var supported = false

    /** Queue a server wait before either a new draw or a copy overwrites the image. */
    @JvmOverloads fun beforeAccess(trace: FramePipelineCapture.Frame? = null): Boolean {
        if (closed) return false
        val display = EGL14.eglGetCurrentDisplay()
        val context = EGL14.eglGetCurrentContext()
        if (display == null || display == EGL14.EGL_NO_DISPLAY ||
            context == null || context == EGL14.EGL_NO_CONTEXT) return false
        val previous = pending ?: run { trace?.targetReadFence(0, 0); return true }
        if (previous.display != display) return false
        if (trace?.isObserving() == true) {
            val begin = System.nanoTime()
            // Readiness only, no wait/flush/timeout or error consumption. The
            // real server wait below remains mandatory regardless of this probe.
            val status = EGL15.eglClientWaitSync(display, previous.sync, 0, 0)
            trace.targetReadFence(when (status) {
                EGL15.EGL_CONDITION_SATISFIED -> 1
                EGL15.EGL_TIMEOUT_EXPIRED -> 2
                else -> 3
            }, System.nanoTime() - begin)
        }
        if (!EGL15.eglWaitSync(display, previous.sync, 0)) return false
        // A queued wait retains the sync. Deletion does not cancel that wait.
        pending = null
        EGL15.eglDestroySync(previous.display, previous.sync)
        return true
    }

    /** Call directly after the last draw reading this texture, while still under renderLock. */
    fun afterRead() {
        check(!closed && pending == null)
        val display = EGL14.eglGetCurrentDisplay()
        if (display != null && display != EGL14.EGL_NO_DISPLAY) {
            if (checkedDisplay != display) {
                checkedDisplay = display
                val version = EGL14.eglQueryString(display, EGL14.EGL_VERSION)?.substringBefore(' ')
                supported = version == "1.5"
            }
            if (supported) {
                val sync = EGL15.eglCreateSync(display, EGL15.EGL_SYNC_FENCE, END_ATTRIBUTES, 0)
                if (sync != null && sync != EGL15.EGL_NO_SYNC) {
                    pending = Pending(display, sync)
                    // The other context cannot flush this producer's command stream.
                    GLES20.glFlush()
                    return
                }
            }
        }
        // Unsupported/failed insertion must finish on the READING context.
        // Finishing only on the copy context would leave the race intact.
        GLES20.glFinish()
    }

    fun close() {
        if (closed) return
        closed = true
        val previous = pending
        pending = null
        if (previous != null) EGL15.eglDestroySync(previous.display, previous.sync)
    }

    private companion object {
        val END_ATTRIBUTES = longArrayOf(EGL14.EGL_NONE.toLong())
    }
}
