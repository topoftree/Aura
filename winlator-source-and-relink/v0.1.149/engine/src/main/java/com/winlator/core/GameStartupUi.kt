/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Host presentation only. Stages describe work; they are not estimates of game loading progress. */
enum class GameStartupStage {
    PREPARING, UPDATING_FILES, PREPARING_WINE, PREPARING_GRAPHICS, PREPARING_AUDIO,
    STARTING_ENVIRONMENT, STARTING_GAME,
}

interface GameStartupUi {
    fun show(stage: GameStartupStage)
    fun close()
}

/** Window, worker and Activity callbacks race; closing is terminal for this session's UI. */
class GameStartupPresentation(private val uiExecutor: Executor, private val ui: GameStartupUi) {
    private val closed = AtomicBoolean(false)

    fun show(stage: GameStartupStage) {
        uiExecutor.execute { if (!closed.get()) ui.show(stage) }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) uiExecutor.execute { ui.close() }
    }
}
