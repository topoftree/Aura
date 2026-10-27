/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.Locale

/** All games share this observer. Debug mode, not a game identity, owns collection. */
object NativeGraphicsTrace {
    const val LIBRARY = "libaura_graphics_trace.so"
    const val VARIABLE = "AURA_GRAPHICS_TRACE"
    const val DIRECTORY = "AURA_GRAPHICS_DIRECTORY"
    const val BYTES = 28672
    private val uuid = Regex("[a-f0-9-]{36}")
    private val fileName = Regex("graphics-(0[1-9]|1[0-6])\\.bin")
    private data class Session(val id: String, val directory: File, val executable: String,
        val ownership: MutableMap<Pair<Int, Long>, String> = mutableMapOf(),
        val history: ArrayDeque<String> = ArrayDeque(), var previous: Map<String, GraphicsTraceSnapshot.Result> = emptyMap(),
        var lastPoll: Long = 0, var epoch: Long = 0, var unreadable: Int = 0)
    private var session: Session? = null
    private var cadence: NativeGraphicsCadence? = null

    fun silence(env: EnvVars) {
        env.put(VARIABLE, "0"); env.remove(DIRECTORY)
        // The same native library also supplies automatic Mesa copy helpers.
        // Turning off collection must not disable the rendering optimization.
        if (NativeFrameCopy.enabled(env)) return
        val kept = env.get("LD_PRELOAD").orEmpty().split(Regex("[\\s:]+"))
            .filter { it.isNotBlank() && File(it).name != LIBRARY }.joinToString(" ")
        if (kept.isEmpty()) env.remove("LD_PRELOAD") else env.put("LD_PRELOAD", kept)
    }

    fun configure(context: Context, env: EnvVars) {
        val optedOut = env.get(VARIABLE) == "0"
        silence(env)
        val intent = (context as? Activity)?.intent ?: return
        val id = intent.getStringExtra("aura_launch_id")?.takeIf(uuid::matches) ?: return
        if (optedOut || !RuntimeDiagnostics.sessionEnabled(context)) return
        val library = File(context.applicationInfo.nativeLibraryDir, LIBRARY)
        if (!library.isFile) {
            RuntimeLaunchDiagnostics.detail(context, "GRAPHICS_TRACE_V1 state=LIBRARY_MISSING"); return
        }
        val directory = runCatching { prepare(context, id, intent.getStringExtra("exec_path").orEmpty()) }.getOrNull()
        if (directory == null) {
            RuntimeLaunchDiagnostics.detail(context, "GRAPHICS_TRACE_V1 state=STORAGE_UNAVAILABLE"); return
        }
        NativeFrameCopy.setLibrary(env, library)
        env.put(VARIABLE, "1").put(DIRECTORY, directory.path)
        RuntimeLaunchDiagnostics.detail(context, "GRAPHICS_TRACE_V1 state=CONFIGURED; scope=ALL_GAMES; " +
            "apis=GLX_VULKAN; storage=BOUNDED_AGGREGATES; timing=HOST_WALL_AND_THREAD_CPU; " +
            "copy_timing=SUBMIT_STAGE; gpu_execution=UNMEASURED; vulkan_fault=NATIVE_RETURNS_BOUNDED_V1")
    }

    @Synchronized private fun prepare(context: Context, id: String, executable: String): File {
        session?.takeIf { it.id == id }?.let { return it.directory }
        discard()
        val root = File(context.filesDir, "diagnostics/graphics")
        check(root.isDirectory || root.mkdirs())
        check(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS))
        root.listFiles().orEmpty().filter { uuid.matches(it.name) }.forEach(::retire)
        val directory = File(root, id); check(directory.mkdir())
        session = Session(id, directory, executable)
        return directory
    }

    @Synchronized fun discard() { cadence?.close(); cadence = null; session?.let { runCatching { retire(it.directory) } }; session = null }

    @Synchronized fun startCadence(id: String, now: Long = System.nanoTime()) {
        if (!RuntimeDiagnostics.enabled()) return
        val current = session?.takeIf { it.id == id && it.epoch % 2 == 0L } ?: return
        cadence?.close()
        val targets = capture(current).filterValues { current.ownership[it.pid to it.startTicks] == "TARGET_EXE" }.toList()
        cadence = NativeGraphicsCadence.arm(current.directory, targets, now)
    }

    @JvmStatic @Synchronized fun stopCadence(id: String? = null) {
        if (id == null || session?.id == id) cadence?.revoke()
    }

    @Synchronized fun cadenceSnapshot(id: String): NativeGraphicsCadence.Snapshot =
        if (session?.id == id) cadence?.snapshot() ?: NativeGraphicsCadence.Snapshot(emptyMap(), "GRAPHICS_CADENCE_V1 state=NOT_ARMED\n")
        else NativeGraphicsCadence.Snapshot(emptyMap(), "GRAPHICS_CADENCE_V1 state=WRONG_SESSION\n")

    private fun writeControl(file: File, offset: Long, value: Long, int: Boolean = false) = runCatching {
        FileChannel.open(file.toPath(), READ, WRITE, NOFOLLOW_LINKS).use { channel ->
            check(channel.size() == BYTES.toLong())
            val header = ByteBuffer.allocate(80).order(LITTLE_ENDIAN)
            while (header.hasRemaining()) check(channel.read(header) > 0)
            check(GraphicsTraceSnapshot.validHeader(header.array(), BYTES))
            val data = ByteBuffer.allocate(if (int) 4 else 8).order(LITTLE_ENDIAN)
            if (int) data.putInt(value.toInt()) else data.putLong(value)
            data.flip()
            while (data.hasRemaining()) check(channel.write(data, offset + data.position()) > 0)
        }
    }

    private fun retire(directory: File) {
        if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) return
        directory.listFiles().orEmpty().filter { fileName.matches(it.name) }.forEach {
            writeControl(it, 20, 1, int = true)
            runCatching { Files.deleteIfExists(it.toPath()) }
        }
        directory.listFiles().orEmpty().filter { Regex("cadence-(0[1-9]|1[0-6])\\.bin").matches(it.name) }.forEach {
            runCatching { Files.deleteIfExists(it.toPath()) }
        }
        runCatching { Files.deleteIfExists(directory.toPath()) }
    }

    /** Invoked before stop / after continue, so crossing host pauses is omitted. */
    @JvmStatic @Synchronized fun activity(paused: Boolean) {
        if (paused) cadence?.revoke()
        val current = session ?: return
        if ((current.epoch % 2 == 1L) == paused) return
        current.epoch++
        current.directory.listFiles().orEmpty().filter { fileName.matches(it.name) }.forEach {
            writeControl(it, 64, current.epoch)
        }
        current.previous = emptyMap(); current.lastPoll = 0
    }

    data class Snapshot(val files: Map<String, ByteArray>, val summary: String, val history: String,
        val faults: String = "VULKAN_FAULT_CAPTURE_V1 state=NOT_CONFIGURED\n")

    /** Explicit manual capture only. Never unfreeze the first fault or reset the per-process quota. */
    @Synchronized fun armFaultWindow(id: String, now: Long = System.nanoTime()) {
        val current = session?.takeIf { it.id == id && it.epoch % 2 == 0L } ?: return
        if (now <= 0 || now > Long.MAX_VALUE - VulkanFaultSnapshot.WINDOW_NS) return
        current.directory.listFiles().orEmpty().filter { fileName.matches(it.name) }.forEach { file -> runCatching {
            FileChannel.open(file.toPath(), READ, WRITE, NOFOLLOW_LINKS).use { channel ->
                check(channel.size() == BYTES.toLong())
                val b = ByteBuffer.allocate(256).order(LITTLE_ENDIAN)
                while (b.hasRemaining()) check(channel.read(b) > 0)
                check(GraphicsTraceSnapshot.validHeader(b.array(), BYTES))
                val at = VulkanFaultSnapshot.OFFSET
                if (b.getInt(20) != 0 || b.getLong(64) != current.epoch || b.getInt(at) != 1 ||
                    b.getInt(at + 4) != VulkanFaultSnapshot.CAPACITY || b.getInt(at + 40) != 0 ||
                    b.getLong(at + 16) !in 0 until VulkanFaultSnapshot.MAX_CALLS ||
                    b.getLong(at + 48) != b.getLong(32) || b.getLong(32) > now) return@use
                val deadline = maxOf(b.getLong(at + 8), now + 15_000_000_000L)
                if (deadline > now + VulkanFaultSnapshot.WINDOW_NS) return@use
                // Read, validate and write the SAME open inode, including across a path replacement.
                val control = ByteBuffer.allocate(8).order(LITTLE_ENDIAN).putLong(deadline)
                control.flip()
                while (control.hasRemaining()) check(channel.write(control, at + 8L + control.position()) > 0)
            }
        } }
    }
    private fun read(file: File): GraphicsTraceSnapshot.Result = FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
        check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && channel.size() == BYTES.toLong())
        fun bytes(): ByteArray {
            channel.position(0)
            val data = ByteBuffer.allocate(BYTES)
            while (data.hasRemaining()) check(channel.read(data) > 0)
            check(channel.size() == BYTES.toLong()); return data.array()
        }
        GraphicsTraceSnapshot.filter(bytes(), bytes())
    }

    private fun capture(current: Session): Map<String, GraphicsTraceSnapshot.Result> {
        val results = linkedMapOf<String, GraphicsTraceSnapshot.Result>()
        current.unreadable = 0
        for (slot in 1..16) {
            val name = "graphics-${slot.toString().padStart(2, '0')}.bin"
            val file = File(current.directory, name)
            if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) continue
            runCatching { read(file) }.onSuccess { results[name] = it }.onFailure { current.unreadable++ }
        }
        val access = ProcGameExecutionAccess()
        val processes = runCatching { access.session()?.processes.orEmpty() }.getOrDefault(emptyList())
        for (result in results.values) {
            val key = result.pid to result.startTicks
            if (current.ownership[key] == "TARGET_EXE") continue
            val process = processes.firstOrNull { it.pid == result.pid && it.startTicks == result.startTicks } ?: continue
            val command = runCatching { access.command(process) }.getOrNull() ?: continue
            current.ownership[key] = if (GameExecutionSampler.matchesExecutable(current.executable, command)) "TARGET_EXE" else "SESSION_OTHER"
        }
        return results
    }

    /** Existing ~10 s memory worker only; no additional sampling thread. */
    @Synchronized fun poll(id: String) {
        val current = session?.takeIf { it.id == id && it.epoch % 2 == 0L } ?: return
        val now = SystemClock.elapsedRealtime()
        val results = capture(current)
        val interval = now - current.lastPoll
        val text = buildString {
            appendLine("GRAPHICS_INTERVAL_V1 elapsed_ms=$now; interval_ms=${if (current.lastPoll == 0L) "?" else interval}; epoch=${current.epoch}; unreadable_slots=${current.unreadable}")
            if (results.isEmpty()) appendLine("state=NO_NATIVE_DATA; unsupported_api_or_not_loaded=UNCONFIRMED")
            results.forEach { (name, result) ->
                val previous = current.previous[name]
                val same = previous?.takeIf { it.pid == result.pid && it.startTicks == result.startTicks && it.activity == result.activity }
                val owner = current.ownership[result.pid to result.startTicks] ?: "UNCONFIRMED"
                appendLine(result.report(owner, same, interval.takeIf { same != null && current.lastPoll != 0L }))
            }
        }.let { if (it.length > 28000) it.take(28000) + "\ninterval=TRUNCATED; full_rows=BINARY\n" else it }
        current.previous = results; current.lastPoll = now
        current.history.addLast(text)
        while (current.history.size > 12 || current.history.sumOf(String::length) > 58000) current.history.removeFirst()
    }

    @Synchronized fun snapshot(id: String, phase: String): Snapshot {
        require(phase == "before" || phase == "after")
        val current = session?.takeIf { it.id == id } ?: return Snapshot(emptyMap(), "GRAPHICS_TRACE_V1 state=NOT_CONFIGURED\n", "")
        val results = capture(current)
        val files = results.mapKeys { (name, _) -> name.replace("graphics-", "graphics-$phase-") }.mapValues { it.value.bytes }
        val summary = buildString {
            appendLine("GRAPHICS_TRACE_V1 phase=$phase; scope=ALL_GAMES; snapshot=DOUBLE_READ_FILTERED; atomic_snapshot=NO; unreadable_slots=${current.unreadable}")
            appendLine("Metrics are inclusive host call times; CPU uses thread clock. GPU execution and actual game FPS are NOT measured. Nested/thread totals must not be summed into frame time. Unknown/uncaptured is not zero. Host pause crossings are omitted; native PC sampling can still perturb timing.")
            appendLine("MESA_COPY_TO_SHM measures first rectangle copy through XShmPutImage entry, including row-loop/unmap work. MESA_COPY_TO_SWAP_END has no observed SHM boundary. Stage counts are not copy requests or FPS; copy_calls/bytes count observed Mesa copy requests, not internal helper chunks. Legacy MESA_RECT_COPY measures requests outside an observed Swap.")
            appendLine("Parallel-copy stage CPU time covers the caller only; helper CPU belongs to total game process/thread samples. Compare wall time and actual FPS, not reduced caller CPU alone. Copy counters are lifetime observations and are not a GPU zero-copy claim.")
            if (results.isEmpty()) appendLine("state=NO_NATIVE_DATA; hook_installation_or_api_coverage=UNCONFIRMED")
            results.forEach { (_, result) -> appendLine(result.report(current.ownership[result.pid to result.startTicks] ?: "UNCONFIRMED")) }
        }
        return Snapshot(files, summary.take(62000) + if (summary.length > 62000) "\nsummary=TRUNCATED; full_rows=BINARY\n" else "",
            current.history.joinToString("\n").take(60000), faultReport(current, results))
    }

    /** Startup export shares the launch identity, never the next capture's game or PID. */
    @Synchronized fun faultReport(id: String): String {
        val current = session?.takeIf { it.id == id } ?: return "VULKAN_FAULT_CAPTURE_V1 state=NOT_CONFIGURED\n"
        return faultReport(current, capture(current))
    }

    private fun faultReport(current: Session, results: Map<String, GraphicsTraceSnapshot.Result>): String = buildString {
        appendLine("VULKAN_FAULT_CAPTURE_V1 state=${if (results.isEmpty()) "NO_NATIVE_DATA" else "OBSERVED"}; unreadable_slots=${current.unreadable}")
        appendLine("source=NATIVE_FUNCTION_RETURN; scope=PER_PROCESS_OBSERVED_WRAPPERS; clock=HOST_MONOTONIC_NS; first=FIRST_OBSERVED; gpu_execution_order=UNKNOWN; atomic_snapshot=NO")
        appendLine("Auto window is 120 s from the first observed graphics call. Manual capture can extend 15 s; call limit=131072 per process, ring=24. Window gaps and unobserved APIs remain unknown. Frozen history may precede this capture; epoch and timestamps identify it. Text shows last 8 stable returns; ordinary graphics binaries retain up to 24.")
        appendLine("Device/queue IDs are local opaque associations, not raw handles. Commands/waits/signals are counts, not resource contents or dependency proof. Driver faults require EXT_device_fault already enabled by the game; not enabled does not mean unsupported. Driver query results are separate from the original VkResult; 2147483647 means not queried. Driver strings/addresses/vendor binaries are not exported.")
        val now = System.nanoTime()
        results.forEach { (name, result) ->
            appendLine("PROCESS slot=${name.removePrefix("graphics-").removeSuffix(".bin")}; pid=${result.pid}; owner=${current.ownership[result.pid to result.startTicks] ?: "UNCONFIRMED"}; coverage=0x${result.coverage.toString(16)}; delegate_conflicts=${result.conflicts}")
            append(result.faults.report(now))
            append(result.commands.report())
        }
    }.let { if (it.length <= 64000) it else it.take(63900) + "\ntext=TRUNCATED; inspect_filtered_graphics_binary=YES\n" }
}

/** Fixed ABI with bounded parsing and torn-row rejection; no raw pointers or pixel contents. */
object GraphicsTraceSnapshot {
    val metrics = listOf("GL_SWAP", "GL_FINISH", "GL_READ_PIXELS", "X_GEOMETRY", "X_SHM_SUBMIT", "X_SYNC",
        "MESA_RECT_COPY", "MESA_FUTEX_WAIT", "VK_SUBMIT", "VK_PRESENT", "VK_FENCE_WAIT", "VK_SEMAPHORE_WAIT",
        "VK_QUEUE_IDLE", "VK_DEVICE_IDLE", "GL_MAP_BUFFER", "VK_MAP_MEMORY", "MESA_COPY_TO_SHM", "MESA_COPY_TO_SWAP_END",
        "VK_CREATE_DEVICE", "VK_ACQUIRE_IMAGE")
    data class Row(val key: Long, val count: Long, val wall: Long, val cpu: Long, val maxWall: Long, val maxCpu: Long,
        val bytes: Long, val cpuCount: Long, val first: Long, val last: Long, val gaps: Long, val maxGap: Long, val errors: Long,
        val copyCalls: Long = 0) {
        val metric get() = (key and 0xffffffff).toInt() - 1
        val tid get() = key ushr 32
    }
    data class Result(val bytes: ByteArray, val pid: Int, val startTicks: Long, val activity: Long,
        val coverage: Long, val conflicts: Long, val dropped: Long, val omitted: Int, val rows: List<Row>,
        val faults: VulkanFaultSnapshot.Result, val commands: VulkanCommandSnapshot.Result) {
        fun report(owner: String, previous: Result? = null, intervalMs: Long? = null): String = buildString {
            append("GRAPHICS_PROCESS_V1 pid=$pid; owner=$owner; coverage=0x${coverage.toString(16)}; conflicts=$conflicts; dropped=$dropped; unstable_rows=$omitted; ")
            appendLine("scope=${if (intervalMs != null) "INTERVAL" else "LIFETIME"}; epoch=$activity")
            val header = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
            val copyVersion = header.getInt(72)
            val helpers = header.getInt(76)
            val parallel = header.getLong(80)
            val copied = header.getLong(88)
            val fallback = header.getLong(96)
            if (copyVersion in 1..2 && helpers in 0..2 && parallel >= 0 && copied >= 0 && fallback >= 0) {
                appendLine("MESA_FRAME_COPY_V1 scope=LIFETIME; parallel_calls=$parallel; parallel_bytes=$copied; " +
                    "workers=$helpers; serial_large_copies=$fallback; counters_atomic_snapshot=NO; caller_cpu_excludes_helpers=YES")
                if (copyVersion == 2) {
                    val chunks = header.getLong(104)
                    val callerBytes = header.getLong(112)
                    val helperBytes = header.getLong(120)
                    if (chunks >= 0 && callerBytes >= 0 && helperBytes >= 0) appendLine(
                        "MESA_FRAME_COPY_SCHEDULE_V2 schedule=DYNAMIC_256K; scope=LIFETIME; chunks=$chunks; " +
                            "caller_bytes=$callerBytes; helper_bytes=$helperBytes; counters_atomic_snapshot=NO")
                }
            }
            if (header.getInt(128) == 1) {
                val hits = header.getLong(136)
                val queries = header.getLong(144)
                if (hits >= 0 && queries >= 0) appendLine(
                    "MESA_FRAME_GEOMETRY_V1 scope=LIFETIME; coalesced=$hits; fresh_queries=$queries; " +
                        "cache_scope=SINGLE_SWAP; counters_atomic_snapshot=NO")
            }
            val regionsVersion = header.getInt(152)
            if (regionsVersion in 1..2) {
                val calls = header.getLong(160)
                val inputs = header.getLong(168)
                val compared = header.getLong(176)
                val written = header.getLong(184)
                if (calls >= 0 && inputs >= 0 && compared >= 0 && written >= 0) appendLine(
                    "MESA_COPY_REGIONS_V1 scope=LIFETIME; calls=$calls; input_bytes=$inputs; " +
                        "comparison_requested_bytes=$compared; written_bytes=$written; " +
                        "counters_atomic_snapshot=NO; gpu_readback=UNCHANGED; " +
                        "policy=${if (regionsVersion == 2) "FUSED_64B" else "BLOCK_4K"}")
            }
            val old = previous?.rows?.associateBy(Row::key).orEmpty()
            for ((metric, group) in rows.groupBy(Row::metric).toSortedMap()) {
                val values = if (intervalMs == null) group else group.mapNotNull { row ->
                    old[row.key]?.takeIf { row.count >= it.count && row.wall >= it.wall && row.cpu >= it.cpu && row.bytes >= it.bytes && row.cpuCount >= it.cpuCount && row.copyCalls >= it.copyCalls }?.let {
                        row.copy(count = row.count-it.count, wall=row.wall-it.wall, cpu=row.cpu-it.cpu, bytes=row.bytes-it.bytes, cpuCount=row.cpuCount-it.cpuCount, copyCalls=row.copyCalls-it.copyCalls)
                    }
                }
                val count = values.sumOf(Row::count)
                if (count == 0L) continue
                fun ms(value: Long) = String.format(Locale.ROOT, "%.3f", value / 1_000_000.0)
                val cpuCount = values.sumOf(Row::cpuCount)
                append("${metrics[metric]} calls=$count; wall_ms=${ms(values.sumOf(Row::wall))}; cpu_ms=${if (cpuCount == count) ms(values.sumOf(Row::cpu)) else "?"}; ")
                append("avg_wall_ms=${ms(values.sumOf(Row::wall) / count)}; lifetime_max_ms=${ms(group.maxOf(Row::maxWall))}; bytes=${values.sumOf(Row::bytes)}")
                if (metric in 16..17) append("; copy_calls=${values.sumOf(Row::copyCalls)}; timing=INCLUSIVE_STAGE")
                if (intervalMs != null && intervalMs > 0) append("; calls_per_s=${String.format(Locale.ROOT,"%.2f",count*1000.0/intervalMs)}")
                appendLine("; threads=${values.size}; coverage=${if (intervalMs != null && values.size < group.size) "PARTIAL_NEW_THREADS" else "OBSERVED_ROWS"}")
            }
            if (rows.isEmpty()) appendLine("state=NO_COMPLETED_CALLS; unmeasured_is_not_zero=YES")
        }
    }
    fun validHeader(bytes: ByteArray, length: Int = bytes.size): Boolean {
        if (length != NativeGraphicsTrace.BYTES || bytes.size < 80) return false
        val b = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
        return bytes.copyOfRange(0,8).contentEquals("AURAGX1\u0000".toByteArray()) && b.getInt(8) == 1 && b.getInt(12) == 128 &&
            b.getInt(16) > 1 && b.getInt(20) in 0..1 && b.getLong(24) > 0 && b.getLong(32) > 0 &&
            b.getLong(48) >= 0 && b.getLong(56) >= 0 && b.getLong(64) >= 0
    }
    fun filter(first: ByteArray, second: ByteArray): Result {
        require(validHeader(first) && validHeader(second))
        val a=ByteBuffer.wrap(first).order(LITTLE_ENDIAN); val b=ByteBuffer.wrap(second).order(LITTLE_ENDIAN)
        require(a.getInt(16)==b.getInt(16) && a.getLong(24)==b.getLong(24) && a.getLong(32)==b.getLong(32))
        val output=second.copyOf(); val rows=mutableListOf<Row>(); var omitted=0
        for (index in 0 until 128) {
            val at=4096+index*192; val seq=b.getLong(at); val key=b.getLong(at+8)
            val v=LongArray(12) { b.getLong(at+16+it*8) }
            val copies=b.getLong(at+144)
            val valid = a.getLong(64)==b.getLong(64) && seq>0 && seq%2==0L && key>0 && key ushr 32 in 1..Int.MAX_VALUE.toLong() &&
                (key and 0xffffffff) in 1..metrics.size.toLong() && v.all { it in 0..Long.MAX_VALUE/128 } && v[0]>0 && v[0]<=1_000_000_000 &&
                seq==v[0]*2 && v[3]<=v[1] && v[4]<=v[2] && v[6]<=v[0] && v[7]>0 && v[8]>=v[7] &&
                copies in 0..Long.MAX_VALUE/128 && ((key and 0xffffffff) !in 17..18 || copies >= v[0]) &&
                (0 until 8).all { b.getInt(at+112+it*4)>=0 } && (0 until 8).sumOf { b.getInt(at+112+it*4).toLong() } == v[0] &&
                (at until at+192).all { first[it]==second[it] }
            if (valid) rows += Row(key,v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9],v[10],v[11],copies)
            else { if (seq!=0L || a.getLong(at)!=0L) omitted++; output.fill(0,at,at+192) }
        }
        val faults=VulkanFaultSnapshot.filter(first,second,output)
        return Result(output,b.getInt(16),b.getLong(24),b.getLong(64),b.getLong(40),b.getLong(48),b.getLong(56),omitted,rows,faults,VulkanCommandSnapshot.filter(first,second,output,faults))
    }
}
