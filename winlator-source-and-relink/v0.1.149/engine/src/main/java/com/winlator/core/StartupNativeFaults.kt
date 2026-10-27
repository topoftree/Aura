/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.os.Process
import android.system.Os
import java.io.File

/** Read-only, bounded module evidence for early Wine faults before a game/thread sampler exists. */
class StartupNativeFaults(private val resolve: (Long) -> String = { StartupFaultMaps().capture(it) }) {
    private val seen = mutableSetOf<Long>()
    fun observe(line: String): String? {
        if (seen.size >= 4) return null
        val match = FAULT.find(line) ?: return null
        val pc = match.groupValues[1].toLongOrNull(16)?.takeIf { it > 0 } ?: return null
        if (!seen.add(pc)) return null
        return runCatching { resolve(pc) }.getOrDefault("STARTUP_NATIVE_FAULT_V1 state=UNAVAILABLE; ownership=UNCONFIRMED\n")
    }
    companion object {
        private val FAULT = Regex(":seh:handle_syscall_fault code=c0000005 flags=[0-9a-f]+ addr=0x[0-9a-f]+ pc=0x([0-9a-f]{1,16}) tid=[0-9a-f]+(?:$|\\s)")
    }
}

class StartupFaultMaps(
    private val root: File = File("/proc"), private val uid: Int = Process.myUid(),
    private val hostPid: Int = Process.myPid(), private val owner: (File) -> Int = { Os.stat(it.path).st_uid },
    private val snapshot: () -> GuestProcessScan? = { GuestProcessSession.snapshot() },
) {
    private fun read(file: File, limit: Int): String? = runCatching {
        file.inputStream().use { input ->
            input.readNBytes(limit + 1).takeIf { it.size <= limit }?.toString(Charsets.UTF_8)
        }
    }.getOrNull()

    private fun same(process: GuestProcessInfo): Boolean = runCatching {
        process.pid > 1 && process.pid != hostPid && process.uid == uid &&
            owner(File(root, "${process.pid}")) == uid &&
            read(File(root, "${process.pid}/stat"), 4096)?.let(GameExecutionStat::parse)?.let {
                it.pid == process.pid && it.startTicks == process.startTicks
            } == true
    }.getOrDefault(false)

    fun capture(pc: Long): String {
        val scan = snapshot() ?: return "STARTUP_NATIVE_FAULT_V1 state=NO_SESSION; ownership=UNCONFIRMED\n"
        val rows = mutableListOf<String>()
        var unreadable = 0
        for (process in scan.processes.take(16)) {
            if (!same(process)) { unreadable++; continue }
            val maps = read(File(root, "${process.pid}/maps"), 256 * 1024)
            if (maps == null || maps.count { it == '\n' } > 4096 || !same(process)) { unreadable++; continue }
            val match = mapping(maps, pc) ?: continue
            rows += "STARTUP_NATIVE_MODULE_V1 pid=${process.pid}; module=${match.module}; " +
                "file_pc_offset=0x${match.offset.toString(16)}; ownership=SESSION_CANDIDATE"
        }
        return "STARTUP_NATIVE_FAULT_V1 state=${if (rows.isEmpty()) "NOT_MAPPED" else "CANDIDATES"}; " +
            "pc=0x${pc.toString(16)}; matched=${rows.size}; unreadable_or_limited=$unreadable; " +
            "session_scan_unreadable=${scan.unreadable}; process_limit=${scan.processes.size > 16}; " +
            "scope=AFTER_FAULT_EXECUTABLE_MAPS; ownership=UNCONFIRMED; stack=NOT_COLLECTED\n" +
            rows.joinToString("\n", postfix = if (rows.isEmpty()) "" else "\n")
    }

    data class Mapping(val module: String, val offset: Long)
    companion object {
        private val MAP = Regex("^([0-9a-f]+)-([0-9a-f]+) ([r-][w-]x[ps]) ([0-9a-f]+) [0-9a-f]+:[0-9a-f]+ [0-9]+\\s+(/[^\\r\\n]+)$")
        private val APK_NAME = Regex("libaura_(?:[0-9a-f]{24}|zink_shared|wine_paths|wine_dns|native_trace|wake_trace|game_probe|code_trace|graphics_trace|network_probe|audio)\\.so")
        private val SYSTEM_NAME = Regex("(?:linker64|lib[A-Za-z0-9_.-]{1,100}\\.so)")
        // Bundled Android Turnip drivers are installed as regular private files,
        // unlike hash-named APK libraries. This is a mapping label, not a hash check.
        private const val R8_SUFFIX = "/installed_components/adrenotools_driver/Aura-Turnip-26-R8/vulkan.ad07xx.so"
        private const val TURNIP_STABLE_SUFFIX = "/installed_components/adrenotools_driver/Aura-Turnip-26-2-5/libvulkan_freedreno.so"
        fun mapping(maps: String, pc: Long): Mapping? {
            if (pc <= 0 || maps.length > 256 * 1024 || maps.count { it == '\n' } > 4096) return null
            for (line in maps.lineSequence().take(4096)) {
                val m = MAP.matchEntire(line) ?: continue
                val start = m.groupValues[1].toLongOrNull(16) ?: continue
                val end = m.groupValues[2].toLongOrNull(16) ?: continue
                val offset = m.groupValues[4].toLongOrNull(16) ?: continue
                if (start < 0 || pc < start || pc >= end || offset < 0 || pc - start > Long.MAX_VALUE - offset) continue
                val path = m.groupValues[5].removeSuffix(" (deleted)")
                val name = path.substringAfterLast('/')
                val module = when {
                    APK_NAME.matches(name) -> name
                    path.startsWith("/data/") && path.endsWith(R8_SUFFIX) -> "BUNDLED_TURNIP_R8_PATH_CANDIDATE"
                    path.startsWith("/data/") && path.endsWith(TURNIP_STABLE_SUFFIX) -> "BUNDLED_TURNIP_26_2_5_PATH_CANDIDATE"
                    (path.startsWith("/apex/") || path.startsWith("/system/") || path.startsWith("/vendor/")) && SYSTEM_NAME.matches(name) -> name
                    else -> "UNIDENTIFIED_LIBRARY"
                }
                return Mapping(module, pc - start + offset)
            }
            return null
        }
    }
}
