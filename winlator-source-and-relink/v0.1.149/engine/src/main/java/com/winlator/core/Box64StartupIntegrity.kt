/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A version string alone cannot detect a rebuilt, same-version bundled Box64. */
object Box64StartupIntegrity {
    fun verifyAndRepair(binary: File, bytes: Long, sha256: String, repair: () -> Unit): Boolean {
        if (bytes <= 0 || !sha256.matches(Regex("[0-9a-f]{64}")))
            throw RuntimeComponentException("BOX64", "INVALID_CATALOGUE")
        if (matches(binary, bytes, sha256)) return false
        repair()
        if (!matches(binary, bytes, sha256))
            throw RuntimeComponentException("BOX64", "INTEGRITY_CHECK_FAILED")
        return true
    }

    private fun matches(binary: File, bytes: Long, sha256: String): Boolean = try {
        // Following the final symlink is intentional: native code lives in nativeLibraryDir.
        if (!binary.isFile || !binary.canRead() || binary.length() != bytes) false else {
            val digest = MessageDigest.getInstance("SHA-256")
            binary.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == sha256
        }
    } catch (_: IOException) { false }
}
