/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions

/** Session-owned, fixed-size recent pipeline ring. No shader source, handles or user strings. */
class StartupVulkanTrace(private val wineRoot: File?, private val captureId: String, private val deadlineElapsed: Long) {
    private var control: FileChannel? = null
    private var slots: FileChannel? = null
    private var state = "NOT_CONFIGURED"
    private var closedReport: String? = null
    private val owned = linkedMapOf<Path, Any?>()

    fun configure(env: EnvVars) {
        env.remove(VARIABLE)
        if (closedReport != null || control != null) return
        try {
            state = "PATH_UNAVAILABLE"
            check(captureId.matches(ID))
            val root = requireNotNull(wineRoot).canonicalFile.toPath()
            check(root.parent != null && Files.isDirectory(root, NOFOLLOW_LINKS))
            val prefix = File(env.get("WINEPREFIX"))
            check(prefix.isAbsolute)
            val z = File(prefix, "dosdevices/z:").toPath()
            check(Files.isSymbolicLink(z) && z.toRealPath() == root)
            // Z is Aura's private runtime root, not Android's filesystem root.
            val base = root.resolve(STORE)
            if (!Files.exists(base, NOFOLLOW_LINKS))
                Files.createDirectory(base, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            check(Files.isDirectory(base, NOFOLLOW_LINKS))
            state = "STORAGE_UNAVAILABLE"
            retireExpired(base)
            val directory = base.resolve(captureId)
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            owned[directory] = identity(directory)
            val permission = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            fun create(name: String, bytes: ByteArray): FileChannel {
                val path = directory.resolve(name)
                Files.createFile(path, permission)
                owned[path] = identity(path)
                return FileChannel.open(path, READ, WRITE, NOFOLLOW_LINKS).also { channel ->
                    try { val data = ByteBuffer.wrap(bytes); while (data.hasRemaining()) check(channel.write(data) > 0) }
                    catch (e: Exception) { channel.close(); throw e }
                }
            }
            val header = ByteBuffer.allocate(64).order(LITTLE_ENDIAN)
                .put("AURAVP1\u0000".toByteArray()).putInt(1).putInt(64).putInt(1)
            header.putLong(32, System.currentTimeMillis() + (deadlineElapsed - SystemClock.elapsedRealtime()).coerceIn(0, 120_000))
            control = create(".pipeline-control", header.array())
            slots = create(".pipeline-slots", ByteArray(64 * 1024))
            env.put(VARIABLE, "Z:\\$STORE\\$captureId")
            state = "CONFIGURED"
        } catch (_: Exception) { close() }
    }

    private fun read(channel: FileChannel?, position: Long, size: Int): ByteBuffer {
        requireNotNull(channel)
        val b = ByteBuffer.allocate(size).order(LITTLE_ENDIAN)
        while (b.hasRemaining()) check(channel.read(b, position + b.position()) > 0)
        return b.apply { flip() }
    }

    fun close() {
        if (closedReport != null) return
        runCatching {
            control?.let { channel ->
                val zero = ByteBuffer.allocate(4)
                while (zero.hasRemaining()) check(channel.write(zero, 16L + zero.position()) > 0)
            }
        }
        closedReport = snapshot()
        runCatching { control?.close() }; runCatching { slots?.close() }
        control = null; slots = null
        // Keep replacement files untouched. An already mapped guest only sees
        // the revoked original inode; the frozen report survives file removal.
        owned.entries.toList().asReversed().forEach { (path, key) -> removeOwned(path, key) }
        owned.clear()
    }

    fun report(): String = closedReport ?: snapshot()

    private fun snapshot(): String = runCatching {
        val c = read(control, 0, 64)
        check(c.array().copyOfRange(0, 8).contentEquals("AURAVP1\u0000".toByteArray()))
        check(c.getInt(8) == 1 && c.getInt(12) == 64 && slots?.size() == 65536L)
        fun u(at: Int) = c.getInt(at).toLong() and 0xffffffffL
        check(u(20) <= 8192)
        var rejected = 0
        val records = buildList {
            repeat(64) { index ->
                val offset = index * 1024L
                val first = read(slots, offset, 1024)
                val seq = first.getInt(0)
                if (seq == 0) return@repeat
                val second = read(slots, offset, 4).getInt(0)
                if (seq != second || seq and 1 != 0) { rejected++; return@repeat }
                val kind = first.getInt(4); val call = first.getInt(16); val status = first.getInt(20)
                val used = first.getInt(28)
                val expected = when (kind) { 1 -> 32; 2 -> 248; 3 -> 92; else -> -1 }
                if (call !in 1..8192 || call.toLong() > u(20) || (call - 1) % 64 != index || status !in 1..2 || used != expected || used !in 1..248) {
                    rejected++; return@repeat
                }
                val fields = List(used) { first.getInt(32 + it * 4).toLong() and 0xffffffffL }
                add(call to render(kind, call, first.getInt(8), first.getInt(12), status, first.getInt(24), fields))
            }
        }.sortedBy { it.first }
        buildString {
            appendLine("VULKAN_PIPELINE_CAPTURE_V1 state=$state; scope=QUALCOMM_DXVK_2_6; calls=${u(20)}; ready=${u(24)}; retained=${records.size}; rejected=$rejected; write_drops=${u(28)}; budget_exhausted=${u(40)}; overwritten_returns=${u(44)}; shader_scan_bytes=${u(48)}; guest=${if (u(24) == 0L) "NOT_OBSERVED" else "READY"}; path=PRIVATE_Z; snapshot=NON_ATOMIC")
            appendLine("Recent 64 calls, maximum 8192 calls/120 seconds; shader inspection limited to 16 KiB each and 4 MiB per session. Prefix fingerprints are not full shader hashes. ENTER without RETURN is unconfirmed, not proof of a crash or a duration. Wine PID/TID are not native PID/TID. No shader words, handles, entry names or specialization contents are exported. Missing summaries, multiple create infos and truncated fields remain partial. Device testing required.")
            records.forEach { append(it.second) }
        }
    }.getOrElse { "VULKAN_PIPELINE_CAPTURE_V1 state=$state; read=UNAVAILABLE; reason=ABSENT_OR_INCOMPLETE\n" }

    private fun render(kind: Int, call: Int, pid: Int, tid: Int, state: Int, result: Int, f: List<Long>): String = buildString {
        val name = when (kind) { 1 -> "SHADER_MODULE"; 2 -> "GRAPHICS_PIPELINE"; else -> "COMPUTE_PIPELINE" }
        appendLine("VULKAN_CALL_V1 call=$call; wine_pid=${pid.toLong() and 0xffffffffL}; wine_tid=${tid.toLong() and 0xffffffffL}; kind=$name; state=${if (state == 2) "RETURN" else "ENTER"}; result=${if (state == 2) result.toString() else "UNOBSERVED"}")
        fun shader(at: Int, stage: String) {
            val s = f.drop(at).take(20)
            appendLine("VULKAN_SHADER_V1 call=$call; stage=$stage; bytes=${s[0]}; spirv_version=${s[1]}; generator=${s[2]}; id_bound=${s[3]}; parse_flags=${s[4]}; prefix_fingerprint=${s[6].toString(16).padStart(8, '0')}${s[5].toString(16).padStart(8, '0')}; capabilities_total=${s[7]}; capabilities=${s.drop(8).take(minOf(12L, s[7]).toInt()).joinToString(",").ifEmpty { "NONE_OR_UNOBSERVED" }}")
        }
        if (kind == 1) { appendLine("VULKAN_SHADER_CREATE_V1 call=$call; flags=${f[0]}; pnext_count=${f[1]}; pnext_truncated=${f[2]}; pnext_types=${f.subList(3,11).take(minOf(f[1],8L).toInt()).joinToString(",")}"); shader(12, "MODULE"); return@buildString }
        appendLine("VULKAN_PIPELINE_V1 call=$call; count=${f[0]}; flags=${f[1]}; pnext_count=${f[2]}; pnext_truncated=${f[3]}; pnext_types=${f.subList(4,12).take(minOf(f[2],8L).toInt()).joinToString(",")}; layout_present=${f[13]}; partial=${f[57]}")
        if (kind == 2) {
            val names = listOf("stage_count" to 12, "render_pass_present" to 14, "subpass" to 15, "topology" to 16,
                "primitive_restart" to 17, "polygon_mode" to 18, "cull_mode" to 19, "front_face" to 20,
                "raster_discard" to 21, "samples" to 22, "depth_test" to 23, "depth_write" to 24, "depth_compare" to 25,
                "stencil_test" to 26, "color_attachments" to 27, "blend_mask" to 28, "dynamic_count" to 29,
                "dynamic_truncated" to 30, "vertex_bindings" to 48, "vertex_attributes" to 49, "viewports" to 50,
                "scissors" to 51, "patch_control_points" to 52, "sample_shading" to 53, "alpha_to_coverage" to 54,
                "depth_clamp" to 55, "depth_bias" to 56, "stages_present" to 58, "state_presence_mask" to 59)
            appendLine("VULKAN_GRAPHICS_V1 call=$call; " + names.joinToString("; ") { (key, index) -> "$key=${f[index]}" } +
                "; dynamic_states=" + f.subList(32,48).take(minOf(f[29],16L).toInt()).joinToString(","))
            appendLine("VULKAN_RENDERING_V1 call=$call; present=${f[232]}; view_mask=${f[233]}; depth_format=${f[234]}; stencil_format=${f[235]}; color_count=${f[236]}; partial=${f[237]}; color_formats=" +
                f.subList(238,246).take(minOf(f[236],8L).toInt()).joinToString(","))
        }
        repeat(if (kind == 2) minOf(f[12], 6L).toInt() else if (f[0] > 0) 1 else 0) { index ->
            val at = 64 + index * 28
            appendLine("VULKAN_STAGE_V1 call=$call; index=$index; stage=${f[at]}; flags=${f[at+1]}; pnext_type=${f[at+2]}; module_present=${f[at+3]}; specialization_count=${f[at+4]}; specialization_bytes=${f[at+5]}; entry_present=${f[at+6]}; summary_source=${f[at+7]}")
            shader(at+8, index.toString())
        }
    }

    companion object {
        const val VARIABLE = "AURA_STARTUP_PIPELINE_DIRECTORY"
        private const val STORE = ".aura-startup-pipelines"
        private val ID = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private fun identity(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
        private fun removeOwned(path: Path, key: Any?) = runCatching {
            if (key != null && identity(path) == key) Files.delete(path)
        }

        /** Reclaim only expired, complete rings left by a killed Aura process. */
        private fun retireExpired(base: Path) {
            var remaining = 0
            var visited = 0
            Files.newDirectoryStream(base).use { paths ->
                for (directory in paths) {
                    check(++visited <= 64)
                    runCatching {
                        check(ID.matches(directory.fileName.toString()) && Files.isDirectory(directory, NOFOLLOW_LINKS))
                        val key = identity(directory)
                        val control = directory.resolve(".pipeline-control")
                        val slots = directory.resolve(".pipeline-slots")
                        check(Files.isRegularFile(control, NOFOLLOW_LINKS) && Files.isRegularFile(slots, NOFOLLOW_LINKS))
                        val controlKey = identity(control); val slotsKey = identity(slots)
                        FileChannel.open(control, READ, WRITE, NOFOLLOW_LINKS).use { channel ->
                            check(channel.size() == 64L && Files.size(slots) == 65536L)
                            val header = ByteBuffer.allocate(64).order(LITTLE_ENDIAN)
                            while (header.hasRemaining()) check(channel.read(header) > 0)
                            check(header.array().copyOfRange(0, 8).contentEquals("AURAVP1\u0000".toByteArray()) &&
                                header.getInt(8) == 1 && header.getInt(12) == 64)
                            val deadline = header.getLong(32)
                            check(deadline > 0 && deadline <= System.currentTimeMillis())
                            val zero = ByteBuffer.allocate(4)
                            while (zero.hasRemaining()) check(channel.write(zero, 16L + zero.position()) > 0)
                        }
                        removeOwned(control, controlKey); removeOwned(slots, slotsKey); removeOwned(directory, key)
                    }
                    if (Files.exists(directory, NOFOLLOW_LINKS)) remaining++
                }
            }
            check(remaining < 64)
        }
    }
}
