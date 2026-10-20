/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.util.Locale

/**
 * Temporary first-launch evidence. A mapped window, a process alone, or a static splash is
 * insufficient. The caller must additionally verify a nonblank guest Surface before saving.
 * No paths, frame buffers or diagnostics are retained here.
 */
class GameStartupEvidence(executable: String) {
    private val selected = executable.replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)
    private var targets = emptySet<Int>()
    private var processesAt = Long.MIN_VALUE
    private var active = false
    private var window = 0
    private var startedAt = 0L
    private var updatedAt = 0L
    private var frames = 0

    @Synchronized fun processes(rows: List<WineProcess>?, now: Long) {
        val ids = rows?.filter { it.name.replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT) == selected }
            ?.map { it.pid }?.filter { it > 0 }?.toSet().orEmpty()
        if (ids != targets || ids.isEmpty()) reset()
        targets = ids
        processesAt = now
    }

    @Synchronized fun setActive(value: Boolean) {
        if (active != value) { reset(); targets = emptySet(); processesAt = Long.MIN_VALUE }
        active = value
    }

    @Synchronized fun removed(id: Int) { if (window == id) reset() }

    @Synchronized fun content(id: Int, pid: Int, visible: Boolean, now: Long) {
        if (!active || pid !in targets || !visible || now - processesAt !in 0..3_000) return
        if (window != id || now - updatedAt !in 0..1_500) {
            reset()
            window = id
            startedAt = now
        }
        updatedAt = now
        frames = (frames + 1).coerceAtMost(30)
    }

    @Synchronized fun candidate(now: Long): Long? = window.toLong().takeIf {
        active && it != 0L && targets.isNotEmpty() && now - processesAt in 0..3_000 &&
            now - updatedAt in 0..1_500 && now - startedAt >= 10_000 && frames >= 30
    }

    private fun reset() { window = 0; startedAt = 0; updatedAt = 0; frames = 0 }
}
