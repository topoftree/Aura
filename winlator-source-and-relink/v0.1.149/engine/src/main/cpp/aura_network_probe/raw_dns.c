/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* Helper-only Android raw DNS cross-control. Uses the system DNS message parser;
 * counts answer records, never returns these records to Wine or prints payloads.
 * The owner kills this diagnostic helper at the overall capture deadline. */
#include "raw_dns.h"
#include <arpa/nameser.h>
#include <errno.h>
#include <poll.h>
#include <stdint.h>
#include <time.h>

static int64_t now_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts)) return 0;
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int count_answers(const uint8_t *message, int length, int type, int rcode, int *count) {
    ns_msg parsed;
    *count = -1;
    if (ns_initparse(message, length, &parsed) < 0 || !ns_msg_getflag(parsed, ns_f_qr) ||
        ns_msg_getflag(parsed, ns_f_opcode) != ns_o_query || ns_msg_getflag(parsed, ns_f_rcode) != rcode)
        return -EBADMSG;
    if (ns_msg_getflag(parsed, ns_f_tc)) return -EMSGSIZE;
    int records = ns_msg_count(parsed, ns_s_an);
    if (records > 64) return -EOVERFLOW;
    int matched = 0;
    for (int i = 0; i < records; ++i) {
        ns_rr answer;
        if (ns_parserr(&parsed, ns_s_an, i, &answer) < 0) return -EBADMSG;
        if (ns_rr_class(answer) != ns_c_in || (int)ns_rr_type(answer) != type) continue;
        if (ns_rr_rdlen(answer) != (type == ns_t_a ? 4u : 16u)) return -EBADMSG;
        ++matched;
    }
    *count = matched;
    return 0;
}

static void query(net_handle_t network, const char *host, const char *target, int type,
                  int family, aura_dns_event emit) {
    emit(target, "RAW_BEGIN", 0, 0, family);
    int fd = android_res_nquery(network, host, ns_c_in, type, 0);
    // API errors are negative POSIX codes, not errno or Winsock codes. Never log fd.
    emit(target, "RAW_QUERY", fd < 0 ? fd : 0, fd < 0 ? 0 : 1, family);
    if (fd < 0) return;
    struct pollfd pending = {.fd = fd, .events = POLLIN};
    int64_t deadline = now_ms() + 2500;
    int ready;
    for (;;) {
        int64_t left = deadline - now_ms();
        if (left <= 0) { ready = 0; break; }
        ready = poll(&pending, 1, (int)left);
        if (ready >= 0 || errno != EINTR) break;
    }
    if (ready <= 0 || (pending.revents & POLLNVAL)) {
        int failure = ready < 0 ? -errno : ready == 0 ? -ETIMEDOUT : -EBADF;
        emit(target, "RAW_WAIT", failure, 0, family);
        android_res_cancel(fd); // Only the not-consumed descriptor belongs to us.
        return;
    }
    uint8_t answer[8192];
    int rcode = -1;
    int length = android_res_nresult(fd, &rcode, answer, sizeof(answer)); // Consumes fd, even on error.
    emit(target, "RAW_RESULT", length < 0 ? length : 0, length < 0 ? 0 : length, family);
    if (length < 0) return;
    emit(target, "RAW_RCODE", rcode, 0, family);
    int count = -1;
    int status = length > (int)sizeof(answer) ? -EMSGSIZE : count_answers(answer, length, type, rcode, &count);
    // This is a bounded RR count, not a usable address list or an authentication result.
    emit(target, "RAW_ANSWERS", status, count, family);
}

void aura_raw_dns_compare(net_handle_t network, const char *host, const char *target, aura_dns_event emit) {
    query(network, host, target, ns_t_a, 2, emit);
    query(network, host, target, ns_t_aaaa, 23, emit);
}
