/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.app.Activity
import android.content.Intent

/** The embedded game must never restart or terminate the process that owns Steam/downloads. */
object EmbeddedGameSession {
    @JvmStatic
    fun returnToHost(activity: Activity) {
        // Usually finish reveals the existing Compose screen. Recover if Android restored only
        // the game Activity, so a failed launch does not leave the user on the system launcher.
        if (activity.isTaskRoot) {
            val host = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            if (host?.component != null && host.component != activity.componentName) {
                host.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                try { activity.startActivity(host) }
                catch (error: RuntimeException) { RuntimeLaunchDiagnostics.failed(activity, error) }
            }
        }
        activity.finish()
    }
}
