/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.InputStream
import java.io.IOException
import java.util.Locale

/** Only fixed diagnostic codes leave this parser. Never retain guest text, paths or credentials. */
class GuestOutputSummary {
    private var lines = 0L
    private val signals = linkedSetOf<String>()
    private val ubisoftModules = linkedSetOf<String>()
    private val levels = LongArray(5)
    private val channels = LongArray(CHANNELS.size + 1)

    @Synchronized
    fun accept(text: String): Boolean {
        lines++
        val raw = text.take(4096).lowercase(Locale.ROOT)
        // Wine +timestamp,+pid adds seconds:pid:tid before the normal channel prefix.
        // Strip only a bounded, validated header; retain no timestamps or process IDs.
        val line = WINE_PREFIX.find(raw)?.let { raw.substring(it.range.last + 1) } ?: raw
        val trimmed = line.trim()
        countChannel(line)
        // This hot Wine trace contains only numeric context fields. It is not an
        // exception/error report. Count it, but avoid running every error regex.
        // A changed format or any additional text still takes the full parser.
        if (line.startsWith("trace:seh:rtlinitializeextendedcontext2 ") && CONTEXT_TRACE.matches(trimmed)) return false
        val previousSignals = signals.size
        val previousModules = ubisoftModules.size
        fun signal(code: String) { if (signals.size < 24) signals += code }
        if (trimmed.startsWith("fdsan: attempted to close file descriptor ") ||
            trimmed.startsWith("fdsan: double-close of file descriptor "))
            signal("ANDROID_FD_OWNERSHIP_VIOLATION")
        if (trimmed.startsWith("aura_native_fd_v1 ") && "expected=unowned" in trimmed)
            signal("NATIVE_FD_OWNED_CLOSE_OBSERVED")
        if ("err:virtual:virtual_setup_exception stack overflow " in line)
            signal("WINE_EXCEPTION_STACK_OVERFLOW")
        if ("err:virtual:virtual_setup_exception nested exception on signal stack " in line)
            signal("WINE_NESTED_SIGNAL_EXCEPTION")
        // Mesa's native register assertion has no Wine severity/channel prefix.
        // Preserve this exact coordinate failure without retaining build paths or
        // guessing which graphics setting supplied the rejected coordinate.
        if (TURNIP_A2D_COORDINATE_ASSERTION.matches(trimmed))
            signal("TURNIP_A2D_COORDINATE_ASSERTION")
        if (WINE_ASSERTION_EXCEPTION.matches(trimmed))
            signal("WINE_ASSERTION_EXCEPTION")
        if (trimmed == "err:seh:ntraiseexception exception frame is not in stack limits => unable to dispatch exception.")
            signal("WINE_EXCEPTION_DISPATCH_FAILED")
        // Wine's final exception message has no err:seh prefix. Do not lose it as
        // ordinary output, or infer a fatal fault from first-chance exception traces.
        if (UNHANDLED_ILLEGAL_INSTRUCTION.matches(trimmed)) {
            signal("GUEST_UNHANDLED_EXCEPTION")
            signal("NT_ILLEGAL_INSTRUCTION")
            signal("WINE_DEBUGGER_STARTING")
        }
        // A module-load trace is evidence of loading, not of a live process, UI or login.
        MODULE_LOADED.find(line)?.groupValues?.get(1)?.let { path ->
            val name = path.substringAfterLast('\\').substringAfterLast('/')
            UBISOFT_MODULES[name]?.let { code ->
                if (name != "libcef.dll" || "\\ubisoft game launcher\\" in path)
                    ubisoftModules += code
            }
        }
        val moduleLoadFailure = MODULE_LOAD_FAILURE.find(line)
        moduleLoadFailure?.let { match ->
            val name = match.groupValues[1].substringAfterLast('\\').substringAfterLast('/').removeSuffix(".dll")
            val component = if (name in WINDOWS_LIBRARIES) name else "UNLISTED"
            // LoadLibrary probes may be optional. Do not classify this as a fatal import.
            signal("MODULE_LOAD_FAILED_$component")
        }
        if ("warn:module:import_dll no implementation for userenv.dll.deriveappcontainersidfromappcontainername imported from" in line)
            signal("API_UNIMPLEMENTED_USERENV_DERIVE_APP_CONTAINER_SID")
        // Match the imported library itself, not another DLL mentioned in its caller's path.
        // Keep only names from a fixed runtime catalogue; never retain arbitrary game filenames.
        val moduleImportFailure = MODULE_IMPORT_FAILURE.find(line)
        moduleImportFailure?.let { match ->
            val name = match.groupValues[1].removeSurrounding("\"")
                .substringAfterLast('\\').substringAfterLast('/').removeSuffix(".dll")
            val component = if (name in WINDOWS_LIBRARIES) name else "UNLISTED"
            signal(if (match.groupValues[2] == "not found") "DLL_NOT_FOUND_$component"
                else "DLL_LOAD_FAILED_$component")
        }
        if (trimmed.removePrefix("[box64] ") in MEMORY_MARKERS)
            signal("BOX64_GUEST_MEMORY_NX_V1")
        // Wine puts a full path between "failed to load" and ntdll.dll; the old adjacent-word
        // checks dropped both that failure and statuses outside three hand-picked values.
        val ntdllLoadFailure = NTDLL_LOAD_FAILURE.containsMatchIn(line)
        if (ntdllLoadFailure) {
            // Wine's .dll.so fallback prints only .dll in its error, but keeps the Unix directory.
            signal(if ("ntdll.so" in line || "/x86_64-unix/ntdll.dll" in line || "/aarch64-unix/ntdll.dll" in line)
                "WINE_NTDLL_UNIX_LOAD_FAILED" else "WINE_NTDLL_PE_LOAD_FAILED")
            signal("WINE_NTDLL_LOAD_FAILED")
        }
        if ("cannot get path to ntdll.so" in line) signal("WINE_NTDLL_PATH_FAILED")
        if ("could not exec the wine loader" in line) signal("WINE_LOADER_EXEC_FAILED")
        if ("failed to set" in line && ("prot_exec" in line || "noexec filesystem" in line))
            signal("EXECUTABLE_MAPPING_DENIED")
        if ("failed to map the shared user data" in line) signal("WINE_SHARED_USER_DATA_MAPPING_FAILED")
        if ("failed to load apiset:" in line) signal("WINE_APISET_LOAD_FAILED")
        if ("failed to load start.exe:" in line) signal("WINE_START_EXE_LOAD_FAILED")
        if ("failed to start wineboot" in line) signal("WINEBOOT_START_FAILED")
        if ("wineserver" in line && ("version mismatch" in line || "wrong wineserver" in line))
            signal("WINESERVER_VERSION_MISMATCH")
        // Native Wine's --version does not initialize the server's character tables.
        // Its fatal NLS error previously disappeared as "no known error" on ARM64EC.
        if ("wineserver:" in line && "failed to load l_intl.nls" in line)
            signal("WINESERVER_NLS_LOAD_FAILED")
        if ("wineserver:" in line && "invalid format for casemap table" in line)
            signal("WINESERVER_NLS_INVALID")
        if ("_r_debug not found in ld.so" in line || "wine_main_preload_info not found" in line ||
            "wine_r_debug not found" in line) signal("WINE_PRELOADER_FAILED")
        if ("cannot link executable" in line || "dlopen failed:" in line)
            signal("ANDROID_LINKER_LOAD_FAILED")
        if ("esync" in line && ("failed" in line || "please enable" in line)) signal("WINE_ESYNC_MISMATCH")
        // These can accompany a recoverable stall. Preserve the observed errors,
        // without inferring a deadlock, a fatal exit or a shared underlying cause.
        if (trimmed == "err:esync:esync_set_event write: bad file descriptor")
            signal("WINE_ESYNC_INVALID_FD")
        if (CRITICAL_SECTION_TIMEOUT.matches(trimmed))
            signal("WINE_CRITICAL_SECTION_TIMEOUT")
        // This assertion is a failed process-handle condition, not proof of a GPU
        // crash or a missing DLL. Do not conflate it with the opposite == assertion.
        if ("chrome_ipc_client.cpp" in line && "m_hcefhandle != invalid_process_handle" in line)
            signal("STEAM_CEF_PROCESS_HANDLE_INVALID")
        if ("you must install or update .net" in line || "required library hostfxr.dll could not be found" in line)
            signal("DOTNET_RUNTIME_MISSING")
        if ("failed to load" in line && ("hostfxr" in line || "hostpolicy" in line || "coreclr" in line))
            signal("DOTNET_RUNTIME_LOAD_FAILED")
        if ("wine mono is not installed" in line || "wine-mono is not installed" in line)
            signal("WINE_MONO_MISSING")
        if ("msiinstallproduct failed" in line) signal("WINE_MSI_INSTALL_FAILED")
        if ("could not load mono into this process" in line) signal("WINE_MONO_LOAD_FAILED")
        if ("system.dllnotfoundexception" in line) signal("MANAGED_NATIVE_LIBRARY_MISSING")
        if ("system.io.filenotfoundexception" in line || "system.io.fileloadexception" in line)
            signal("MANAGED_FILE_LOAD_FAILED")
        if ("system.badimageformatexception" in line) signal("MANAGED_INVALID_IMAGE_FORMAT")
        if ("failed to initialize player" in line || "failed to load unityplayer" in line)
            signal("UNITY_PLAYER_INITIALIZATION_FAILED")
        if (trimmed.startsWith("windowsvideomedia error ")) {
            signal("UNITY_VIDEO_PLAYBACK_FAILED")
            if (trimmed.startsWith("windowsvideomedia error 0xc00d36b3 "))
                signal("MEDIA_FOUNDATION_INVALID_STREAM")
        }
        if ("videoplayer cannot play clip" in line) signal("UNITY_VIDEO_PLAYBACK_FAILED")
        val gpuMemoryFailure = "vk_error_out_of_device_memory" in line || "vk_error_out_of_host_memory" in line
        if ("vk_error_out_of_device_memory" in line) signal("GPU_DEVICE_OUT_OF_MEMORY")
        if ("vk_error_out_of_host_memory" in line) signal("GPU_HOST_OUT_OF_MEMORY")
        // DXVK can create the device successfully, then fail and retry a window's
        // swapchain. Preserve that stage without declaring all Vulkan initialization
        // failed. A later properties dump is not proof of successful recovery.
        val swapchainCreateFailure = "presenter: failed to create vulkan swapchain:" in line
        if (swapchainCreateFailure) signal("VULKAN_SWAPCHAIN_CREATE_FAILED")
        when {
            "error while loading shared libraries" in line -> signal("NATIVE_LIBRARY_LOAD_FAILED")
            "error: file is not found" in line -> signal("BOX64_EXECUTABLE_MISSING")
            "is not an executable file" in line -> signal("BOX64_NOT_EXECUTABLE")
            "error: loading needed libs" in line || "error loading needed lib" in line -> signal("BOX64_LIBRARY_LOAD_FAILED")
            "error: relocating symbols" in line || "symbol lookup error" in line -> signal("SYMBOL_RESOLUTION_FAILED")
            "could not load kernel32" in line -> signal("WINE_KERNEL32_LOAD_FAILED")
            "failed to load ntdll" in line || "could not load ntdll" in line -> signal("WINE_NTDLL_LOAD_FAILED")
            "could not exec wineserver" in line || "wineserver failed" in line ||
                "wine server never started" in line || "wine server failed to run" in line -> signal("WINESERVER_START_FAILED")
            "err:module:import_dll" in line || "err:module:loader_init" in line -> signal("WINDOWS_DLL_LOAD_FAILED")
            "nodrv_createwindow" in line || "cannot open display" in line -> signal("DISPLAY_CONNECTION_FAILED")
            "vk_error_device_lost" in line -> signal("VULKAN_DEVICE_LOST")
            !gpuMemoryFailure && !swapchainCreateFailure && ("failed to create vulkan" in line || "failed to initialize vulkan" in line || "no adapters found" in line) -> signal("VULKAN_INITIALIZATION_FAILED")
            !gpuMemoryFailure && !swapchainCreateFailure && "vk_error" in line -> signal("VULKAN_CALL_FAILED")
            "steamapi_init" in line && ("fail" in line || "false" in line) -> signal("STEAM_CLIENT_UNAVAILABLE")
            "unhandled page fault" in line || "unhandled exception" in line -> signal("GUEST_UNHANDLED_EXCEPTION")
            "permission denied" in line || "operation not permitted" in line -> signal("ACCESS_DENIED")
            "cannot allocate memory" in line || "out of memory" in line -> signal("GUEST_OUT_OF_MEMORY")
        }
        if (trimmed.startsWith("wine-"))
            WINE_VERSION.find(trimmed)?.let { signal("WINE_VERSION_${it.groupValues[1]}") }
        if ("box64 arm64 v" in line) BOX64_VERSION.find(line)?.let { signal("BOX64_VERSION_${it.groupValues[1]}") }
        // Retain numeric Wine status codes and known shipped library names, never source text.
        if (line.contains("wine:") || WINE_ERROR.containsMatchIn(line)) {
            (STATUS.findAll(line) + LOADER_STATUS.findAll(line)).take(4).forEach { match ->
                val code = match.groupValues[1]
                signal("NTSTATUS_$code")
                STATUS_REASONS[code]?.let(::signal)
            }
        }
        // GetProcAddress probes and native-vs-builtin lookup misses are not missing DLLs.
        val optionalModuleProbe = "warn:module:ldrgetprocedureaddress" in line ||
            "warn:module:find_builtin_dll" in line
        if (!optionalModuleProbe && moduleLoadFailure == null && moduleImportFailure == null &&
            ("fail" in line || "error" in line || "not found" in line || "could not" in line)) {
            for (name in LIBRARIES) if (name in line) signal("COMPONENT_$name")
        }
        return signals.size != previousSignals || ubisoftModules.size != previousModules
    }

    /** Fixed category names only. Unknown channels and all raw text are discarded. */
    private fun countChannel(line: String) {
        var start = 0
        while (start < line.length && start < 16 && line[start].isWhitespace()) start++
        var end = start
        while (end < line.length && end - start < 8 && line[end] in "0123456789abcdef") end++
        if (end - start in 4..8 && line.getOrNull(end) == ':') start = end + 1
        val level = LEVELS.indexOfFirst { line.regionMatches(start, it, 0, it.length) }
        if (level < 0) { levels[4]++; return }
        levels[level]++
        val channelStart = start + LEVELS[level].length
        val channel = CHANNELS.indexOfFirst { line.regionMatches(channelStart, it, 0, it.length) &&
            line.getOrNull(channelStart + it.length) == ':' }
        channels[if (channel < 0) CHANNELS.size else channel]++
    }

    @Synchronized fun report(): String = "输出行数：$lines\n运行信号：${signals.joinToString().ifEmpty { "未匹配到已知错误" }}\n" +
        "GUEST_OUTPUT_V2 err=${levels[0]}; warn=${levels[1]}; fixme=${levels[2]}; trace=${levels[3]}; other=${levels[4]}; " +
        channels.indices.filter { channels[it] > 0 }.joinToString(",") { "${CHANNELS.getOrNull(it) ?: "other_channel"}=${channels[it]}" } +
        if (ubisoftModules.isEmpty()) "" else "\nUBISOFT_MODULES_V1 loaded=" + ubisoftModules.joinToString(",") + "; UI_READY_UNCONFIRMED"
    @Synchronized fun signalCount(): Int = signals.size
    @Synchronized fun signalCodes(): List<String> = signals.toList()

    companion object {
        private val MEMORY_MARKERS = setOf("aura_guest_memory_nx_v1", LegacyDataNames.BOX64_MEMORY_MARKER)
        private val WINE_VERSION = Regex("^wine-([0-9.]{1,20})$")
        private val BOX64_VERSION = Regex("(?:^|\\[box64] )box64 arm64 v([0-9.]{1,20}) ")
        private val LEVELS = listOf("err:", "warn:", "fixme:", "trace:")
        private val WINE_PREFIX = Regex("^\\s{0,16}(?:[0-9]{1,12}\\.[0-9]{1,6}:)?(?:[0-9a-f]{4,8}:){0,2}(?=(?:err|warn|fixme|trace):[a-z0-9_]+:)")
        private val CONTEXT_TRACE = Regex("trace:seh:rtlinitializeextendedcontext2 context [0-9a-f]{1,16}, " +
            "context_flags 0x[0-9a-f]{1,8}, context_ex [0-9a-f]{1,16}, compaction_mask [0-9a-f]{1,16}\\.")
        private val CRITICAL_SECTION_TIMEOUT = Regex(
            "err:sync:rtlpwaitforcriticalsection section (?:0x)?[0-9a-f]{1,16} [^\\r\\n]{1,1024} " +
                "wait timed out in thread [0-9a-f]{1,16}, blocked by [0-9a-f]{1,16}, retrying \\([0-9]{1,6} sec\\)")
        private val TURNIP_A2D_COORDINATE_ASSERTION = Regex(
            "(?:[^\\r\\n]{0,512}/)?src/freedreno/registers/adreno/a6xx-pack\\.xml\\.h:[0-9]{1,7}: " +
                "struct tu_reg_value pack_a6xx_gras_a2d_dest_br\\(struct a6xx_gras_a2d_dest_br\\): " +
                "assertion \"\\(fields\\.[xy] & 0xffffc000\\) == 0\" failed")
        private val WINE_ASSERTION_EXCEPTION = Regex(
            "trace:seh:dispatch_exception code=80000101 \\(exception_wine_assertion\\) flags=[0-9a-f]{1,8} addr=(?:0x)?[0-9a-f]{1,16}")
        private val UNHANDLED_ILLEGAL_INSTRUCTION = Regex(
            "wine: unhandled illegal instruction at address (?:0x)?[0-9a-f]{1,16} " +
                "\\(thread [0-9a-f]{1,8}\\), starting debugger\\.\\.\\.")
        private val CHANNELS = listOf("module", "seh", "virtual", "vulkan", "dxgi", "sync", "winediag", "mscoree", "wineboot")
        private val NTDLL_LOAD_FAILURE = Regex("(?:failed to load|could not load)\\s+[^\\r\\n]{0,1024}?\\bntdll(?:\\.dll|\\.so)?\\b")
        private val WINE_ERROR = Regex("(?:^|\\s)(?:[0-9a-f]{4,8}:)?(?:err|warn):[a-z0-9_]+:")
        private val MODULE_LOADED = Regex("(?:^|\\s)(?:[0-9a-f]{4,8}:){0,2}trace:loaddll:build_module loaded l\"([^\"\\r\\n]{1,1024})\" at [0-9a-f]+: native\\s*$")
        private val MODULE_LOAD_FAILURE = Regex("(?:^|\\s)(?:[0-9a-f]{4,8}:){0,2}(?:err|warn):module:load_dll failed to load module l\"([^\"\\r\\n]{1,1024})\"; status=(?:0x)?[c8][0-9a-f]{7}\\b")
        private val UBISOFT_MODULES = mapOf("ubisoftgamelauncher.exe" to "LAUNCHER", "ubisoftgamelauncher64.exe" to "LAUNCHER",
            "upc.exe" to "CLIENT", "uplaywebcore.exe" to "WEB_CORE", "libcef.dll" to "CEF")
        private val MODULE_IMPORT_FAILURE = Regex(
            "(?:^|\\s)(?:[0-9a-f]{4,8}:){0,2}err:module:import_dll " +
                "(?:library|loading library) (\\S{1,260}) \\(which is needed by l?\"[^\"\\r\\n]*\"\\) " +
                "(not found|failed \\(error (?:0x)?[c8][0-9a-f]{7}\\))\\.?\\s*$")
        // Fixed public component names only. Unknown imports still get an explicit UNLISTED
        // signal so the caller knows raw, locally saved Wine output is needed to identify them.
        private val WINDOWS_LIBRARIES = setOf(
            "ntdll", "kernel32", "kernelbase", "msvcrt", "ucrtbase", "concrt140", "vccorlib140",
            "vcruntime140", "vcruntime140_1", "vcruntime140_threads", "msvcp140_1", "msvcp140_2",
            "mfc140", "mfc140u",
            "msvcp140_atomic_wait", "msvcp140_codecvt_ids", "dxgi", "d3d8", "d3d9", "d3d10",
            "d3d10_1", "d3d10core", "d3d11", "d3d12", "d3d12core", "d3dx11_42", "d3dx11_43",
            "xinput1_1", "xinput1_2", "xinput1_3", "xinput1_4", "xinput9_1_0", "dinput", "dinput8",
            "dsound", "winmm", "mf", "mfplat", "mfplay", "mfreadwrite", "wmvcore", "quartz",
            "mscoree", "hostfxr", "hostpolicy", "coreclr", "unityplayer", "mono", "mono-2.0-bdwgc",
            "mono-2.0-sgen", "sdl2", "sdl3", "fna3d", "faudio", "openal32", "gdiplus", "winhttp",
            "wininet", "steam_api", "steam_api64", "steamclient", "steamclient64", "icudt68",
            "icuin68", "icuuc68",
        ) + listOf(60, 70, 71, 80, 90, 100, 110, 120, 140).flatMap { listOf("msvcr$it", "msvcp$it", "vcomp$it") } +
            (33..47).map { "d3dcompiler_$it" } + (24..43).map { "d3dx9_$it" } +
            (33..43).map { "d3dx10_$it" } + (0..9).map { "xaudio2_$it" } +
            (0..7).flatMap { listOf("x3daudio1_$it", "xactengine3_$it") } + (0..5).map { "xapofx1_$it" }
        // Exactly eight hex digits after Wine's status/error marker. Never collect arbitrary
        // numbers, memory addresses, surrounding text, user paths or exception messages.
        private val STATUS = Regex("\\b(?:status|error)[ :=]+(?:0x)?([c8][0-9a-f]{7})\\b")
        private val LOADER_STATUS = Regex("(?:failed to map the shared user data|failed to load (?:apiset|start\\.exe)):\\s*(?:0x)?([c8][0-9a-f]{7})\\b")
        private val STATUS_REASONS = mapOf(
            "c0000005" to "NT_ACCESS_VIOLATION", "c000000f" to "NT_FILE_NOT_FOUND",
            "c0000017" to "NT_NO_MEMORY", "c0000018" to "NT_ADDRESS_CONFLICT",
            "c0000022" to "NT_ACCESS_DENIED", "c0000034" to "NT_OBJECT_NAME_NOT_FOUND",
            "c000003a" to "NT_OBJECT_PATH_NOT_FOUND", "c000007b" to "NT_INVALID_IMAGE_FORMAT",
            "c000012f" to "NT_INVALID_IMAGE_NOT_MZ", "c0000135" to "NT_DLL_NOT_FOUND",
            "c0000142" to "NT_DLL_INITIALIZATION_FAILED",
            "c000001d" to "NT_ILLEGAL_INSTRUCTION",
        )
        private val LIBRARIES = listOf("libc.so.6", "libm.so.6", "libvulkan", "libgl.so", "libx11.so",
            "ntdll", "kernel32", "msvcp140", "vcruntime140", "ucrtbase", "d3d11", "dxgi", "d3d12", "winevulkan",
            "hostfxr", "hostpolicy", "coreclr", "sdl2", "fna3d", "faudio", "openal32", "unityplayer")

        /** Drain continuously with a bounded line buffer, including lines without a newline. */
        @JvmStatic @JvmOverloads @Throws(IOException::class)
        fun drain(input: InputStream, callback: Callback<String>, collecting: java.util.function.BooleanSupplier = java.util.function.BooleanSupplier { true }) {
            input.reader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(1024)
                val line = StringBuilder()
                var pending = false
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (!collecting.asBoolean) { line.setLength(0); pending = false; continue }
                    for (index in 0 until count) {
                        val char = buffer[index]
                        if (char == '\n') {
                            callback.call(line.toString())
                            line.setLength(0)
                            pending = false
                        } else {
                            pending = true
                            if (line.length < 4096) line.append(char)
                        }
                    }
                }
                if (pending && collecting.asBoolean) callback.call(line.toString())
            }
        }
    }
}
