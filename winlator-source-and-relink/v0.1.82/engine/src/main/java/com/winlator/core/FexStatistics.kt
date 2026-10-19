/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption

data class FexStatistics(val threads: Int, val jitCount: Long, val jitTicks: Long, val diskHits: Long,
                         val diskMisses: Long, val smc: Long, val unaligned: Long, val floatFallbacks: Long) {
    fun diagnostic() = "threads=$threads jit=$jitCount jit_ticks=$jitTicks disk_hit=$diskHits disk_miss=$diskMisses " +
        "smc=$smc sigbus=$unaligned float_fallback=$floatFallbacks"
}

/** FEX-2610 SHMStats v2. Read live-thread totals, not FPS or process CPU utilization. */
object FexStatisticsReader {
    private const val LIMIT = 256 * 1024

    fun read(file: File, sinceMs: Long = 0): FexStatistics? = try {
        Files.newByteChannel(file.toPath(), setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            // A crashed old guest can leave a file behind; PID reuse alone is not ownership.
            if (Files.getLastModifiedTime(file.toPath(), LinkOption.NOFOLLOW_LINKS).toMillis() < sinceMs) return null
            val length = channel.size()
            if (length !in 64..LIMIT.toLong()) return null
            val buffer = ByteBuffer.allocate(length.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            while (buffer.hasRemaining()) if (channel.read(buffer) <= 0) return null
            parse(buffer.array())
        }
    } catch (_: Exception) { null }

    fun parse(bytes: ByteArray): FexStatistics? {
        if (bytes.size !in 64..LIMIT) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.get(0).toInt() != 2 || b.get(1).toInt() !in 2..3) return null
        val stride = b.getShort(2).toInt() and 0xffff
        val length = b.getInt(56)
        if (stride !in 112..512 || stride % 16 != 0 || length !in 64..bytes.size) return null
        var offset = b.getInt(52)
        var threads = 0
        val seen = HashSet<Int>()
        val sums = LongArray(7)
        val fields = intArrayOf(72, 8, 80, 88, 32, 24, 40)
        while (offset != 0) {
            if (offset < 64 || offset > length - stride || (offset - 64) % stride != 0 || !seen.add(offset)) return null
            if (b.getInt(offset + 4) != 0) {
                threads++
                for (i in fields.indices) {
                    val value = b.getLong(offset + fields[i])
                    if (value < 0 || sums[i] > Long.MAX_VALUE - value) return null
                    sums[i] += value
                }
            }
            offset = b.getInt(offset)
        }
        return FexStatistics(threads, sums[0], sums[1], sums[2], sums[3], sums[4], sums[5], sums[6])
    }
}
