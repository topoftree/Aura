/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A bounded, private, recoverable capture. Never reads account/configuration files or whole memory. */
class GameDiagnosticSource private constructor(
    val entry: String, private val file: File, private val limit: Int,
    private val baseline: Stamp?, private val anchors: ByteArray?, private val fromStart: Boolean,
    private val beforeRead: () -> Boolean,
) {
    private data class Stamp(val size: Long, val modified: Long, val key: Any?)
    fun copyTo(destination: File): String {
        if (runCatching { file.canonicalFile == destination.canonicalFile }.getOrDefault(true))
            return "$entry\tDESTINATION_CONFLICT"
        destination.delete()
        return try {
            if (!beforeRead()) return "$entry\tFLUSH_FAILED"
            val before = stamp(file) ?: return "$entry\tMISSING_OR_NOT_REGULAR"
            if (!fromStart && before == baseline) return "$entry\tUNCHANGED_FROM_LAUNCH"
            FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { input ->
                val observed = anchor(input, before.size)
                val start = if (!fromStart && baseline != null && before.key == baseline.key &&
                    before.size >= baseline.size && anchors != null && anchor(input, baseline.size).contentEquals(anchors)) baseline.size else 0L
                val size = before.size - start
                if (size == 0L) return "$entry\tNO_NEW_BYTES"
                val retained = minOf(size, limit.toLong())
                val truncated = size > retained
                // Keep the beginning and end of this launch's slice, with exact source offsets.
                val head = if (truncated) retained / 2 else retained
                val tail = retained - head
                destination.outputStream().use { output ->
                    copyRange(input, output, start, head)
                    if (tail > 0) copyRange(input, output, before.size - tail, tail)
                }
                val after = stamp(file)
                if (after == null || after.key != before.key || after.size < before.size ||
                    stampChannelAnchorChanged(input, before, after) || !anchor(input, before.size).contentEquals(observed)) {
                    destination.delete(); return "$entry\tCHANGED_DURING_READ"
                }
                "$entry\tCAPTURED\tfrom=$start\tend=${before.size}\tbytes=$retained\ttruncated=$truncated" +
                    "\thead=$head\ttail=$tail\tsha256=${sha256(destination)}"
            }
        } catch (_: Exception) { destination.delete(); "$entry\tUNREADABLE" }
    }

    private fun stampChannelAnchorChanged(input: FileChannel, before: Stamp, after: Stamp): Boolean =
        before.size == after.size && before.modified != after.modified || input.size() < before.size

    companion object {
        fun watch(entry: String, path: File, limit: Int, fromStart: Boolean = false,
                  beforeRead: () -> Boolean = { true }): GameDiagnosticSource {
            require(entry.matches(Regex("[a-z0-9_.-]+")) && limit in 1024..24 * 1024 * 1024)
            val file = File(requireNotNull(path.parentFile).canonicalFile, path.name)
            val baseline = stamp(file)
            val anchors = if (baseline != null && !fromStart) runCatching {
                FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { anchor(it, baseline.size) }
            }.getOrNull() else null
            return GameDiagnosticSource(entry, file, limit, baseline, anchors, fromStart, beforeRead)
        }
        private fun stamp(file: File): Stamp? = runCatching {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                .takeIf { it.isRegularFile }?.let { Stamp(it.size(), it.lastModifiedTime().toMillis(), it.fileKey()) }
        }.getOrNull()
        private fun anchor(input: FileChannel, size: Long): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            for (offset in listOf(0L, maxOf(0, size - 512))) {
                val buffer = ByteBuffer.allocate(minOf(size, 512).toInt())
                while (buffer.hasRemaining()) check(input.read(buffer, offset + buffer.position()) > 0)
                digest.update(buffer.array())
            }
            return digest.digest()
        }
        private fun copyRange(input: FileChannel, output: OutputStream, offset: Long, size: Long) {
            var copied = 0L
            val buffer = ByteBuffer.allocate(32 * 1024)
            while (copied < size) {
                buffer.clear().limit(minOf(buffer.capacity().toLong(), size - copied).toInt())
                val n = input.read(buffer, offset + copied)
                check(n > 0)
                output.write(buffer.array(), 0, n); copied += n
            }
        }
        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(32 * 1024)
                while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

object GameDiagnosticArchive {
    const val STORE = "diagnostics/game-bundle"
    private const val DIRECTORY = "capture"
    private const val ARCHIVE = "game-debug-latest.zip"
    private val lock = Any()
    private val fixed = setOf("session.txt", "report-before.txt", "report-after.txt", "sources.tsv", "status.txt", "trigger.txt",
        "wine-wake-before.txt", "wine-wake-after.txt", "graphics-before.txt", "graphics-after.txt", "graphics-history.txt",
        "vulkan-fault-before.txt", "vulkan-fault-after.txt", "graphics-cadence.txt", "x11-transport.txt",
        "fex-statistics.txt", "fex-jit-capture.txt", "jit-present-timeline.txt", "code-protection-before.txt", "code-protection-after.txt", "network-control.txt",
        "display-state-before.txt", "display-state-after.txt", "system-pressure.txt", "performance-window.txt", "dxvk-flow.txt", "dxvk-color.txt",
        "game-loop-command.txt", "final-display-cadence.txt", "visual-state.txt", "frame-pipeline.txt", "thread-scheduling.txt")

    @Synchronized fun begin(store: File, session: String, capture: String, started: Long, report: String) = synchronized(lock) {
        require(session.matches(Regex("[a-f0-9-]{36}")) && capture.matches(Regex("[a-f0-9-]{36}")))
        check(store.isDirectory || store.mkdirs())
        val directory = File(store, DIRECTORY)
        if (directory.exists()) { check(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)); directory.deleteRecursively() }
        check(directory.mkdir())
        write(store, "session.txt", "GAME_DIAGNOSTIC_BUNDLE_V1\nsession=$session\ncapture=$capture\nstarted_utc_ms=$started\n")
        write(store, "report-before.txt", report)
        write(store, "status.txt", "state=COLLECTING\ncompleted_batches=0\n")
    }

    fun write(store: File, name: String, text: String) = synchronized(lock) {
        require(name in fixed || name.matches(Regex("sample-0[1-6]\\.txt")) ||
            name.matches(Regex("thread-scheduling-0[1-8]\\.txt")) ||
            name.matches(Regex("frame-pipeline-(present|draw|read|display|wait)-0[1-8]\\.txt")) ||
            name.matches(Regex("frame-pipeline-wait-step-(0[1-9]|[12][0-9]|3[0-2])\\.txt")) ||
            name.matches(Regex("x11-transport-(0[1-9]|[12][0-9]|3[0-2])\\.txt")))
        require(text.length <= 64 * 1024)
        val directory = directory(store)
        val temp = File(directory, "$name.tmp")
        temp.writeText(text)
        Files.move(temp.toPath(), File(directory, name).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    fun wakeBytes(store: File, name: String, bytes: ByteArray) = synchronized(lock) {
        require(name.matches(Regex("wine-wake-(before|after)-0[1-4]\\.bin")))
        require(WakeTraceSnapshot.validHeader(bytes))
        val directory = directory(store)
        val temp = File(directory, "$name.tmp")
        temp.writeBytes(bytes)
        Files.move(temp.toPath(), File(directory, name).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    fun graphicsBytes(store: File, name: String, bytes: ByteArray) = synchronized(lock) {
        require(name.matches(Regex("graphics-(before|after)-(0[1-9]|1[0-6])\\.bin")))
        require(GraphicsTraceSnapshot.validHeader(bytes))
        val directory = directory(store)
        val temp = File(directory, "$name.tmp")
        temp.writeBytes(bytes)
        Files.move(temp.toPath(), File(directory, name).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    fun cadenceBytes(store: File, name: String, bytes: ByteArray) = synchronized(lock) {
        require(name.matches(Regex("graphics-cadence-0[1-4]\\.bin")))
        require(GraphicsCadenceSnapshot.validHeader(bytes))
        val directory = directory(store)
        val temp = File(directory, "$name.tmp")
        temp.writeBytes(bytes)
        Files.move(temp.toPath(), File(directory, name).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    fun codeBytes(store: File, name: String, bytes: ByteArray) = synchronized(lock) {
        require(name.matches(Regex("code-protection-(before|after)-(0[1-9]|1[0-6])\\.bin")))
        require(CodeTraceSnapshot.validHeader(bytes))
        val temp = File(directory(store), "$name.tmp")
        temp.writeBytes(bytes)
        Files.move(temp.toPath(), File(directory(store), name).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    fun logs(store: File, sources: List<GameDiagnosticSource>, current: () -> Boolean = { true }) = synchronized(lock) {
        require(sources.size <= 12 && sources.map { it.entry }.distinct().size == sources.size)
        val directory = directory(store)
        val statuses = sources.map { source ->
            val destination = File(directory, source.entry)
            if (!current()) "${source.entry}\tSESSION_CHANGED" else {
                val status = source.copyTo(destination)
                if (current()) status else { destination.delete(); "${source.entry}\tSESSION_CHANGED" }
            }
        }
        val expected = listOf("guest-debug.log", "steam-bootstrap_log.txt", "steam-cef_log.txt", "steam-webhelper.txt",
            "steam-webhelper_gpu.txt", "ubisoft-launcher_log.txt", "ubisoft-game_starter_log.txt",
            "game-engine-root-1.log", "game-engine-root-2.log", "game-engine-executable-1.log", "game-engine-executable-2.log")
        val missing = expected.filter { name -> sources.none { it.entry == name } }.map { "$it\tNOT_CONFIGURED_FOR_LAUNCH" }
        write(store, "sources.tsv", "entry\tstatus\trange_and_hash\n" + (statuses + missing).joinToString("\n") +
            "\nNo old client directories were searched. Logging settings were preserved.\n")
    }

    fun available(store: File): Boolean = synchronized(lock) {
        checkpointAvailable(store) || File(store, ARCHIVE).let { it.isFile && it.length() > 0 }
    }

    fun export(store: File, output: OutputStream) = synchronized(lock) {
        // Recover complete checkpoints even if Android killed the host during collection.
        if (checkpointAvailable(store)) publish(store)
        val file = File(store, ARCHIVE)
        check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        file.inputStream().use { it.copyTo(output) }
    }

    fun publish(store: File) = synchronized(lock) {
        val directory = directory(store)
        val files = directory.listFiles().orEmpty().filter {
            !it.name.endsWith(".tmp") && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS)
        }.sortedBy { it.name }
        // Existing sources plus 32 transport chunks, four cadence files and summaries.
        check(files.size in 3..240)
        check(files.sumOf(File::length) <= 40L * 1024 * 1024)
        val temp = File(store, "$ARCHIVE.tmp")
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                fun text(name: String, text: String) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
                }
                text("README.txt", "One capture, one session. Capture times and completion are in session.txt/status.txt.\n" +
                    "graphics-cadence.txt and graphics-cadence-NN.bin retain up to 8192 native Vulkan call entries/returns per target process (at most four), only during the manual ten-second window. Timings use absolute host monotonic and calling-thread CPU clocks, include observer overhead and overlap across threads; they are not GPU execution times. RETURN_UNOBSERVED is neither success nor proof of a hung call. Missing wrappers, drops, rejected/torn rows and exhausted budgets remain unknown. No handles or arguments are retained. Decode with tools/diagnostics/graphics-trace/cadence_decode.py.\n" +
                    "x11-transport.txt and x11-transport-NN.txt retain at most 4800 host read/decoded dispatch boundaries for eight capture-local clients. Times are relative to start_ns; read is a Java callback/recv boundary, not kernel wake or guest send. Read IDs identify the latest observed read, not full packet provenance; fragmented requests can span reads. Present IDs join this capture's frame pipeline only. Dispatch includes its child copy/wait time and must not be added to it. Skips invalidate later read associations. Payloads, opcodes, FDs and addresses are omitted.\n" +
                    "frame-pipeline.txt and frame-pipeline-{present,draw,read,display,wait,wait-step}-NN.txt retain bounded manual host frame stages, including intervals below 50 ms. Present IDs and drawable slots are local to this capture, not guest frame IDs. Read rows join a host draw to the latest completed copy under the same drawable lock; zero means unknown, repeats do not prove physical duplicates. Display rows use absolute Android monotonic timestamps; other rows use nanoseconds relative to start_ns. EGL frame IDs join supported actual display queries only; missing timestamps and unobserved eglSwapBuffers duration stay unknown. EGL server-wait timing is enqueue cost, not GPU wait duration. Wait rows record completion strategy, client-wait and old-Idle drain wall time, fallback reason, zero-timeout/no-flush destination read-fence readiness, and timer wake/lock versus actual Idle dispatch source. Wait-step rows retain at most 4800 planned waits/fallbacks, requested timeout, actual host call bounds/result, exact selected reservation's capture-local source Present (zero if unknown), eligible deadline and known-distinct/alias/unknown counts. An incomplete call has -1 bounds/result; observer skips and overflow remain explicit. No buffer allocation IDs or native handles are exported. Timer marks belong to that ticket's scheduled task, which may lose to another drain; they do not prove it dispatched Idle. The normal completion path may drain already-completed old buffers at their deadlines while awaiting the current copy fence, with bounded waits and blocking completion fallback; diagnostic readiness probes never replace synchronization. Legacy captures include PC probe operation intervals, not exact stopped durations; V2 performance windows place all PC probes after these frame observations. No actual GPU execution or physical scanout timing is inferred.\n" +
                    "performance-window.txt V2 brackets ten seconds of performance observation BEFORE the six three-point PC batches and anonymous network control. Later sample-NN.txt PC positions cannot be assigned to earlier long frames. This is still a Debug run, not uninstrumented performance; pixel/native trace/counter overhead remains. End snapshots are not atomic. Code-protection retains its independent eight-second native limit.\n" +
                    "thread-scheduling.txt and up to eight numbered chunks add bounded read-only process/thread CPU, runqueue, context-switch, fault and I/O counters on the same host monotonic clock. At most four session-owned selected-EXE processes, eight selected threads, 200 ticks/ten seconds; refresh at least 500 ms apart. Read wall budget 500 ms, checked between bounded reads (one operation can overrun). Missing counters are ?, zero schedstat can be disabled, other threads/transient waits remain unobserved. Cumulative counters require matching PID/TID start times and nondecreasing values before differencing. R/S/O include observer spans; R is inside S, not additive. POLL/SOCKET is not proof of Internet waits or authentication failure. No login changes or traffic interception.\n" +
                    "system-pressure.txt adds bounded numeric thermal status, per-core reported frequency, available memory, system VM/PSI deltas and selected EXE process faults. Unknown, reset, partial scan and unreadable counters remain explicit; system pressure is not attributable to the game, and readings do not prove throttling or memory-caused stalls.\n" +
                    "dxvk-color.txt records up to three manual Cuphead D3D11 pre-presentation back-buffer samples, nine texels per attempt. Only RGBA channel aggregates are exported, with source/image-view and presentation-target formats. Completion uses existing DXVK resource tracking; no extra GPU wait is inserted. Unsupported, pending, failed and revoked observations are not black pixels. This is before the DXVK presentation blit; host AHB points are separate frames unless independently associated. Readback can perturb rendering.\n" +
                    "dxvk-flow.txt contains manual-only D3D9 CPU wall intervals by Wine TID and fixed wait/flush/query/submission/pipeline reason. The rebuilt DLL uses a private 36 KiB page, 128 bounded rows, 500 ms bins, at most 50000 completed scopes and a ten-second native deadline. Retained detailed rows are limited to the largest 24; totals cover all stable accepted rows. QPC is correlated through a UTC bracket, not assumed identical to host monotonic time. Nested/parallel intervals overlap, pending operations and crossing operations are excluded, and GPU execution is not measured. DLL availability and dropped/budget/torn observations remain explicit. No raw resource pointer, shader bytes or per-frame text logging is added.\n" +
                    "display-state-before/after.txt and the displayState report section contain fixed numeric BF3 profile fields, monitor/viewport sizes, the last 16 mode transitions and the renderer's computed host transform with age/source checks. Profiles are candidates, not proof of which file the game loaded. Missing values are not zero. No profile paths, account data or frame pixels are exported; computed geometry is not a GPU viewport readback or proof that black borders are outside the game. Snapshots are not atomic.\n" +
                    "fex-jit-capture.txt adds manual-only SHM v3 aggregates (10s, 50000 hooks/thread, 256 live slots). Repeat attempts compare full private entry address and MaxInst within the same thread/window; they include races and are not proof of unchanged instruction bytes. Capacity failure is recorded at the actual failed allocator branch; creation/adoption and other clear requests are separate. Partial tables, expired/budget flags, event wrapping, unstable slots and exited threads limit coverage. Guest repeat keys and code bytes are never exported.\n" +
                    "jit-present-timeline.txt retains up to 64 host Present gaps of at least 50ms across at most 32 windows, and probe-operation overlap. Window ownership/display FPS are unconfirmed. CNTVCT anchor is bracketed by host monotonic nanoseconds for correlation; probe overlap is perturbed, not the exact stop duration. Clock bins use compile completion time and inclusive elapsed ticks, not CPU time.\n" +
                    "fex-statistics.txt compares live FEX Windows thread slots during this capture. JIT counts include first-time compilation, not only recompilation; SMC counters are exception candidates, not confirmed SMC/loops. CNTVCT timing uses the reference clock, not CPU frequency or CPU utilization. Exception/JIT/lock times overlap and must not be summed. Reset/replaced/exited threads are excluded; legacy v2 slot and Wine TID reuse between reads cannot be ruled out. Debug statistics require a new game process; OFF/unknown is not zero.\n" +
                    "SHM v4 adds read-only Android auxv / Wine registry / effective FEX CPU selections and actual startup cache state. Missing capabilities are UNKNOWN; differences can reflect preserved CPU errata or SVE context restrictions. FEX_CACHE_LOOKUP_V1 separates rejected lookups, pending-write / resident-buffer / storage blob hits and code actually loaded. Storage may be OS-cached, not physical I/O; no-matching-content is not proof of self-modification. RAM bytes are per-process gauges repeated on threads: never sum them. Counts are bounded by the existing manual window and share its observation budget; dropped or exited threads remain unknown.\n" +
                    "code-protection-before/after.txt and *.bin observe the verified native Wine NtProtectVirtualMemory syscall-table entry, only inside an explicit capture window (8 seconds, 100000 calls/process). Original arguments, return status, errno and signal behavior are preserved. Direct mprotect/other APIs are not covered. Executable-request or previous-executable ranges, failed returns, repeated ranges and elapsed/thread-CPU times are evidence, not proof of FEX-origin SMC or a loop. Addresses describe requested ranges, not an exact kernel mapping change. Dropped calls, foreign handles, unreadable inputs, row overflow and event wrap are explicit. The binary retains 64 ranges and the last 128 returns. Timings include observer/ptrace interference. Decode with tools/diagnostics/code-protection/decode.py.\n" +
                    "game-engine-*.log watches only Game.log/game.log at the game root and selected executable directory, baselined before launch. Unchanged old files are excluded, appends include only new bytes, rewritten files restart at zero. LogBackups are never searched. sources.tsv reports missing, unchanged, bounded/truncated or rejected sources; this is not a guarantee that an engine wrote/flushed a current log.\n" +
                    "FEX_GUEST_MAP_V2 gives the rejection stage for bounded FEX metadata reads, including frame alignment, missing/unreadable mappings and range failure. Readable RWX Wine state pages are allowed; entry signatures, metadata ranges and consistency checks remain mandatory. MAPPED_ENTRY uses only the exact current adjacent entry header, not stale State.rip or a backward scan. FEX_GUEST_LOCATION_V1 reports the nearest guest address and mapping-relative/file offset, NOT a PE RVA, exact faulting instruction or backtrace.\n" +
                    "WINE_THREAD_REQUEST_V2 observes existing ordinary manual-capture stops only. After pinned native Wine/Bionic checks, SEND_HEADER source reads the fixed outgoing header without relying on a name. During replies, WOW64_ORIGINAL_HEADER validates the exact caller code/frame and preserved original 32-bit header; NATIVE_NAME requires a verified native caller and immutable allowlisted name. The current reply union is never interpreted as a request. source identifies accepted evidence; detail distinguishes invalid headers, unknown callers/names and rejected layouts. NtYieldExecution stages remain separate. Read failures/changes and unsupported sites/versions stay unknown, not zero calls. No payloads, FDs, handles, raw stack, pointers or data-register values are exported. Samples are NOT complete call history, request duration, completion status or evidence of deadlock; repeated names do not identify the same invocation.\n" +
                    "graphics-before/after.txt and graphics-history.txt contain optional per-process GLX/Vulkan host call aggregates for ALL games. graphics-*.bin retains stable per-thread rows. Native data includes clients; TARGET_EXE / SESSION_OTHER / UNCONFIRMED ownership is reported separately. Only verified non-executable GOT slots and resolved function pointers are observed. Unsupported or unobserved metrics are not zero. Durations include nested calls and worker overlap: do not sum them into frame time. CPU is thread CPU; GPU execution is unmeasured. Call rate is not actual game FPS. Pause crossings are omitted; diagnostic thread capture can perturb intervals. Original arguments, results, timeouts and rendering settings are preserved. Decode with tools/diagnostics/graphics-trace/decode.py.\n" +
                    "vulkan-fault-before/after.txt identifies first observed actual native API error and device loss, distinct from repeated DXVK log messages. Text includes up to 8 recent returns per process; the filtered graphics binaries retain up to 24. Fault history can predate the manual capture. Auto collection lasts 120 s from the first graphics call; manual capture can extend 15 s without resetting the 131072-call budget or frozen fault. Missing data is unknown. Driver fault queries run only with the existing enabled feature; only bounded counts/categories, never raw addresses, descriptions or vendor binary data, are exported. See docs/VULKAN_DEVICE_LOSS_DIAGNOSTICS.md.\n" +
                    "visual-state.txt contains at most 54 numeric host copy/draw/EGL/UI snapshots within the manual ten-second window. Metadata and app window requests are not actual pixels, compositor dataspace or panel HDR. Missing stages/fields are UNKNOWN. GL queries can perturb the few observed calls. See docs/GAME_VISUAL_DIAGNOSTICS.md.\n" +
                    "Current builds collect this bundle manually. Legacy bundles may contain trigger.txt for an automatic suspected Present gap; a gap alone does not establish a game freeze.\n" +
                    "WINE_SYNC records fixed-layout read-only observations. The historical critical-section candidate alone does NOT identify the sampled thread's current lock. WINE_CRITICAL_WAIT_FRAME, when CAPTURED, validates the pinned x86 callsite, arguments and Wine TID; MATCH relates that call to the candidate. A cleared node address does not prove alert delivery. Owner is a Wine TID, not a Linux TID; a wake flag is not proof of a missed wakeup.\n" +
                    "Legacy wine-wake-*.bin contains bounded native call history for the Mirror's Edge Linux leader. This automatic stall capture and its native injection are retired. For archived bundles, see wine-wake-before/after.txt for coverage. The old diagnostic layer redirected three verified syscall-table entries and one private completion entry to wrappers that call the original functions; timing is perturbed. No timeout, alert flag or game setting is changed by the observer. Only direct WAIT_RETURN/STATUS_ALERTED identifies native alert consumption; COMPLETION_RETURN can also report an already-completed queue node without consuming an alert. Completion and flag observations are boundaries, not atomic with the original call. Legacy fixed-layout queue/frame snapshots fail closed on the new runtime. Snapshots filter unstable slots, may wrap/drop events, and do not prove a fixed stall. Decode with tools/diagnostics/wine-wake-trace/decode.py.\n" +
                    "COLLECTING means the final checkpoint was not reached; retained samples are still usable.\n" +
                    "Sources are bounded snapshots, not a claim of a complete log or game readiness. Truncated log files join the head and tail; sources.tsv gives both ranges.\n" +
                    "Native batches rotate Linux leader / hottest comparable worker / second worker, at most 18 points. Missing/stale workers are skipped.\n" +
                    "Manual captures and worker validation inspect up to 256 thread entries; the periodic monitor keeps its 64-entry budget. GAME_THREAD_SCAN records limits, unreadable threads and directory failures. A partial scan is not a total thread count.\n" +
                    "Signal-delivery stops remain separate from regular PC samples and are forwarded unchanged. SIGNAL_CODE_CONTEXT/REF uses the same 64-byte executable-code and 4-KiB stack-read bounds, retaining at most 16 executable references, not raw stack words or a backtrace. Validated nearest FEX guest mappings may be included; they are not exact faulting x86 registers or proof of a crash. No signal retry or handler suppression.\n" +
                    "Worker rank uses interval CPU time of surviving threads, not a bottleneck verdict. Thread identity and timing are in each sample.\n" +
                    "Capture briefly stops the selected thread; overlapping frame intervals are perturbed. CODE_REF entries are executable candidates, NOT an unwound call chain.\n" +
                    "Context flags: 1=code unreadable/out of bounds, 2=stack unavailable, 4=maps I/O, 8=maps limit, 16=reference limit.\n" +
                    "No full memory dump, raw stack, raw register dump, credentials/config files or automatic upload. Client logs may contain personal data.\n")
                for (file in files) {
                    zip.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
                text("checksums.sha256", files.joinToString("\n", postfix = "\n") { "${GameDiagnosticSource.sha256(it)}  ${it.name}" })
            }
            Files.move(temp.toPath(), File(store, ARCHIVE).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    private fun directory(store: File): File = File(store, DIRECTORY).also {
        check(Files.isDirectory(it.toPath(), NOFOLLOW_LINKS))
    }

    private fun checkpointAvailable(store: File) = listOf("session.txt", "report-before.txt", "status.txt").all {
        Files.isRegularFile(File(store, "$DIRECTORY/$it").toPath(), NOFOLLOW_LINKS)
    }
}
