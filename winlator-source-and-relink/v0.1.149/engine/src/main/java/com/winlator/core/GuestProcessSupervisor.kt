/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

data class GuestProcessInfo(
    val pid: Int, val parentPid: Int, val uid: Int, val startTicks: Long,
    val state: Char, val rssBytes: Long?,
) {
    val identity get() = pid to startTicks

    companion object {
        /** comm may contain spaces and parentheses. Fields after its last ')' have fixed offsets. */
        fun parse(stat: String, uid: Int, rssBytes: Long?): GuestProcessInfo? {
            val open = stat.indexOf('(')
            val close = stat.lastIndexOf(')')
            if (open < 1 || close <= open) return null
            val pid = stat.substring(0, open).trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
            val fields = stat.substring(close + 1).trim().split(Regex("\\s+"))
            if (fields.size < 20 || fields[0].length != 1) return null
            val parent = fields[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val start = fields[19].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            return GuestProcessInfo(pid, parent, uid, start, fields[0][0], rssBytes)
        }

        fun hasSession(environment: String, token: String): Boolean =
            token.isNotEmpty() && environment.substringBeforeLast('\u0000', "").splitToSequence('\u0000')
                .any { it == "AURA_GUEST_SESSION=$token" || it == "${LegacyDataNames.GUEST_SESSION_VARIABLE}=$token" }
    }
}

data class GuestProcessScan(val processes: List<GuestProcessInfo>, val unreadable: Int = 0)

interface GuestProcessAccess {
    fun scan(): GuestProcessScan
    fun read(pid: Int): GuestProcessInfo?
    fun matchesSession(process: GuestProcessInfo, token: String): Boolean
    fun sendSignal(pid: Int, signal: Int): Boolean
}

data class GuestMemorySample(val processes: Int, val measured: Int, val rssBytes: Long?, val unreadable: Int)
data class GuestSignalResult(val matched: Int, val signalled: Int, val unreadable: Int) {
    fun report(action: String) = "GUEST_EXECUTION_V1 action=$action; matched=$matched; signalled=$signalled; " +
        "unconfirmed=${matched - signalled}; unreadable=$unreadable"
}
data class GuestCleanupResult(val matched: Int, val killRequests: Int, val remaining: Int, val unreadable: Int,
                              val rootHandleRequested: Boolean = false) {
    fun report() = "GUEST_PROCESS_CLEANUP matched=$matched; killRequests=$killRequests; remaining=$remaining; " +
        "unreadable=$unreadable; rootHandleRequested=$rootHandleRequested"
}

/** Only an attached child, its descendants, or exact session-tag matches from our UID are eligible.
 * Identity checks also reject recycled PIDs; process names / relative PID values confer no ownership.
 */
class GuestProcessSupervisor(
    private val uid: Int, private val hostPid: Int, private val token: String, private val access: GuestProcessAccess,
) {
    private val known = mutableSetOf<Pair<Int, Long>>()

    @Synchronized
    fun attachRoot(pid: Int) {
        access.read(pid)?.takeIf { eligible(it) && it.parentPid == hostPid }?.let { known += it.identity }
    }

    @Synchronized
    fun snapshot(): GuestProcessScan {
        val scan = access.scan()
        val candidates = scan.processes.filter(::eligible)
        val selected = candidates.filter { it.identity in known || access.matchesSession(it, token) }.toMutableList()
        val selectedPids = selected.mapTo(mutableSetOf()) { it.pid }
        do {
            val children = candidates.filter { child -> child.pid !in selectedPids && selected.any {
                parent -> child.parentPid == parent.pid && child.startTicks >= parent.startTicks
            } }
            selected += children
            children.mapTo(selectedPids) { it.pid }
        } while (children.isNotEmpty())
        known.clear()
        selected.mapTo(known) { it.identity }
        return GuestProcessScan(selected, scan.unreadable)
    }

    @Synchronized
    fun memory(): GuestMemorySample = snapshot().let { scan ->
        val rss = scan.processes.mapNotNull { it.rssBytes?.takeIf { value -> value >= 0 } }
        GuestMemorySample(scan.processes.size, rss.size,
            if (rss.isEmpty() && scan.processes.isNotEmpty()) null else rss.sum(), scan.unreadable)
    }

    @Synchronized
    fun signalTracked(pid: Int, startTicks: Long, signal: Int): Boolean {
        if (pid to startTicks !in known) return false
        val current = access.read(pid) ?: return false
        return eligible(current) && current.startTicks == startTicks && access.sendSignal(pid, signal)
    }

    @Synchronized
    fun signalAll(signal: Int): GuestSignalResult {
        val matched = mutableSetOf<Pair<Int, Long>>()
        val signalled = mutableSetOf<Pair<Int, Long>>()
        var unreadable = 0
        // Once parents are stopped, sweep once more for children forked during discovery.
        // Reuse the same ownership/start-time checks as teardown; never stop the Android host.
        repeat(if (signal == STOP) 2 else 1) {
            val scan = snapshot()
            unreadable = maxOf(unreadable, scan.unreadable)
            scan.processes.forEach {
                matched += it.identity
                if (signalTracked(it.pid, it.startTicks, signal)) signalled += it.identity
            }
        }
        return GuestSignalResult(matched.size, signalled.size, unreadable)
    }

    @Synchronized
    fun terminate(): GuestCleanupResult {
        val matched = mutableSetOf<Pair<Int, Long>>()
        var requests = 0
        var unreadable = 0
        // Freeze this session before termination to reduce forks during teardown; sweep again for
        // children born during the first snapshot. Never signal a process group or other app.
        repeat(2) {
            val scan = snapshot()
            unreadable = maxOf(unreadable, scan.unreadable)
            scan.processes.mapTo(matched) { it.identity }
            scan.processes.forEach { signalTracked(it.pid, it.startTicks, STOP) }
            scan.processes.asReversed().forEach {
                if (signalTracked(it.pid, it.startTicks, KILL)) requests++
                else signalTracked(it.pid, it.startTicks, CONTINUE)
            }
        }
        val final = snapshot()
        return GuestCleanupResult(matched.size, requests, final.processes.size, maxOf(unreadable, final.unreadable))
    }

    private fun eligible(process: GuestProcessInfo) = process.pid > 1 && process.pid != hostPid &&
        process.uid == uid && process.state != 'Z' && process.state != 'X'

    companion object {
        // Linux signal numbers, shared by Android ARM64 and the deterministic test backend.
        const val KILL = 9
        const val STOP = 19
        const val CONTINUE = 18
    }
}
