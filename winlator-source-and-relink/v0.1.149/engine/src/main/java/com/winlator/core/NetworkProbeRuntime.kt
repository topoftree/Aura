/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/** Stage only the verified diagnostic helper inside the current prefix's C: drive. */
object NetworkProbeRuntime {
    const val GUEST_PATH = "C:\\.aura-network-diagnostics\\aura-network-probe.exe"
    const val SHA256 = "567824e663af14d7fa1a9bc6cae988a28b581f68353c4c826312dbd6582e624d"

    fun install(prefix: File, bytes: ByteArray): File {
        check(prefix.isAbsolute && prefix.isDirectory)
        check(bytes.size in 1024..64 * 1024)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(hash == SHA256)
        // Resolving the active prefix alias is intentional. Descendants must not redirect writes.
        val drive = File(prefix.canonicalFile, "drive_c")
        check(drive.isDirectory && drive.canonicalFile == drive && !Files.isSymbolicLink(drive.toPath()))
        val directory = File(drive, ".aura-network-diagnostics")
        check(directory.canonicalFile == directory && !Files.isSymbolicLink(directory.toPath()))
        check(directory.isDirectory || directory.mkdir())
        val helper = File(directory, "aura-network-probe.exe")
        check(helper.canonicalFile == helper && !Files.isSymbolicLink(helper.toPath()))
        val temporary = File.createTempFile("probe-", ".tmp", directory)
        try {
            temporary.writeBytes(bytes)
            Files.move(temporary.toPath(), helper.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { temporary.delete() }
        return helper
    }
}
