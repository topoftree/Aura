/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.util.Locale

/** Fixed observations from this session's connection_log.txt; not a replacement for live IPC.
 * Never stores a raw line, endpoint, account identifier, login token or SteamID.
 */
internal class SteamConnectionObservation {
    private var phase = "NO_FRESH_PHASE"
    private var logon = "UNOBSERVED"
    private var logoff = "UNOBSERVED"
    private val signals = linkedSetOf<String>()
    private val counts = linkedMapOf<String, Int>()
    private val recent = java.util.ArrayDeque<String>()
    private var sequence = 0
    private fun event(code: String) {
        counts[code] = ((counts[code] ?: 0) + 1).coerceAtMost(1_000_000)
        sequence = (sequence + 1).coerceAtMost(1_000_000)
        if (recent.size == 32) recent.removeFirst()
        recent.addLast("$sequence:$code")
    }

    fun accept(line: String) {
        val lower = line.take(4096).lowercase(Locale.ROOT)
        HEADER.find(lower)?.groupValues?.get(1)?.let {
            phase = when (it) {
                "offline" -> "OFFLINE_REPORTED"
                "connecting" -> "CONNECTING_REPORTED"
                "connected" -> "CONNECTED_REPORTED"
                "logged on" -> "LOGGED_ON_REPORTED"
                "logged off" -> "LOGGED_OFF_REPORTED"
                "logging on" -> "LOGGING_ON_REPORTED"
                "logging off" -> "LOGGING_OFF_REPORTED"
                else -> "DISCONNECTED_REPORTED"
            }
        }
        if ("recvmsgclientlogonresponse()" in lower) {
            logon = result(lower.substringAfter("recvmsgclientlogonresponse()"))
            event(if (logon == "OK_REPORTED") "LOGON_OK" else "LOGON_OTHER")
        }
        if ("recvmsgclientloggedoff()" in lower) {
            logoff = result(lower.substringAfter("recvmsgclientloggedoff()"))
            event("LOGGED_OFF")
        }
        if ("connectivity test" in lower && "http fetch failed" in lower) event("HTTP_FETCH_FAILED")
        if ("http connectivity test" in lower && " - success" in lower) event("HTTP_TEST_OK")
        if ("http connectivity test" in lower && " - timeout" in lower) event("HTTP_TEST_TIMEOUT")
        if ("getcmlistforconnect -- web api call failed" in lower) {
            event("DIRECTORY_API_FAILED")
            if (Regex("status\\s*=\\s*0\\b").containsMatchIn(lower)) event("DIRECTORY_STATUS_ZERO")
        }
        if ("websocket" in lower && "timeout/neterror" in lower) event("WEBSOCKET_PROBE_FAILED")
        if ("connectfailed(" in lower) event("CONNECT_FAILED")
        if ("connectioncompleted(" in lower) event("CONNECT_COMPLETED")
        if ("connectfailed" in lower || "connection failed" in lower) signals += "CONNECTION_FAILED"
        if ("connection timed out" in lower || "connect timeout" in lower) signals += "CONNECTION_TIMEOUT"
        if ("failed to resolve" in lower || "name resolution failed" in lower) signals += "NAME_RESOLUTION_FAILED"
        if ("certificate verify failed" in lower || "certificate validation failed" in lower) signals += "CERTIFICATE_CHECK_FAILED"
        if ("ssl handshake failed" in lower || "tls handshake failed" in lower) signals += "TLS_HANDSHAKE_FAILED"
    }

    fun report(fresh: Boolean) = "STEAM_CONNECTION_V1 fresh=$fresh; phase=$phase; logon=$logon; " +
        "signals=${signals.joinToString(",").ifEmpty { "NONE" }}; logoff=$logoff; authentication=UNCONFIRMED"

    fun networkReport(truncated: Boolean) = "STEAM_NETWORK_EVENTS_V1 scope=SESSION_LOG_TAIL; tail_truncated=$truncated; " +
        "timestamps=NOT_RETAINED; authentication=UNCONFIRMED\n" +
        "counts=" + counts.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "NONE" } + "\n" +
        "recent=" + recent.joinToString(",").ifEmpty { "NONE" } + "\n" +
        "Status zero is not an HTTP response or proof of a DNS zero-address answer. Events are Steam's reports, not API interception.\n"

    companion object {
        private fun result(value: String): String {
            // Steam also prints human-readable names such as 'No Connection'. Only fixed
            // allowlisted results leave this parser; unknown text/account fields never do.
            val quoted = Regex("'([a-z _-]{1,64})'").find(value)?.groupValues?.get(1)
                ?.filter { it in 'a'..'z' }
            if (quoted != null) return when (quoted) {
                "ok" -> "OK_REPORTED"
                in RESULT_CODES -> quoted.uppercase(Locale.ROOT)
                else -> "OTHER_REPORTED"
            }
            if (Regex("""\bok\s*\(1\)""").containsMatchIn(value)) return "OK_REPORTED"
            return RESULT_CODES.firstOrNull { Regex("\\b$it\\b").containsMatchIn(value) }
                ?.uppercase(Locale.ROOT) ?: "OTHER_REPORTED"
        }
        private val HEADER = Regex("""^\s*(?:\[[0-9: .\-/]+]\s*)?\[(offline|connecting|connected|logged on|logged off|logging on|logging off|disconnected),\s*\d+,\s*\d+]""")
        private val RESULT_CODES = listOf("invalidpassword", "invalidloginauthcode", "expiredloginauthcode",
            "accountlogondenied", "accountlogindeniedneedtwofactor", "twofactorcodemismatch",
            "loggedinelsewhere", "logonsessionreplaced", "serviceunavailable", "noconnection",
            "connectfailed", "timeout", "tryanothercm", "ratelimitexceeded", "accountdisabled",
            "fail", "busy", "invalidprotocolver", "notloggedon", "remotedisconnect", "iofailure")
    }
}
