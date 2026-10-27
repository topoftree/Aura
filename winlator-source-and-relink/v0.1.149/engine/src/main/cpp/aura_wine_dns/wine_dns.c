/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* Wine-process-only resolver fallback. Successful libc answers are untouched.
 * Android's resolver selects the network; no DNS server, proxy or TLS changes.
 * Numeric libc conversions allocate every result, so ordinary freeaddrinfo owns it.
 */
#define _GNU_SOURCE
#include "dns_answer.h"
#include <android/multinetwork.h>
#include <arpa/inet.h>
#include <arpa/nameser.h>
#include <dlfcn.h>
#include <errno.h>
#include <netdb.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static int (*original)(const char *, const char *, const struct addrinfo *, struct addrinfo **);
static pthread_once_t once = PTHREAD_ONCE_INIT;
static atomic_uint pending, messages;
static _Thread_local int resolving;
static int enabled;

static void initialize(void) {
    *(void **)(&original) = dlsym(RTLD_NEXT, "getaddrinfo");
    const char *setting = getenv("AURA_WINE_DNS_FALLBACK");
    enabled = !setting || strcmp(setting, "0");
}
static int64_t now_ms(void) {
    struct timespec t;
    if (clock_gettime(CLOCK_MONOTONIC, &t)) return 0;
    return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}
static int bindings(net_handle_t *process, net_handle_t *dns) {
    return android_getprocnetwork(process) || android_getprocdns(dns);
}
static void record(const char *state, unsigned v4, unsigned v6, int a, int aaaa) {
    if (atomic_fetch_add(&messages, 1) >= 8) return;
    char line[192];
    int n = snprintf(line, sizeof(line), "AURA_WINE_DNS_V1 state=%s; ipv4=%u; ipv6=%u; raw_a=%d; raw_aaaa=%d; scope=NAME_RESOLUTION_ONLY\n",
                     state, v4, v6, a, aaaa);
    if (n <= 0 || (size_t)n >= sizeof(line)) return;
    // A diagnostic pipe closing must not introduce SIGPIPE into a resolver call.
    sigset_t block, previous, waiting;
    sigemptyset(&block); sigaddset(&block, SIGPIPE);
    if (pthread_sigmask(SIG_BLOCK, &block, &previous)) return;
    int had_pending = sigpending(&waiting) == 0 && sigismember(&waiting, SIGPIPE);
    if (write(STDERR_FILENO, line, (size_t)n) < 0 && errno == EPIPE && !had_pending) {
        const struct timespec zero = {0, 0};
        (void)sigtimedwait(&block, NULL, &zero);
    }
    (void)pthread_sigmask(SIG_SETMASK, &previous, NULL);
}

static int query(net_handle_t network, const char *node, int type, int64_t deadline,
                 struct aura_dns_answer *answer) {
    answer->count = 0;
    if (now_ms() >= deadline) return -100;
    int fd = android_res_nquery(network, node, ns_c_in, type, 0);
    if (fd < 0) return -101;
    struct pollfd ready = {.fd = fd, .events = POLLIN};
    int64_t family_deadline = now_ms() + 1500;
    if (family_deadline > deadline) family_deadline = deadline;
    int status;
    do {
        int64_t left = family_deadline - now_ms();
        if (left <= 0) { android_res_cancel(fd); return -100; }
        status = poll(&ready, 1, (int)left);
    } while (status < 0 && errno == EINTR);
    if (status <= 0 || (ready.revents & POLLNVAL)) { android_res_cancel(fd); return -102; }
    uint8_t data[8192];
    int rcode = -1;
    int length = android_res_nresult(fd, &rcode, data, sizeof(data)); // Consumes fd, including failure.
    if (length < 0 || length > (int)sizeof(data)) return -103;
    return aura_dns_parse(data, (size_t)length, node, type, rcode, answer);
}

static int numeric_entries(const struct aura_dns_answer *answers, int family, const char *service,
                           const struct addrinfo *hints, struct addrinfo ***tail, unsigned *count) {
    for (unsigned i = 0; i < answers->count; ++i) {
        char numeric[INET6_ADDRSTRLEN];
        if (!inet_ntop(family, answers->address[i], numeric, sizeof(numeric))) return 0;
        struct addrinfo h = *hints, *entry = NULL;
        h.ai_family = family;
        h.ai_flags |= AI_NUMERICHOST;
        // No hostname lookup: libc applies service, socket and ADDRCONFIG semantics.
        int status = original(numeric, service, &h, &entry);
        if (status == EAI_NONAME || status == EAI_NODATA) continue;
        if (status || !entry) return 0;
        **tail = entry;
        while (entry) {
            entry->ai_flags = hints->ai_flags;
            *tail = &entry->ai_next;
            entry = entry->ai_next;
        }
        ++*count;
    }
    return 1;
}

__attribute__((visibility("default")))
int getaddrinfo(const char *node, const char *service, const struct addrinfo *hints, struct addrinfo **result) {
    int entry_errno = errno;
    pthread_once(&once, initialize);
    if (!original) { errno = ENOSYS; return EAI_SYSTEM; }
    errno = entry_errno;
    int status = original(node, service, hints, result);
    int saved_errno = errno;
    const struct addrinfo defaults = {0};
    const struct addrinfo *h = hints ? hints : &defaults;
    const int supported_flags = AI_ADDRCONFIG | AI_PASSIVE | AI_NUMERICSERV;
    unsigned char literal[16];
    if (!status || !enabled || resolving || !result ||
        (status != EAI_NONAME && status != EAI_NODATA && status != EAI_AGAIN) ||
        (h->ai_family != AF_UNSPEC && h->ai_family != AF_INET && h->ai_family != AF_INET6) ||
        (h->ai_flags & ~supported_flags) ||
        (h->ai_socktype != 0 && h->ai_socktype != SOCK_STREAM && h->ai_socktype != SOCK_DGRAM) ||
        (h->ai_protocol != 0 && h->ai_protocol != IPPROTO_TCP && h->ai_protocol != IPPROTO_UDP) ||
        !aura_dns_hostname(node) || !strchr(node, '.') ||
        inet_pton(AF_INET, node, literal) == 1 || inet_pton(AF_INET6, node, literal) == 1) {
        errno = saved_errno; return status;
    }
    if (atomic_fetch_add(&pending, 1) >= 4) { atomic_fetch_sub(&pending, 1); errno = saved_errno; return status; }
    resolving = 1;
    net_handle_t process = 0, dns = 0, after_process = 0, after_dns = 0;
    struct addrinfo *head = NULL, **tail = &head;
    struct aura_dns_answer a = {0}, aaaa = {0};
    int a_status = -104, aaaa_status = -104;
    unsigned v4 = 0, v6 = 0;
    int success = 0;
    if (bindings(&process, &dns) || (process && dns && process != dns)) goto done;
    net_handle_t network = process ? process : dns; // Zero uses Android's current default, including VPN.
    int64_t deadline = now_ms() + 3000;
    if (h->ai_family != AF_INET6) a_status = query(network, node, ns_t_a, deadline, &a);
    if (h->ai_family != AF_INET) aaaa_status = query(network, node, ns_t_aaaa, deadline, &aaaa);
    if (bindings(&after_process, &after_dns) || process != after_process || dns != after_dns) goto done;
    // One family's failure does not invalidate a separately validated other-family answer.
    if ((!aaaa_status && !numeric_entries(&aaaa, AF_INET6, service, h, &tail, &v6)) ||
        (!a_status && !numeric_entries(&a, AF_INET, service, h, &tail, &v4))) goto done;
    if (head && now_ms() <= deadline && !bindings(&after_process, &after_dns) &&
        process == after_process && dns == after_dns) success = 1;
done:
    if (success) *result = head;
    else if (head) freeaddrinfo(head);
    record(success ? "RECOVERED" : "UNRESOLVED", v4, v6, a_status, aaaa_status);
    resolving = 0;
    atomic_fetch_sub(&pending, 1);
    errno = success ? entry_errno : saved_errno;
    return success ? 0 : status;
}
