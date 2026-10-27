/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.app.Activity
import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*

/** Three asynchronous, nine-texel D3D11 back-buffer reads, before the presentation blit. */
object DxvkColorProtocol {
    const val BYTES = 1024
    fun empty() = ByteArray(BYTES).also {
        "AUCOLR1\u0000".toByteArray(Charsets.US_ASCII).copyInto(it)
        ByteBuffer.wrap(it).order(LITTLE_ENDIAN).putInt(8, 1).putInt(12, BYTES)
    }
    fun valid(data: ByteArray) = data.size == BYTES && data.copyOfRange(0, 16).contentEquals(empty().copyOfRange(0, 16))
    fun report(first: ByteArray, second: ByteArray, epoch: Int): String {
        require(valid(first) && valid(second) && epoch > 0)
        val a = ByteBuffer.wrap(first).order(LITTLE_ENDIAN)
        val b = ByteBuffer.wrap(second).order(LITTLE_ENDIAN)
        if (a.getInt(16) != 0 || b.getInt(16) != 0) return "DXVK_COLOR_V1 state=STILL_ACTIVE\n"
        if (a.getInt(20) != epoch || b.getInt(20) != epoch) return "DXVK_COLOR_V1 state=EPOCH_CHANGED\n"
        if (a.getInt(48) != epoch || b.getInt(48) != epoch) return "DXVK_COLOR_V1 state=NO_NATIVE_EVENTS; pixels=UNKNOWN\n"
        val deadline = b.getLong(32)
        require(deadline > 10000 && b.getInt(24) != 0 && a.getInt(24) == b.getInt(24) && b.getInt(28) in 0..3 && a.getLong(32) == deadline)
        val states = listOf("PENDING", "COMPLETE", "UNSUPPORTED", "ALLOCATION_FAILED", "COPY_FAILED", "DEVICE_LOST")
        return buildString {
            appendLine("DXVK_COLOR_V1 state=OBSERVED; attempts=${b.getInt(28)}; wine_pid=${b.getInt(24).toLong() and 0xffffffffL}; scope=CUPHEAD_D3D11_PRE_PRESENT_BLIT; points_per_read=9; max_attempts=3")
            appendLine("COLOR_BOUNDARY source=GAME_BACK_BUFFER_BEFORE_DXVK_BLIT; target_formats=METADATA_ONLY; ahb_pixels=SEPARATE_HOST_PROBE; same_ahb_present=UNPAIRED; clock=WINE_QPC_AND_UTC_NOT_EXACT_HOST_ALIGNMENT; timing=CPU_ENQUEUE_TO_OBSERVED_RESOURCE_RELEASE_NOT_GPU_TIME; extra_gpu_waits=NONE; readback_can_perturb_rendering=YES; transfer=RAW_ENCODED_BYTES_NO_GAMMA_CHANGE")
            for (slot in 0..2) {
                val at = 128 + slot * 160
                if (b.getInt(at) == 0 || b.getInt(at + 4) != epoch) continue
                if (b.getInt(at) and 1 != 0 || !(at until at + 160).all { first[it] == second[it] }) {
                    appendLine("COLOR_READ slot=$slot; state=UNSTABLE; pixels=UNKNOWN"); continue
                }
                val state = b.getInt(at + 8)
                val frame = b.getLong(at + 16)
                val begin = b.getLong(at + 24); val end = b.getLong(at + 32)
                val qpcBegin = b.getLong(at + 40); val qpcEnd = b.getLong(at + 48)
                val hz = b.getLong(at + 136)
                val points = b.getInt(at + 144)
                val values = (0..7).map { b.getInt(at + 56 + it * 4).toLong() and 0xffffffffL }
                val sums = (0..3).map { b.getInt(at + 88 + it * 4) }
                val mins = (0..3).map { b.getInt(at + 104 + it * 4) }
                val maxs = (0..3).map { b.getInt(at + 120 + it * 4) }
                val pixelsValid = state != 2 || points == 9 && values[2] in listOf(37L, 43L, 44L, 50L) &&
                    values[7] == 1L && end in begin until deadline && qpcEnd >= qpcBegin &&
                    (0..3).all { mins[it] in 0..255 && maxs[it] in mins[it]..255 && sums[it] in 9 * mins[it]..9 * maxs[it] }
                if (state !in 1..6 || frame <= 0 || begin !in deadline - 10000 until deadline ||
                    qpcBegin <= 0 || hz !in 1..1_000_000_000 || !pixelsValid) {
                    appendLine("COLOR_READ slot=$slot; state=INVALID; pixels=UNKNOWN"); continue
                }
                val names = listOf("width", "height", "source_vk_format", "source_view_vk_format", "target_vk_format", "target_view_vk_format", "vk_color_space", "samples")
                append("COLOR_READ slot=$slot; state=${states[state - 1]}; swapchain_frame=$frame; utc_begin_ms=$begin; utc_end_ms=$end; qpc_begin=$qpcBegin; qpc_end=$qpcEnd; frequency=$hz; ")
                append(names.zip(values).joinToString("; ") { (k, v) -> "$k=$v" })
                if (state == 2) append("; points=9; rgba_sum=${sums.joinToString(",")}; rgba_min=${mins.joinToString(",")}; rgba_max=${maxs.joinToString(",")}")
                else append("; pixels=UNKNOWN")
                appendLine()
            }
        }
    }
}

/** Host retains the exact control inode, revokes it before reading or changing sessions. */
object DxvkColorTrace {
    const val PATH = "AURA_DXVK_COLOR_PATH"
    private data class Session(val id: String, val file: File, val channel: FileChannel, val fileKey: Any?, var epoch: Int = 0)
    private var current: Session? = null
    fun silence(env: EnvVars) { env.remove(PATH) }
    fun configure(context: Context, env: EnvVars) {
        silence(env)
        val activity = context as? Activity ?: return
        if (!RuntimeDiagnostics.sessionEnabled(context)) return
        val id = activity.intent.getStringExtra("aura_launch_id")?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) } ?: return
        val parts = activity.intent.getStringExtra("exec_path")?.replace('\\', '/')?.split('/') ?: return
        if (parts.any { it == ".." } || !parts.last().equals("Cuphead.exe", true)) return
        val prefix = env.get("WINEPREFIX").takeIf { it.startsWith('/') } ?: return
        val ready = runCatching { prepare(id, prefix) }.isSuccess
        if (ready) env.put(PATH, "C:\\aura-color-diagnostics\\$id.bin")
        RuntimeLaunchDiagnostics.detail(context, "DXVK_COLOR_CONFIG_V1 state=${if (ready) "PREPARED" else "UNAVAILABLE"}; scope=CUPHEAD; activation=MANUAL; producer_loaded=UNCONFIRMED")
    }
    @Synchronized internal fun prepare(id: String, prefix: String) {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        if (current?.let { it.id == id && it.channel.isOpen } == true) return
        discard()
        val drive = File(prefix, "drive_c").canonicalFile
        check(drive.isDirectory)
        val directory = File(drive, "aura-color-diagnostics")
        check(!Files.isSymbolicLink(directory.toPath()) && (directory.isDirectory || directory.mkdir()))
        Files.newDirectoryStream(directory.toPath()).use { files ->
            files.iterator().asSequence().take(256).filter { it.fileName.toString().matches(Regex("[a-f0-9-]{36}\\.bin")) }.forEach { path ->
                runCatching {
                    FileChannel.open(path, READ, WRITE, NOFOLLOW_LINKS).use { c ->
                        check(c.size() == DxvkColorProtocol.BYTES.toLong())
                        val b = ByteBuffer.allocate(DxvkColorProtocol.BYTES)
                        while (b.hasRemaining()) check(c.read(b) > 0)
                        check(DxvkColorProtocol.valid(b.array()))
                        val zero = ByteBuffer.allocate(4); check(c.write(zero, 16) == 4)
                    }
                    Files.deleteIfExists(path)
                }
            }
        }
        val file = File(directory, "$id.bin")
        val channel = FileChannel.open(file.toPath(), CREATE_NEW, READ, WRITE, NOFOLLOW_LINKS)
        try {
            val data = ByteBuffer.wrap(DxvkColorProtocol.empty())
            while (data.hasRemaining()) check(channel.write(data) > 0)
            current = Session(id, file, channel, Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey())
        } catch (e: Exception) { channel.close(); Files.deleteIfExists(file.toPath()); throw e }
    }
    private fun write(s: Session, at: Long, value: Long, wide: Boolean = false) {
        val data = ByteBuffer.allocate(if (wide) 8 else 4).order(LITTLE_ENDIAN)
        if (wide) data.putLong(value) else data.putInt(value.toInt())
        data.flip(); while (data.hasRemaining()) check(s.channel.write(data, at + data.position()) > 0)
    }
    @Synchronized fun start(id: String, now: Long = System.currentTimeMillis()) {
        if (!RuntimeDiagnostics.enabled()) return
        val s = current?.takeIf { it.id == id } ?: return
        runCatching {
            write(s, 16, 0)
            check(now in 1 until Long.MAX_VALUE - 10000 && s.epoch < Int.MAX_VALUE)
            s.epoch++
            write(s, 32, now + 10000, true)
            write(s, 20, s.epoch.toLong()); write(s, 16, 1)
        }.onFailure { stop(id) }
    }
    @Synchronized fun stop(id: String? = null) {
        current?.takeIf { id == null || it.id == id }?.let { runCatching { write(it, 16, 0) } }
    }
    @Synchronized fun report(id: String): String {
        val s = current?.takeIf { it.id == id && it.epoch > 0 } ?: return "DXVK_COLOR_V1 state=NOT_CONFIGURED; pixels=UNKNOWN\n"
        return runCatching {
            fun read(): ByteArray {
                check(s.channel.size() == DxvkColorProtocol.BYTES.toLong())
                val b = ByteBuffer.allocate(DxvkColorProtocol.BYTES)
                while (b.hasRemaining()) check(s.channel.read(b, b.position().toLong()) > 0)
                return b.array()
            }
            DxvkColorProtocol.report(read(), read(), s.epoch)
        }.getOrDefault("DXVK_COLOR_V1 state=UNREADABLE_OR_UNSTABLE; pixels=UNKNOWN\n")
    }
    @Synchronized fun discard() {
        stop(); current?.let { runCatching { it.channel.close() }; runCatching {
            val key = Files.readAttributes(it.file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
            if (key != null && key == it.fileKey) Files.deleteIfExists(it.file.toPath())
        } }; current = null
    }
}
