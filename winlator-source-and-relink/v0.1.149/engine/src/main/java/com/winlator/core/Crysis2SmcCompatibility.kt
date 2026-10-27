/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Intent
import java.io.File
import java.security.MessageDigest

/** Built-in, exact-build experiment. It never changes the game file or persistent settings. */
object Crysis2SmcCompatibility {
    const val EXTRA = "aura_crysis2_inline_smc_verified"
    const val ENVIRONMENT = "AURA_FEX_CRYSIS2_INLINE_SMC"
    const val FAST_ENVIRONMENT = "AURA_FEX_CRYSIS2_FAST_SMC"
    const val EXECUTABLE_BYTES = 40_670_208L
    const val EXECUTABLE_SHA256 = "0079d15b5b85a1ec7e50bb5e59c14eb255ffc247f2ac87e67b65b402bc8a7d91"

    fun supports(path: String): Boolean {
        val parts = path.replace('\\', '/').split('/')
        return parts.none { it == ".." } && parts.last().equals("Crysis2.exe", true)
    }

    fun matches(file: File): Boolean {
        if (!supports(file.path) || !file.isFile || file.length() != EXECUTABLE_BYTES) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == EXECUTABLE_SHA256
    }

    fun prepare(intent: Intent, file: File, installer: Boolean = false): String? {
        intent.removeExtra(EXTRA)
        if (!supports(file.path)) return null
        val state = if (installer || !PerGameLaunch.hasSnapshot(intent) ||
            intent.getStringExtra("exec_path") != file.absolutePath) "SKIPPED_LAUNCH" else try {
            if (matches(file)) "CONFIGURED" else "SKIPPED_VERSION_MISMATCH"
        } catch (_: java.io.IOException) { "SKIPPED_UNREADABLE" }
        catch (_: SecurityException) { "SKIPPED_UNREADABLE" }
        intent.putExtra(EXTRA, state == "CONFIGURED")
        val requested = PerGameLaunch.read(intent).crysisFastSmc
        val fastState = if (!requested) "OFF" else if (state == "CONFIGURED") "REQUESTED" else state
        return "CRYSIS2_SMC_V1 state=$state; scope=EXACT_X86_1_9; checks=FULL_INSTRUCTION; " +
            "activation=GUEST_GATED; game_ready=UNCONFIRMED; files=UNCHANGED\n" +
            "CRYSIS2_FAST_SMC_V1 state=$fastState; requested=$requested; activation=GUEST_GATED; " +
            "short_instructions=EXACT_BYTES; fallback=CRC; game_ready=UNCONFIRMED"
    }

    fun enabled(intent: Intent) = PerGameLaunch.hasSnapshot(intent) &&
        intent.getBooleanExtra(EXTRA, false) && supports(intent.getStringExtra("exec_path").orEmpty())

    fun environment(intent: Intent, input: EnvVars) = EnvVars().apply {
        putAll(input)
        // This flag is owned by the verified launch, never inherited from another game/container.
        put(ENVIRONMENT, if (enabled(intent)) "1" else "0")
        put(FAST_ENVIRONMENT, if (enabled(intent) && PerGameLaunch.read(intent).crysisFastSmc) "1" else "0")
    }
}
