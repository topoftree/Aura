/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.core

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Version probes do not create prefixes, connect Steam or launch game code. */
object RuntimeProcessProbe {
    @JvmStatic fun run(command: Array<String>, environment: EnvVars, root: File, timeoutMillis: Long): String {
        val output = GuestOutputSummary()
        val builder = ProcessBuilder(*command).directory(root).redirectErrorStream(true)
        for (key in environment) builder.environment()[key] = environment.get(key)
        val process = builder.start()
        val reader = Thread({
            try { GuestOutputSummary.drain(process.inputStream, output::accept) }
            catch (_: IOException) { /* A timeout or closed process pipe ends capture. */ }
        }, "aura-runtime-probe").apply { isDaemon = true; start() }
        try {
            val completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!completed) process.destroyForcibly()
            reader.join(500)
            return "status=${if (completed) process.exitValue() else "TIMEOUT"}; ${output.report()}"
        } catch (error: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw error
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
