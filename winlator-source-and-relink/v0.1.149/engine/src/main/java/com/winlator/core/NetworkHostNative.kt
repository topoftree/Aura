/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.os.SystemClock
import androidx.annotation.Keep

/** Host-process cross-control. Blocking libc calls run on the capture's single guarded worker. */
object NetworkHostNative {
    interface Resolver {
        /** errno/relation pairs for process network and DNS; no network handles are returned. */
        fun bindings(network: Long): IntArray
        /** EAI, observed errno, IPv4 count, IPv6 count, address-list limit. */
        fun lookup(network: Long, target: Int, explicitNetwork: Boolean): IntArray
    }

    fun collect(networkHandle: Long?, live: () -> Boolean, emit: (String) -> Unit,
                resolver: Resolver = NetworkHostNativeJni, now: () -> Long = SystemClock::elapsedRealtime) {
        val started = now()
        val network = networkHandle ?: 0L
        fun event(target: String, stage: String, code: Int = 0, value: Int = 0, family: Int = 0) {
            if (live()) emit("NET_HOST_NATIVE_V1 target=$target stage=$stage code=$code value=$value " +
                "family=$family ms=${(now() - started).coerceAtLeast(0)}")
        }
        try {
            for ((index, target) in listOf("STORE_HTTP", "DIRECTORY_HTTPS").withIndex()) {
                if (!live()) return
                event(target, "ACTIVE_NETWORK", value = if (network == 0L) 0 else 1)
                val binding = resolver.bindings(network)
                check(binding.size == 4)
                if (!live()) return
                event(target, "PROCESS_NETWORK", binding[0], binding[1])
                event(target, "PROCESS_DNS", binding[2], binding[3])
                for (explicit in listOf(false, true)) {
                    if (!live()) return
                    if (explicit && network == 0L) { event(target, "ACTIVE_UNAVAILABLE"); continue }
                    val prefix = if (explicit) "ACTIVE" else "DEFAULT"
                    event(target, "${prefix}_BEGIN")
                    val answer = resolver.lookup(network, index, explicit)
                    // A blocked libc call may finish after cancellation or the deadline.
                    // Discard it and stop before issuing another query. Never restart workers
                    // until this one exits; there is no safe cancellation API for getaddrinfo.
                    if (!live()) return
                    check(answer.size == 5)
                    event(target, "${prefix}_DNS", answer[0], answer[1])
                    event(target, "${prefix}_V4", value = answer[2], family = 2)
                    event(target, "${prefix}_V6", value = answer[3], family = 23)
                    if (answer[4] != 0) event(target, "ADDRESS_LIMIT", value = 64)
                }
                event(target, "TARGET_DONE")
            }
            event("RUNTIME", "DONE")
        } catch (_: Exception) { event("RUNTIME", "UNAVAILABLE") }
          catch (_: LinkageError) { event("RUNTIME", "UNAVAILABLE") }
    }
}

/** Separate from the helper's LD_PRELOAD library: this has no getaddrinfo override. */
@Keep
private object NetworkHostNativeJni : NetworkHostNative.Resolver {
    private val loaded by lazy { System.loadLibrary("aura_network_host"); true }
    private external fun readBindings(network: Long): IntArray
    private external fun query(network: Long, target: Int, explicitNetwork: Boolean): IntArray
    override fun bindings(network: Long): IntArray { check(loaded); return readBindings(network) }
    override fun lookup(network: Long, target: Int, explicitNetwork: Boolean): IntArray {
        check(loaded)
        return query(network, target, explicitNetwork)
    }
}
