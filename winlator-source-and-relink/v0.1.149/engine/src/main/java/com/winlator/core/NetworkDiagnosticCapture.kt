/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.os.CancellationSignal
import android.os.SystemClock
import android.system.ErrnoException
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Explicit Debug captures only. No polling outside a capture; no modifications to Steam or DNS settings. */
object NetworkDiagnosticCapture {
    private data class Configuration(val id: String, val root: File, val environment: EnvVars)
    @Volatile private var configuration: Configuration? = null
    private val running = AtomicReference<Work?>()
    private var observer: AutoCloseable? = null

    fun configure(id: String, root: File, environment: EnvVars) {
        cancel()
        configuration = Configuration(id, root, EnvVars().also { it.putAll(environment); RuntimeDiagnostics.silence(it) })
        if (observer == null) observer = RuntimeDiagnostics.observe { if (!it) discard() }
    }
    fun cancel() { running.get()?.cancelled?.set(true) }
    fun discard() { configuration = null; cancel() }

    private class Work {
        val cancelled = AtomicBoolean(false)
        val resources = CopyOnWriteArrayList<AutoCloseable>()
        @Volatile var process: Process? = null
        fun release() {
            process?.let { if (it.isAlive) it.destroyForcibly() }
            resources.forEach { runCatching { it.close() } }
        }
    }

    /** Worker only. Streaming checkpoints survive capture byte limits and cancellation. */
    fun collect(context: Context, id: String, current: () -> Boolean, checkpoint: (String) -> Unit = {}): String {
        val report = NetworkProbeReport()
        val config = configuration?.takeIf { it.id == id }
            ?: return report.render("UNAVAILABLE")
        if (!RuntimeDiagnostics.enabled() || !current()) return report.render("CANCELLED")
        val work = Work()
        if (!running.compareAndSet(null, work)) return report.render("BUSY")
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 20_000
        // Both controls and the helper-local native resolver use this exact snapshot.
        val manager = runCatching { context.getSystemService(ConnectivityManager::class.java) }.getOrNull()
        val network = runCatching { manager?.activeNetwork }.getOrNull()
        fun live() = !work.cancelled.get() && RuntimeDiagnostics.enabled() && configuration === config &&
            SystemClock.elapsedRealtime() < deadline
        fun emit(line: String) {
            if (live() && report.accept(line)) runCatching { checkpoint(report.render("RUNNING")) }
        }
        val done = CountDownLatch(3)
        val workers = mutableListOf<Thread>()
        var state = "ERROR"
        try {
            checkpoint(report.render("RUNNING"))
            workers += Thread({
                try { host(network, work, started, ::live, ::emit) }
                catch (_: Exception) { emit("NET_HOST_V1 target=RUNTIME stage=UNAVAILABLE code=0 value=0 family=0 ms=0") }
                finally { done.countDown() }
            }, "aura-network-host").apply { isDaemon = true; start() }
            workers += Thread({
                try { NetworkHostNative.collect(network?.networkHandle, ::live, ::emit) }
                finally { done.countDown() }
            }, "aura-network-host-native").apply { isDaemon = true; start() }
            workers += Thread({
                var failure = "INSTALL_FAILED"
                var bytes = 0
                fun status(state: String, exit: Int? = null) {
                    report.helper(state, exit, bytes)
                    if (live()) runCatching { checkpoint(report.render("RUNNING")) }
                }
                try {
                    if (!live()) return@Thread
                    status("INSTALLING")
                    val helperBytes = context.assets.open("network-diagnostics/aura-network-probe.exe").use { it.readBytes() }
                    NetworkProbeRuntime.install(File(config.environment.get("WINEPREFIX")), helperBytes)
                    if (live()) {
                        failure = "START_FAILED"
                        status("STARTING")
                        val builder = ProcessBuilder(File(config.root, "opt/wine/bin/wine").path, NetworkProbeRuntime.GUEST_PATH)
                            .directory(config.root).redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
                        val probeEnvironment = runCatching { NetworkNativeProbe.environment(config.environment,
                            File(context.applicationInfo.nativeLibraryDir), network?.networkHandle) }.getOrElse {
                            emit("NET_NATIVE_V1 target=RUNTIME stage=UNAVAILABLE code=0 value=0 family=0 ms=0")
                            config.environment
                        }
                        for (key in probeEnvironment) builder.environment()[key] = probeEnvironment.get(key)
                        val process = builder.start()
                        work.process = process
                        if (!live()) process.destroyForcibly()
                        failure = "READ_FAILED"
                        status("RUNNING")
                        // Bound both line size and total input. Never save arbitrary guest stdout/stderr.
                        process.inputStream.use { input ->
                            val line = StringBuilder()
                            var oversized = false
                            while (live() && bytes < 64 * 1024) {
                                val ch = input.read()
                                if (ch < 0) break
                                bytes++
                                if (ch == 10) {
                                    if (!oversized) emit(line.toString().trimEnd('\r'))
                                    line.setLength(0); oversized = false
                                } else if (line.length < 320) line.append(ch.toChar()) else oversized = true
                            }
                            if (live() && bytes < 64 * 1024 && !oversized && line.isNotEmpty()) emit(line.toString().trimEnd('\r'))
                        }
                        if (bytes >= 64 * 1024) status("OUTPUT_LIMIT")
                        else {
                            while (live() && !process.waitFor(100, TimeUnit.MILLISECONDS)) { /* Bounded by the capture deadline. */ }
                            if (!process.isAlive) status("EXITED", process.exitValue())
                            else status(if (SystemClock.elapsedRealtime() >= deadline) "TIME_LIMIT" else "CANCELLED")
                        }
                    }
                } catch (_: Exception) {
                    status(if (live()) failure else "CANCELLED")
                    emit("NET_PROBE_V1 target=RUNTIME stage=UNAVAILABLE code=0 value=0 family=0 ms=0")
                }
                finally { work.process?.let { if (it.isAlive) it.destroyForcibly() }; done.countDown() }
            }, "aura-network-wine").apply { isDaemon = true; start() }
            while (live() && current() && !done.await(100, TimeUnit.MILLISECONDS)) { /* Cancellable deadline. */ }
            if (live() && current()) {
                val changed = runCatching { if (manager == null) 2 else if (manager.activeNetwork == network) 0 else 1 }.getOrDefault(2)
                emit("NET_HOST_V1 target=RUNTIME stage=NETWORK_CHANGED code=0 value=$changed family=0 ms=${SystemClock.elapsedRealtime() - started}")
            }
            state = when {
                work.cancelled.get() || !RuntimeDiagnostics.enabled() || configuration !== config || !current() -> "CANCELLED"
                done.count == 0L -> "COMPLETE"
                else -> "TIME_LIMIT"
            }
        } catch (_: InterruptedException) { state = "CANCELLED"; Thread.currentThread().interrupt() }
          catch (_: Exception) { state = "ERROR" }
        finally {
            work.cancelled.set(true)
            work.release()
            // Keep the gate until workers really exit; a stuck system API must not accumulate threads on retries.
            Thread({
                workers.forEach { runCatching { it.join() } }
                work.release()
                running.compareAndSet(work, null)
            }, "aura-network-cleanup").apply { isDaemon = true; start() }
        }
        return report.render(state).also { runCatching { checkpoint(it) } }
    }

    private fun host(network: Network?, work: Work, started: Long, live: () -> Boolean, emit: (String) -> Unit) {
        val targets = listOf(Triple("STORE_HTTP", "store.steampowered.com", 80),
            Triple("DIRECTORY_HTTPS", "api.steampowered.com", 443))
        for ((label, host, port) in targets) {
            if (!live()) return
            fun event(stage: String, code: Int = 0, value: Int = 0, family: Int = 0) = emit(
                "NET_HOST_V1 target=$label stage=$stage code=$code value=$value family=$family ms=${SystemClock.elapsedRealtime() - started}")
            if (network == null) { event("UNAVAILABLE"); continue }
            val signal = CancellationSignal()
            val cancel = AutoCloseable { signal.cancel() }
            work.resources += cancel
            val answer = AtomicReference<List<InetAddress>>(emptyList())
            val dnsCode = java.util.concurrent.atomic.AtomicInteger(Int.MIN_VALUE)
            val dnsFailed = AtomicBoolean(false)
            val dnsDone = CountDownLatch(1)
            event("DNS_BEGIN")
            try {
                DnsResolver.getInstance().query(network, host, DnsResolver.FLAG_EMPTY, { it.run() }, signal,
                    object : DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(addresses: List<InetAddress>, rcode: Int) {
                            answer.set(addresses.take(64)); dnsCode.set(rcode); dnsDone.countDown()
                        }
                        override fun onError(error: DnsResolver.DnsException) {
                            dnsFailed.set(true)
                            dnsCode.set(error.code); dnsDone.countDown()
                        }
                    })
                if (!dnsDone.await(2500, TimeUnit.MILLISECONDS)) { event("TIMEOUT"); continue }
                event(if (dnsFailed.get()) "DNS_ERROR" else "DNS", dnsCode.get(), answer.get().size)
            } finally { signal.cancel(); work.resources -= cancel }
            if (!live()) return
            val addresses = answer.get()
            event("DNS_V4", value = addresses.count { it is Inet4Address }, family = 2)
            event("DNS_V6", value = addresses.count { it is Inet6Address }, family = 23)
            event("DNS_ZERO", value = addresses.count { it is Inet4Address && it.isAnyLocalAddress })
            // At most one address per family. Numeric address and target host are never logged.
            for (address in addresses.distinctBy { it is Inet6Address }) {
                if (!live()) return
                val family = if (address is Inet4Address) 2 else 23
                var stage = "CONNECT"
                var raw: Socket? = null
                try {
                    raw = network.socketFactory.createSocket()
                    work.resources += raw
                    if (!live()) continue
                    event("CONNECT_BEGIN", family = family)
                    raw.connect(InetSocketAddress(address, port), 2000)
                    event("CONNECT", family = family)
                    raw.soTimeout = 2000
                    val socket = if (port == 443) {
                        stage = "TLS"
                        event("TLS_BEGIN", family = family)
                        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
                        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                        tls.soTimeout = 2000
                        tls.startHandshake()
                        event("TLS", family = family)
                        tls
                    } else raw
                    stage = "HTTP_SEND"
                    event("HTTP_SEND_BEGIN", family = family)
                    val path = if (port == 443) "/ISteamDirectory/GetCMListForConnect/v1/?cellid=0&cmtype=websockets" else "/"
                    socket.getOutputStream().write(("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: AuraNetworkDiagnostic/1\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    event("HTTP_SEND", family = family)
                    stage = "HTTP_RECEIVE"
                    event("HTTP_RECEIVE_BEGIN", family = family)
                    val status = StringBuilder()
                    val input = socket.getInputStream()
                    while (status.length < 512 && live()) {
                        val ch = input.read()
                        if (ch < 0 || ch == 10) break
                        status.append(ch.toChar())
                    }
                    val code = Regex("HTTP/1\\.[01] ([1-5][0-9]{2})(?: .*)?\\r?").matchEntire(status.toString())
                        ?.groupValues?.get(1)?.toIntOrNull()
                    if (code != null) event("HTTP_STATUS", value = code, family = family) else event("IO_ERROR", family = family)
                } catch (error: Exception) {
                    val errno = generateSequence(error as Throwable?) { it.cause }.take(8).filterIsInstance<ErrnoException>().firstOrNull()?.errno ?: 0
                    event(stage, errno, -1, family)
                    event(when (error) { is SocketTimeoutException -> "TIMEOUT"; is SSLException -> "TLS_ERROR"; else -> "IO_ERROR" }, errno, family = family)
                } finally { raw?.let { runCatching { it.close() }; work.resources -= it } }
            }
        }
        emit("NET_HOST_V1 target=RUNTIME stage=DONE code=0 value=0 family=0 ms=${SystemClock.elapsedRealtime() - started}")
    }
}
