/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN

/** Sanitizes the optional submitted-command block; handles never enter this ABI. */
object VulkanCommandSnapshot {
    const val OFFSET = 2880
    const val SIZE = 1216
    private val fields = listOf("device", "queue", "family", "index", "buffers", "known_buffers", "flags",
        "operations", "draws", "dispatches", "copies", "barriers", "clears", "rendering", "secondary",
        "image_operands", "buffer_operands", "ownership_requests", "first_src_layout", "first_dst_layout", "last_op", "bind_point")
    private val reasonNames = listOf("contention", "missing_buffer", "invalidated_generation", "incomplete_recording", "capacity_eviction", "lifecycle_invalidation")
    private val unknownNames = listOf("MISSING", "GENERATION", "INCOMPLETE", "SUMMARY_EVICTED", "ANCESTRY_UNKNOWN", "LIFECYCLE", "CONTENTION", "INACTIVE")
    data class Event(val call: Long, val begin: Long, val end: Long, val epoch: Long, val values: List<Long>) {
        fun report(label: String, schema: Int) = "$label call=$call; begin_ns=$begin; end_ns=$end; epoch=$epoch; " +
            fields.zip(values).joinToString("; ") { (key, value) -> "$key=${if (key in listOf("family", "index", "first_src_layout", "first_dst_layout", "bind_point") && value == 0xffffffffL) "?" else value}" } +
            "; unknown_reasons=" + if (schema < 3) "NOT_RECORDED" else unknownNames.filterIndexed { index, _ ->
                values[6] and (1L shl (index + 8)) != 0L }.joinToString("|").ifEmpty { "NONE" }
    }
    data class Result(val state: String, val observed: Long = 0, val dropped: Long = 0, val submitted: Long = 0,
        val omitted: Int = 0, val lost: Event? = null, val events: List<Event> = emptyList(),
        val schema: Int = 0, val reasons: List<Long>? = null) {
        fun report() = buildString {
            appendLine("VULKAN_COMMANDS_V4 state=$state; native_schema=$schema; observed_calls=$observed; dropped=$dropped; submissions=$submitted; unstable_records=$omitted; recent=${events.size}")
            appendLine("COMMAND_SCOPE classification=OBSERVED_SUBSET; complete_dependencies=NO; shader_resource_identity=UNMEASURED; secondary_contents=${if (schema >= 4) "VALIDATED_DIRECT_SUBSET_WHEN_FLAG_32" else "UNKNOWN"}; cap=2000000; private_buffers=${if (schema >= 3) 128 else 64}; private_ancestry=${if (schema >= 3) 512 else 0}; " +
                "flags=1_UNKNOWN,2_TRUNCATED,4_SECONDARY_PARTIAL_OR_UNOBSERVED,8_NEGATIVE,16_DEVICE_LOST,32_CHILD_COUNTERS_INCLUDED; family_index=ACTUAL_IF_OBSERVED; " +
                "op=1_DRAW,2_DISPATCH,3_COPY,4_BARRIER,5_CLEAR,6_RENDERING,7_SECONDARY,8_BIND; layout=VULKAN_ENUM; gpu_trigger=UNPROVEN; " +
                "flags_15_8=V3_UNKNOWN_REASON_UNION_NOT_COUNTS; ancestry_is_private=YES; first_layout_and_last_op=PRIMARY_ONLY; secondary_count=EXECUTE_CALLS; child_references_per_parent=64")
            appendLine("COMMAND_REASONS scope=OBSERVATIONS_NOT_UNIQUE_DROPS; counters=INDEPENDENT_NOT_TRANSACTIONAL; " +
                (reasons?.let { reasonNames.zip(it).joinToString("; ") { (name, value) -> "$name=$value" } } ?: "state=NOT_RECORDED"))
            appendLine(lost?.report("FIRST_LOST_SUBMIT", schema) ?: "FIRST_LOST_SUBMIT state=NOT_RECORDED")
            events.forEach { appendLine(it.report("RECENT_SUBMIT_COMMANDS", schema)) }
        }
    }
    fun filter(first: ByteArray, second: ByteArray, output: ByteArray, fault: VulkanFaultSnapshot.Result): Result {
        require(first.size >= OFFSET + SIZE && second.size >= OFFSET + SIZE && output.size >= OFFSET + SIZE)
        output.fill(0, OFFSET, OFFSET + SIZE)
        val b = ByteBuffer.wrap(second).order(LITTLE_ENDIAN)
        fun int(at: Int) = b.getInt(OFFSET + at)
        fun long(at: Int) = b.getLong(OFFSET + at)
        fun stable(at: Int, size: Int) = (OFFSET + at until OFFSET + at + size).all { first[it] == second[it] }
        fun keep(at: Int, size: Int) = second.copyInto(output, OFFSET + at, OFFSET + at, OFFSET + at + size)
        if (int(0) == 0 && stable(0, 4)) return Result("NOT_RECORDED")
        if (fault.state != "RECORDED" || int(0) !in 1..4 || int(4) != 8 || !stable(0, 8) ||
            long(8) !in 0..2_000_128 || long(16) !in 0..1_000_000_000L ||
            long(24) !in 0..fault.entries || long(32) !in 0..long(24) ||
            if (int(0) == 1) (40 until 64 step 8).any { long(it) != 0L }
            else (40 until 64 step 4).any { (int(it).toLong() and 0xffffffffL) > 1_000_000_000L }) return Result("INVALID_OR_UNSTABLE_HEADER")
        keep(0, 64)
        var omitted = 0
        fun event(at: Int, once: Boolean = false): Event? {
            if (long(at) == 0L && stable(at, 8)) return null
            fun reject(): Event? { omitted++; return null }
            val seq = long(at)
            if (seq <= 0 || seq % 2 != 0L || once && seq != 2L || !stable(at, 128)) return reject()
            val e = Event(long(at + 8), long(at + 16), long(at + 24), long(at + 32),
                (0 until 22).map { int(at + 40 + it * 4).toLong() and 0xffffffffL })
            val v = e.values
            if (e.call !in 1..fault.entries || e.begin !in fault.started..fault.deadline || e.end !in e.begin..fault.deadline ||
                e.epoch < 0 || e.epoch % 2 != 0L || e.epoch > b.getLong(64) ||
                v[0] > 65535 || v[1] > 65535 || v[4] > 256 || v[5] > v[4] ||
                v[6] and (if (int(0) >= 4) 0xff3fL else if (int(0) >= 3) 0xff1fL else 31L).inv() != 0L ||
                int(0) >= 3 && v[6] and 0xff00L != 0L && v[6] and 1L == 0L ||
                (int(0) < 4 && v[7] > 512_000_000) || (8..14).any { v[it] > v[7] } || v[20] > 8 ||
                !once && (e.call - 1) % 8 != (at - 192) / 128L ||
                once && (fault.firstLost?.call != e.call || v[6] and 24 != 24L)) return reject()
            keep(at, 128); return e
        }
        val lost = event(64, true)
        val events = (0 until 8).mapNotNull { event(192 + it * 128) }.sortedBy(Event::call)
        return Result("RECORDED", long(8), long(16), long(24), omitted, lost, events, int(0),
            if (int(0) >= 2) (40 until 64 step 4).map { int(it).toLong() and 0xffffffffL } else null)
    }
}
