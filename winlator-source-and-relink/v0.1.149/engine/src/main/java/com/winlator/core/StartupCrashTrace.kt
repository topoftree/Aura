/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import com.winlator.xenvironment.RootFS
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions

/** Private revocable lease, bounded native records and fixed graphics-error classifications. */
class StartupCrashTrace(private val directory: File, private val deadline: Long) {
    private var state = "NOT_CONFIGURED"
    private var errors = 0
    private var assertions = 0
    private var closed = false
    private var lease: FileChannel? = null
    private val recent = ArrayDeque<String>()
    private val control get() = File(directory, ".abort-control")
    private val events get() = File(directory, ".abort-events")

    fun configure(library: File, env: EnvVars) {
        // Logger already supports Wine output. Disable its separate unbounded disk log.
        env.put("DXVK_LOG_LEVEL", "error").put("DXVK_LOG_PATH", "none")
        if (!library.isFile) { state = "LIBRARY_MISSING"; return }
        try {
            val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            Files.createFile(control.toPath(), permissions)
            Files.createFile(events.toPath(), permissions)
            val bytes = ByteBuffer.allocate(64).order(LITTLE_ENDIAN)
                .put("AURAAB1\u0000".toByteArray()).putLong(deadline).putInt(1).array()
            val channel = FileChannel.open(control.toPath(), WRITE, NOFOLLOW_LINKS).also { lease = it }
            val data = ByteBuffer.wrap(bytes)
            while (data.hasRemaining()) check(channel.write(data) > 0)
            val preload = env.get("LD_PRELOAD").orEmpty()
            // Existing ARM64 assert entry must save original registers first; it
            // forwards the original caller through aura_startup_observe_assert.
            env.put("LD_PRELOAD", if (preload.isBlank()) library.path else "$preload ${library.path}")
                .put(VARIABLE, "1").put(DIRECTORY, directory.path)
            state = "CONFIGURED"
        } catch (_: Exception) {
            state = "STORAGE_UNAVAILABLE"
            revoke()
        }
    }

    fun observe(line: String, receivedElapsed: Long) {
        if (closed) return
        if (line.contains(":seh:dispatch_exception code=80000101")) assertions++
        val error = classifyGraphics(line) ?: return
        errors++
        if (recent.size == 32) recent.removeFirst()
        recent.addLast("STARTUP_GRAPHICS_ERROR_V1 sequence=$errors; host_receive_elapsed_ms=$receivedElapsed; $error")
    }

    private fun revoke() {
        val channel = lease ?: return
        lease = null
        runCatching {
            channel.use {
                check(channel.size() == 64L)
                val bytes = ByteBuffer.allocate(4).order(LITTLE_ENDIAN).putInt(0).apply { flip() }
                while (bytes.hasRemaining()) check(channel.write(bytes, 16L + bytes.position()) > 0)
            }
        }
    }

    fun close() { if (!closed) { closed = true; revoke() } }

    fun report(): String {
        val header = runCatching {
            FileChannel.open(control.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
                check(channel.size() == 64L)
                val bytes = ByteBuffer.allocate(64).order(LITTLE_ENDIAN)
                while (bytes.hasRemaining()) check(channel.read(bytes) > 0)
                check(bytes.array().copyOfRange(0, 8).contentEquals("AURAAB1\u0000".toByteArray()))
                bytes.getInt(20).coerceIn(0, 4) to bytes.getInt(24).coerceAtLeast(0)
            }
        }.getOrNull()
        val raw = runCatching {
            check(Files.isRegularFile(events.toPath(), NOFOLLOW_LINKS) && events.length() <= 32768)
            FileChannel.open(events.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
                val bytes = ByteBuffer.allocate(32769)
                while (bytes.hasRemaining() && channel.read(bytes) > 0) { /* bounded */ }
                check(bytes.position() <= 32768)
                String(bytes.array(), 0, bytes.position(), Charsets.US_ASCII)
            }
        }.getOrNull()
        // A native writer may be between records. Never reinterpret a partial last line.
        val lines = raw?.substringBeforeLast('\n', "")?.lineSequence()?.filter { it.isNotEmpty() }?.toList().orEmpty()
        val valid = lines.take(128).filter { NATIVE_ROW.matches(it) }
        val complete = valid.count { it.startsWith("NATIVE_ABORT_END_V1 ") }
        return buildString {
            appendLine("STARTUP_CRASH_CONTEXT_V1 state=$state; scope=SESSION_PROCESSES; target_exe=UNCONFIRMED; " +
                "native_ready=${header?.second ?: "UNKNOWN"}; claimed_events=${header?.first ?: "UNKNOWN"}; complete_events=$complete; " +
                "native_read=${if (raw == null) "UNAVAILABLE" else "AVAILABLE"}; rejected_rows=${lines.size - valid.size}; " +
                "partial_tail=${raw?.endsWith('\n') == false && raw.isNotEmpty()}; wine_assertions=$assertions; " +
                "graphics_errors=$errors; graphics_retained=${recent.size}; snapshot=NON_ATOMIC; graphics_clock=RECEIVE_ONLY")
            appendLine("Coverage: imported abort/assert entry points only; hidden calls, direct signals, PE/FEX frames and loader namespaces may be unavailable. Missing events do not exclude an abort. Return offsets are ELF image offsets, not file offsets or a precise fault PC. Native unwind is best effort; fatal behavior is preserved.")
            valid.forEach(::appendLine)
            recent.forEach(::appendLine)
        }
    }

    companion object {
        const val LIBRARY = "libaura_abort_trace.so"
        const val VARIABLE = "AURA_STARTUP_ABORT_TRACE"
        const val DIRECTORY = "AURA_STARTUP_ABORT_DIRECTORY"
        private val ERROR = Regex("^(?:[0-9]+\\.[0-9]+:[0-9a-f]+:[0-9a-f]+:)?err: {2,}", RegexOption.IGNORE_CASE)
        private val RESULT = Regex("\\bVK_(?:ERROR_[A-Z0-9_]+|TIMEOUT|NOT_READY)\\b")
        private val PIPELINE_CODE = Regex("Failed to (?:compile pipeline|create (?:base |vertex shader |fragment shader |compute shader |built-in compute |built-in graphics )?pipeline): (-?[0-9]{1,10})\\s*$")
        private val knownResults = setOf("VK_ERROR_DEVICE_LOST", "VK_ERROR_OUT_OF_HOST_MEMORY", "VK_ERROR_OUT_OF_DEVICE_MEMORY",
            "VK_ERROR_INITIALIZATION_FAILED", "VK_ERROR_INCOMPATIBLE_DRIVER", "VK_ERROR_EXTENSION_NOT_PRESENT",
            "VK_ERROR_FEATURE_NOT_PRESENT", "VK_ERROR_TOO_MANY_OBJECTS", "VK_ERROR_FORMAT_NOT_SUPPORTED",
            "VK_ERROR_FRAGMENTED_POOL", "VK_ERROR_UNKNOWN", "VK_ERROR_OUT_OF_POOL_MEMORY", "VK_ERROR_INVALID_EXTERNAL_HANDLE",
            "VK_ERROR_SURFACE_LOST_KHR", "VK_ERROR_NATIVE_WINDOW_IN_USE_KHR", "VK_ERROR_OUT_OF_DATE_KHR",
            "VK_ERROR_INVALID_SHADER_NV", "VK_ERROR_VALIDATION_FAILED_EXT", "VK_TIMEOUT", "VK_NOT_READY")
        private val NATIVE_ROW = Regex(
            "(?:NATIVE_ABORT_EVENT_V1 event=[1-4]; pid=[0-9]{1,10}; tid=[0-9]{1,10}; origin=(?:ABORT|ASSERT2|ASSERT); host_elapsed_ms=[0-9]{1,19}; fatal=UNCHANGED|" +
            "NATIVE_ABORT_FRAME_V1 event=[1-4]; index=(?:[0-9]|1[0-5]); kind=(?:CALLER_RETURN|UNWIND_RETURN); module=(?:UNKNOWN|lib[A-Za-z0-9_.-]{1,90}\\.so|" +
                "(?:notadreno_utils|notgsl|notllvm-glnext|notllvm-qcom|notllvm-qgl|vulkan\\.ad0863|vulkan\\.adreno)\\.so); image_offset=0x[0-9a-f]{1,16}|" +
            "NATIVE_ABORT_REASON_V1 event=[1-4]; source=(?:ANDROID_MESSAGE|UNOBSERVED); category=(?:OUT_OF_MEMORY|SPIRV|COMPILER|ASSERTION|UNREACHABLE|UNCLASSIFIED|UNOBSERVED); text=NOT_EXPORTED|" +
            "NATIVE_ABORT_END_V1 event=[1-4]; frames=(?:[1-9]|1[0-6]); unwind=BEST_EFFORT)")

        fun isGraphicsOutput(line: String) = ERROR.containsMatchIn(line)

        fun classifyGraphics(line: String): String? {
            if (!isGraphicsOutput(line)) return null
            val bounded = line.take(2048)
            val lower = bounded.lowercase(java.util.Locale.ROOT)
            val category = when {
                "device lost" in lower || "vk_error_device_lost" in lower -> "DEVICE_LOST"
                "out of" in lower && "memory" in lower || "vk_error_out_of" in lower -> "OUT_OF_MEMORY"
                "pipeline" in lower -> "PIPELINE"
                "shader" in lower || "spir-v" in lower -> "SHADER"
                "submit" in lower -> "SUBMISSION"
                "present" in lower || "swapchain" in lower -> "PRESENTATION"
                "fence" in lower || "semaphore" in lower -> "SYNCHRONIZATION"
                else -> "UNCLASSIFIED"
            }
            // Only public result identifiers; no paths, messages, shader data or addresses.
            val result = RESULT.find(bounded)?.value?.takeIf { it in knownResults } ?: "UNOBSERVED"
            val code = PIPELINE_CODE.find(bounded)?.groupValues?.get(1)?.toIntOrNull()
            return "category=$category; result=$result; return_code=${code ?: "UNOBSERVED"}; source=DXVK_PREFIX_UNCONFIRMED"
        }

        fun silence(env: EnvVars) {
            env.put(VARIABLE, "0").remove(DIRECTORY)
            env.remove(StartupVulkanTrace.VARIABLE)
            val preload = env.get("LD_PRELOAD").orEmpty().split(Regex("[\\s:]+"))
                .filter { it.isNotBlank() && File(it).name != LIBRARY }.joinToString(" ")
            if (preload.isEmpty()) env.remove("LD_PRELOAD") else env.put("LD_PRELOAD", preload)
        }

        fun configure(context: Context, env: EnvVars, capture: StartupExceptionCapture?) {
            silence(env)
            if (RuntimeDiagnostics.enabled() && capture?.recording() == true)
                capture.configureCrashTrace(File(context.applicationInfo.nativeLibraryDir, LIBRARY), env, RootFS.find(context).rootDir)
        }
    }
}
