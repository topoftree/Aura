/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

/** Only fixed tokens and numeric results cross from the guest probe into an export. */
class NetworkProbeReport {
    private val lines = mutableListOf<String>()
    private var recordCharacters = 0
    private var discarded = 0
    private var hostLines = 0
    private var wineLines = 0
    private var nativeLines = 0
    private var hostNativeLines = 0
    private var rawLines = 0
    private var hostDone = false
    private var wineDone = false
    private var nativeDone = false
    private var hostNativeDone = false
    private var rawDone = false
    private var helperState = "NOT_STARTED"
    private var helperExit: Int? = null
    private var helperBytes = 0
    @Synchronized fun accept(line: String): Boolean {
        val match = FORMAT.matchEntire(line) ?: run { discarded++; return false }
        val source = match.groupValues[1]
        val stage = match.groupValues[3]
        val allowed = when (source) {
            "NATIVE" -> NATIVE_STAGES + RAW_STAGES
            "HOST_NATIVE" -> NATIVE_STAGES
            else -> STAGES
        }
        if (match.groupValues[2] !in TARGETS || stage !in allowed ||
            (stage == "NETWORK_CHANGED" && source != "HOST") ||
            match.groupValues.drop(4).any { it.toLongOrNull() == null } || lines.size >= 192 ||
            recordCharacters + line.length + 1 > 28 * 1024) {
            discarded++; return false
        }
        lines += line
        recordCharacters += line.length + 1
        val done = match.groupValues[2] == "RUNTIME" && match.groupValues[3] == "DONE" &&
            match.groupValues[4] == "0"
        when (source) {
            "HOST" -> { hostLines++; hostDone = hostDone || done }
            "PROBE" -> { wineLines++; wineDone = wineDone || done }
            "NATIVE" -> {
                nativeLines++; nativeDone = nativeDone || done
                if (stage in RAW_STAGES) rawLines++
                if (match.groupValues[2] == "RUNTIME" && stage == "RAW_DONE" && match.groupValues[4] == "0") rawDone = true
            }
            "HOST_NATIVE" -> { hostNativeLines++; hostNativeDone = hostNativeDone || done }
        }
        return true
    }

    /** Host-owned process status: never accept error messages, paths, or arbitrary stderr. */
    @Synchronized fun helper(state: String, exitCode: Int? = null, stdoutBytes: Int = 0) {
        require(state in HELPER_STATES && stdoutBytes in 0..64 * 1024)
        helperState = state
        helperExit = exitCode
        helperBytes = stdoutBytes
    }

    @Synchronized fun render(state: String): String {
        require(state in STATES)
        // Worker completion alone cannot establish that both controls produced a complete record.
        val recordedState = if (state == "COMPLETE" && (!hostDone || !wineDone || !nativeDone || !hostNativeDone || !rawDone ||
                helperState != "EXITED" || helperExit != 0)) "PARTIAL" else state
        fun side(done: Boolean, count: Int) = if (done) "DONE" else if (count == 0) "NO_OUTPUT" else "PARTIAL"
        return "NETWORK_CONTROL_V1 state=$recordedState; scope=ANONYMOUS_CONTROL; proxy=DIRECT; " +
            "steam_authentication=UNTESTED; limit_ms=20000; discarded_lines=$discarded\n" +
            "NETWORK_HELPER_V1 state=$helperState; exit=${helperExit ?: "UNKNOWN"}; stdout_bytes=$helperBytes; " +
            "host=${side(hostDone, hostLines)}; wine=${side(wineDone, wineLines)}; " +
            "host_lines=$hostLines; wine_lines=$wineLines; completion_is_connectivity=NO\n" +
            "NETWORK_NATIVE_V1 state=${side(nativeDone, nativeLines)}; lines=$nativeLines; " +
            "scope=HELPER_LIBC_LOOKUPS; hints=UNSPEC_STREAM_TCP_FLAGS0; game_network=UNCHANGED\n" +
            "NETWORK_HOST_NATIVE_V1 state=${side(hostNativeDone, hostNativeLines)}; lines=$hostNativeLines; " +
            "scope=APP_PROCESS_LIBC_LOOKUPS; synchronous_cancel=UNSUPPORTED; late_results=DISCARDED\n" +
            "NETWORK_RAW_V1 state=${side(rawDone, rawLines)}; lines=$rawLines; scope=HELPER_ANDROID_RESOLVER; " +
            "network=CAPTURE_SNAPSHOT; queries=A_AAAA; answers=RECORD_COUNTS_ONLY\n" +
            "Targets: STORE_HTTP=store.steampowered.com:80/; " +
            "DIRECTORY_HTTPS=api.steampowered.com:443/ISteamDirectory/GetCMListForConnect/v1/?cellid=0&cmtype=websockets\n" +
            "Independent Android/Wine controls, not intercepted Steam requests. DNS codes: Android DnsResolver / Wine getaddrinfo. " +
            "Wine socket codes: WSA; HTTP codes: GetLastError. Android errors: errno where available. " +
            "DNS_ZERO counts unspecified IPv4 addresses. SELECT value=0 means timeout. HTTP_STATUS value is only valid after success.\n" +
            "Native DEFAULT_DNS/ACTIVE_DNS code=raw EAI, value=observed errno (error meaning only for EAI_SYSTEM). " +
            "DEFAULT observes Wine's original libc call; ACTIVE uses identical hints and the captured Android Network. " +
            "Binding value: 0=unbound, 1=matches snapshot, 2=different, 3=unknown; binding code=errno on failure. " +
            "ACTIVE_NETWORK value=availability; NETWORK_CHANGED value: 0=no, 1=yes, 2=unknown. " +
            "Each native side has its own start clock; do not subtract host/native timestamps. " +
            "HOST_NATIVE uses the same hints in the app process; helper DEFAULT still preserves Wine's original call.\n" +
            "Raw RAW_QUERY/RAW_RESULT/RAW_WAIT code=negative POSIX error or 0; RAW_RESULT value=byte length, " +
            "RAW_RCODE code=DNS rcode only after successful result; RAW_ANSWERS value=matching answer RR count or -1 if invalid, " +
            "code=negative parse/limit error or 0. No payloads exported. Helper Wine timings include diagnostic cross-checks. " +
            "Missing host-native DONE or helper RAW_DONE means comparison incomplete.\n" +
            lines.joinToString("\n") + "\n"
    }
    companion object {
        private val TARGETS = setOf("RUNTIME", "STORE_HTTP", "DIRECTORY_HTTPS")
        private val STAGES = setOf("WSA_START", "DONE", "DNS_BEGIN", "DNS", "DNS_V4", "DNS_V6", "DNS_ZERO",
            "SOCKET", "NONBLOCK", "CONNECT_BEGIN", "CONNECT", "SELECT", "SO_ERROR", "HTTP_OPEN", "HTTP_TIMEOUTS",
            "HTTP_CONNECT_HANDLE", "HTTP_REQUEST", "HTTP_OPTIONS", "HTTP_SEND_BEGIN", "HTTP_SEND", "HTTP_RECEIVE_BEGIN",
            "HTTP_RECEIVE", "HTTP_STATUS", "TLS_BEGIN", "TLS", "TIMEOUT", "TLS_ERROR", "IO_ERROR", "DNS_ERROR", "UNAVAILABLE", "NETWORK_CHANGED")
        private val NATIVE_STAGES = setOf("ACTIVE_NETWORK", "PROCESS_NETWORK", "PROCESS_DNS", "DEFAULT_BEGIN", "DEFAULT_DNS",
            "DEFAULT_V4", "DEFAULT_V6", "ACTIVE_BEGIN", "ACTIVE_DNS", "ACTIVE_V4", "ACTIVE_V6", "ACTIVE_UNAVAILABLE",
            "ADDRESS_LIMIT", "TARGET_DONE", "DONE", "UNAVAILABLE")
        private val RAW_STAGES = setOf("RAW_BEGIN", "RAW_QUERY", "RAW_WAIT", "RAW_RESULT", "RAW_RCODE",
            "RAW_ANSWERS", "RAW_DONE", "RAW_UNAVAILABLE")
        private val FORMAT = Regex("NET_(PROBE|HOST_NATIVE|HOST|NATIVE)_V1 target=([A-Z_]+) stage=([A-Z0-9_]+) code=(-?\\d{1,10}) value=(-?\\d{1,10}) family=(0|2|23) ms=(\\d{1,10})")
        private val STATES = setOf("RUNNING", "COMPLETE", "PARTIAL", "TIME_LIMIT", "CANCELLED", "UNAVAILABLE", "BUSY", "ERROR")
        private val HELPER_STATES = setOf("NOT_STARTED", "INSTALLING", "STARTING", "RUNNING", "EXITED",
            "INSTALL_FAILED", "START_FAILED", "READ_FAILED", "OUTPUT_LIMIT", "CANCELLED", "TIME_LIMIT")
    }
}
