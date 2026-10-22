/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.util.Locale

/**
 * Temporary, owned-window evidence. By default a mapped window, a process alone, or a static
 * splash is insufficient for first-install confirmation. Cover/client observers may use a
 * shorter policy. The caller must additionally verify a nonblank guest Surface.
 * No paths, frame buffers or diagnostics are retained here.
 */
class GameStartupEvidence @JvmOverloads constructor(executable: String, private val minimumDuration: Long = 10_000,
                          private val minimumFrames: Int = 30, aliases: Set<String> = emptySet()) {
    init { require(minimumDuration >= 0 && minimumFrames > 0) }
    private val selected = (aliases + executable).map {
        it.replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)
    }.filter { it.isNotBlank() }.toSet()
    private var targets = emptySet<Int>()
    private var processesAt = Long.MIN_VALUE
    private var active = false
    private var window = 0
    private var startedAt = 0L
    private var updatedAt = 0L
    private var frames = 0

    @Synchronized fun processes(rows: List<WineProcess>?, now: Long) {
        val ids = rows?.filter { it.name.replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT) in selected }
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
        if (!active || pid !in targets || !visible || now - processesAt !in 0..3_000) {
            if (window == id) reset()
            return
        }
        if (window != id || now - updatedAt !in 0..1_500) {
            reset()
            window = id
            startedAt = now
        }
        updatedAt = now
        frames = (frames + 1).coerceAtMost(minimumFrames)
    }

    @Synchronized fun candidate(now: Long): Long? = window.toLong().takeIf {
        active && it != 0L && targets.isNotEmpty() && now - processesAt in 0..3_000 &&
            now - updatedAt in 0..1_500 && now - startedAt >= minimumDuration && frames >= minimumFrames
    }

    private fun reset() { window = 0; startedAt = 0; updatedAt = 0; frames = 0 }
}
