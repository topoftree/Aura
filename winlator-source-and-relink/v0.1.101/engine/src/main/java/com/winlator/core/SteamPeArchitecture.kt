/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.channels.FileChannel
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

/** Header architecture only, not dependency integrity or successful execution. */
object SteamPeArchitecture {
    fun report(root: Path): String = "STEAM_PE_V1 " + listOf(
        "client" to "steam.exe",
        "chromehtml" to "bin/chromehtml.dll",
        "cef_win7" to "bin/cef/cef.win7/steamwebhelper.exe",
        "cef_win7x64" to "bin/cef/cef.win7x64/steamwebhelper.exe",
        "cef_win64" to "bin/cef/cef.win64/steamwebhelper.exe",
    ).joinToString("; ") { (key, relative) ->
        val current = inspect { SteamCefFiles.resolve(root, relative) }
        // Current Steam loads bin/chromehtml.dll. Retain the legacy root layout,
        // but do not hide an invalid or inaccessible current file with that fallback.
        val architecture = if (key == "chromehtml" && current == "MISSING")
            inspect { SteamCefFiles.resolve(root, "chromehtml.dll") } else current
        "$key=$architecture"
    }

    fun read(file: Path): String = inspect { file }

    // Read 64 + 26 bytes, never map or allocate the executable's declared size.
    // NOFOLLOW_LINKS also refuses a link substituted after directory resolution.
    private fun inspect(resolve: () -> Path): String = try {
        val file = resolve()
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        when {
            !attributes.isRegularFile -> "UNAVAILABLE"
            attributes.size() == 0L -> "EMPTY"
            else -> FileChannel.open(file, READ, NOFOLLOW_LINKS).use { channel ->
                val dos = header(channel, 0, 64)
                if (dos == null || dos.getShort(0).toInt() != 0x5a4d) "INVALID" else {
                    val offset = dos.getInt(60).toLong() and 0xffffffffL
                    // Nonstandard/hostile offsets must not trigger unbounded I/O.
                    if (offset !in 64L..1_048_576L) "INVALID" else {
                        val pe = header(channel, offset, 26)
                        val optionalSize = pe?.getShort(20)?.toInt()?.and(0xffff) ?: 0
                        if (pe == null || pe.getInt(0) != 0x4550 || optionalSize < 2 ||
                            offset + 24 + optionalSize > channel.size()) "INVALID" else {
                            val machine = pe.getShort(4).toInt() and 0xffff
                            val magic = pe.getShort(24).toInt() and 0xffff
                            when {
                                machine == 0x014c && magic == 0x010b -> "X86"
                                machine == 0x8664 && magic == 0x020b -> "X64"
                                machine == 0xaa64 && magic == 0x020b -> "ARM64"
                                machine == 0xa641 && magic == 0x020b -> "ARM64EC"
                                machine == 0xa64e && magic == 0x020b -> "ARM64X"
                                machine in setOf(0x014c, 0x8664, 0xaa64, 0xa641, 0xa64e) -> "INVALID"
                                magic == 0x010b || magic == 0x020b -> "OTHER"
                                else -> "INVALID"
                            }
                        }
                    }
                }
            }
        }
    } catch (_: NoSuchFileException) { "MISSING" }
      catch (_: IOException) { "UNAVAILABLE" }
      catch (_: DirectoryIteratorException) { "UNAVAILABLE" }
      catch (_: SecurityException) { "UNAVAILABLE" }

    private fun header(channel: FileChannel, offset: Long, count: Int): ByteBuffer? {
        val buffer = ByteBuffer.allocate(count).order(LITTLE_ENDIAN)
        channel.position(offset)
        while (buffer.hasRemaining()) if (channel.read(buffer) <= 0) return null
        return buffer
    }
}
