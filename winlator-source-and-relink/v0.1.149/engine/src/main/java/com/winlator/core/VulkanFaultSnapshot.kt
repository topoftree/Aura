/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN

/** Optional block in the existing graphics ABI. Only stable, bounded metadata is exported. */
object VulkanFaultSnapshot {
    const val OFFSET = 192
    const val SIZE = 2688
    const val CAPACITY = 24
    const val MAX_CALLS = 131072L
    const val WINDOW_NS = 120_000_000_000L
    val apis = listOf("UNKNOWN", "vkQueueSubmit", "vkQueueSubmit2", "vkQueueSubmit2KHR", "vkQueuePresentKHR",
        "vkWaitForFences", "vkWaitSemaphores", "vkWaitSemaphoresKHR", "vkQueueWaitIdle", "vkDeviceWaitIdle",
        "vkMapMemory", "vkCreateDevice", "vkAcquireNextImageKHR", "vkAcquireNextImage2KHR")
    private val driverStates = listOf("NOT_OBSERVED", "DEVICE_UNTRACKED", "FEATURE_NOT_ENABLED",
        "FEATURE_ENABLE_UNKNOWN", "RESOLVER_UNOBSERVED", "ENTRY_UNAVAILABLE", "COUNTS_QUERY_FAILED",
        "METADATA_COMPLETE", "METADATA_PARTIAL", "PENDING")
    data class Event(val call: Long, val returned: Long, val begin: Long, val end: Long, val epoch: Long,
        val tid: Int, val api: Int, val device: Int, val queue: Int, val result: Int,
        val flags: Int, val objects: Long, val commands: Long, val waits: Long, val signals: Long,
        val image: Long, val timeout: Int) {
        fun report(label: String) = "$label api=${apis[api]}; result=$result; result_name=${resultName(result)}; " +
            "call=$call; return=$returned; begin_ns=$begin; end_ns=$end; epoch=$epoch; tid=$tid; " +
            "device=$device; queue=$queue; flags=$flags; objects=$objects; commands=$commands; " +
            "waits=$waits; signals=$signals; image=${if (image == 0xffffffffL) "?" else image}; " +
            "timeout=${listOf("NA", "ZERO", "FINITE", "INFINITE")[timeout]}"
    }
    data class Result(val state: String, val started: Long = 0, val deadline: Long = 0,
        val entries: Long = 0, val returns: Long = 0, val dropped: Long = 0, val frozen: Boolean = false,
        val firstError: Event? = null, val firstLost: Event? = null, val events: List<Event> = emptyList(),
        val omitted: Int = 0, val driver: String = "state=NOT_OBSERVED") {
        fun report(now: Long): String = buildString {
            val window = when {
                state != "RECORDED" -> state
                frozen -> "FROZEN_AFTER_DEVICE_LOST"
                entries >= MAX_CALLS -> "CALL_LIMIT"
                now > deadline -> "WINDOW_EXPIRED"
                else -> "ARMED"
            }
            appendLine("VULKAN_NATIVE_RETURNS_V1 state=$state; window=$window; start_ns=$started; deadline_ns=$deadline; " +
                "entries=$entries; returns=$returns; dropped=$dropped; unstable_records=$omitted; " +
                "recent_retained=${events.size}; recent_text_limit=8")
            appendLine(firstError?.report("FIRST_ERROR") ?: "FIRST_ERROR state=NOT_OBSERVED")
            appendLine(firstLost?.report("FIRST_DEVICE_LOST") ?: "FIRST_DEVICE_LOST state=NOT_OBSERVED")
            appendLine("DRIVER_FAULT $driver")
            events.takeLast(8).forEach { appendLine(it.report("RECENT_RETURN")) }
        }
    }

    fun resultName(value: Int) = when (value) {
        0 -> "VK_SUCCESS"; 1 -> "VK_NOT_READY"; 2 -> "VK_TIMEOUT"; 5 -> "VK_INCOMPLETE"
        -1 -> "VK_ERROR_OUT_OF_HOST_MEMORY"; -2 -> "VK_ERROR_OUT_OF_DEVICE_MEMORY"
        -4 -> "VK_ERROR_DEVICE_LOST"; -1000001004 -> "VK_ERROR_OUT_OF_DATE_KHR"
        1000001003 -> "VK_SUBOPTIMAL_KHR"; else -> "OTHER"
    }

    /** Writes a sanitized block into [output]; reserved, torn and invalid fields never survive. */
    fun filter(first: ByteArray, second: ByteArray, output: ByteArray): Result {
        require(first.size >= OFFSET + SIZE && second.size >= OFFSET + SIZE && output.size >= OFFSET + SIZE)
        output.fill(0, OFFSET, OFFSET + SIZE)
        val a = ByteBuffer.wrap(first).order(LITTLE_ENDIAN)
        val b = ByteBuffer.wrap(second).order(LITTLE_ENDIAN)
        fun int(at: Int) = b.getInt(OFFSET + at)
        fun long(at: Int) = b.getLong(OFFSET + at)
        fun stable(at: Int, length: Int) = (OFFSET + at until OFFSET + at + length).all { first[it] == second[it] }
        fun keep(at: Int, length: Int) = second.copyInto(output, OFFSET + at, OFFSET + at, OFFSET + at + length)
        if (int(0) == 0 && a.getInt(OFFSET) == 0) return Result("NOT_RECORDED")
        if (int(0) != 1 || int(4) != CAPACITY || !stable(0, 16) || !stable(48, 16) ||
            long(48) <= 0 || long(48) != b.getLong(32) || long(8) < long(48) ||
            long(16) !in 0..MAX_CALLS + 128 || long(24) !in 0..minOf(long(16), MAX_CALLS) ||
            long(32) !in 0..long(24) || int(40) !in 0..1 || int(44) != 0 || long(56) != 0L)
            return Result("INVALID_OR_UNSTABLE_HEADER")
        keep(0, 64)
        var omitted = 0
        fun event(at: Int, once: Boolean = false): Event? {
            val seq = long(at)
            if (seq == 0L && a.getLong(OFFSET + at) == 0L) return null
            fun reject(): Event? { omitted++; return null }
            if (seq <= 0 || seq % 2 != 0L || (once && seq != 2L) || !stable(at, 96)) return reject()
            val p = at + 8
            val e = Event(long(p), long(p + 8), long(p + 16), long(p + 24), long(p + 32),
                int(p + 40), int(p + 44), int(p + 48), int(p + 52), int(p + 56), int(p + 60),
                int(p + 64).toLong() and 0xffffffffL, int(p + 68).toLong() and 0xffffffffL,
                int(p + 72).toLong() and 0xffffffffL, int(p + 76).toLong() and 0xffffffffL,
                int(p + 80).toLong() and 0xffffffffL, int(p + 84))
            if (e.call !in 1..minOf(long(16), MAX_CALLS) || e.returned !in 1..long(24) ||
                e.begin !in long(48)..long(8) || e.end !in e.begin..long(8) ||
                e.epoch < 0 || e.epoch % 2 != 0L || e.epoch > b.getLong(64) ||
                e.tid <= 0 || e.api !in 1 until apis.size || e.device !in 0..65535 || e.queue !in 0..65535 ||
                e.flags !in 0..15 || e.timeout !in 0..3 ||
                (at == 64 && e.result >= 0) || (at == 160 && e.result != -4) ||
                (!once && (e.returned - 1) % CAPACITY != (at - 384) / 96L)) return reject()
            keep(at, 96)
            return e
        }
        val firstError = event(64, true)
        val firstLost = event(160, true)
        val events = (0 until CAPACITY).mapNotNull { event(384 + it * 96) }.sortedBy(Event::returned)
        val ds = long(256)
        val driver = when {
            ds == 0L -> "state=NOT_OBSERVED"
            ds == 1L -> "state=PENDING_OR_CANCELLED"
            ds != 2L || firstLost == null || !stable(256, 128) || int(264) !in 1..8 || int(276) !in 0..7 ||
                long(288) < 0 || int(296) !in 0..16 || int(300) !in 0..8 || int(304) !in 0..31 ||
                (0 until 8).any { int(308 + it * 4) !in 0..16 } ||
                (0 until 8).sumOf { int(308 + it * 4) } != int(296) ||
                (0 until 11).any { int(340 + it * 4) != 0 } -> {
                    omitted++; "state=INVALID_OR_UNSTABLE"
                }
            else -> {
                keep(256, 128)
                "state=${driverStates[int(264)]}; counts_result=${int(268)}; detail_result=${int(272)}; " +
                    "flags=${int(276)}; address_total=${int(280).toLong() and 0xffffffffL}; " +
                    "vendor_total=${int(284).toLong() and 0xffffffffL}; vendor_binary_bytes=${long(288)}; " +
                    "address_retained=${int(296)}; vendor_retained=${int(300)}; category_bits=${int(304)}; " +
                    "address_types=${(0 until 8).joinToString(",") { int(308 + it * 4).toString() }}"
            }
        }
        if (ds == 1L) ByteBuffer.wrap(output).order(LITTLE_ENDIAN).putLong(OFFSET + 256, 1)
        return Result("RECORDED", long(48), long(8), long(16), long(24), long(32), int(40) == 1,
            firstError, firstLost, events, omitted, driver)
    }
}
