/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* Loaded only into Aura's bounded Windows diagnostic helper. Observe its first
 * libc lookup of each fixed target, then compare the SAME hints on the Android
 * Network captured by the host. Never bind the process or replace a DNS answer.
 * The host owns cancellation/20s timeout and kills only this helper.
 */
#define _GNU_SOURCE
#include <android/multinetwork.h>
#include <dlfcn.h>
#include <errno.h>
#include <netdb.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include "raw_dns.h"

static int (*next_getaddrinfo)(const char *, const char *, const struct addrinfo *, struct addrinfo **);
static pthread_once_t once = PTHREAD_ONCE_INIT;
static atomic_uint observed, completed, raw_completed;
static _Thread_local int comparing;
static int enabled;
static net_handle_t active_network;
static uint64_t started_ms;

static uint64_t now_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts)) return 0;
    return (uint64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void initialize(void) {
    *(void **)(&next_getaddrinfo) = dlsym(RTLD_NEXT, "getaddrinfo");
    const char *flag = getenv("AURA_NETWORK_PROBE");
    enabled = flag && !strcmp(flag, "1");
    started_ms = now_ms();
    const char *handle = getenv("AURA_NETWORK_HANDLE");
    if (!enabled || !handle || !*handle || strlen(handle) > 20) return;
    // No signs, spaces, trailing data or truncated network handles.
    for (const char *p = handle; *p; ++p) if (*p < '0' || *p > '9') return;
    errno = 0;
    char *end;
    unsigned long long value = strtoull(handle, &end, 10);
    if (!errno && !*end) active_network = (net_handle_t)value;
}

static void event(const char *target, const char *stage, int code, int value, int family) {
    char line[224];
    uint64_t now = now_ms();
    uint64_t elapsed = now >= started_ms ? now - started_ms : 0;
    int size = snprintf(line, sizeof(line),
        "NET_NATIVE_V1 target=%s stage=%s code=%d value=%d family=%d ms=%llu\n",
        target, stage, code, value, family, (unsigned long long)elapsed);
    // One bounded pipe write keeps each record intact alongside the PE helper.
    if (size > 0 && (size_t)size < sizeof(line)) (void)write(STDOUT_FILENO, line, (size_t)size);
}

static void counts(const char *target, int explicit_network, const struct addrinfo *result) {
    int v4 = 0, v6 = 0, total = 0;
    for (; result && total < 64; result = result->ai_next, ++total) {
        if (result->ai_family == AF_INET) ++v4;
        if (result->ai_family == AF_INET6) ++v6;
    }
    event(target, explicit_network ? "ACTIVE_V4" : "DEFAULT_V4", 0, v4, 2);
    event(target, explicit_network ? "ACTIVE_V6" : "DEFAULT_V6", 0, v6, 23);
    if (result) event(target, "ADDRESS_LIMIT", 0, 64, 0);
}

static void binding(const char *target, const char *stage, int (*read_binding)(net_handle_t *)) {
    net_handle_t network = NETWORK_UNSPECIFIED;
    errno = 0;
    int result = read_binding(&network);
    int error = errno;
    // 0=unbound, 1=matches captured Network, 2=different, 3=unknown/error.
    int relation = result ? 3 : !network ? 0 : !active_network ? 3 : network == active_network ? 1 : 2;
    event(target, stage, result ? error : 0, relation, 0);
}

int getaddrinfo(const char *node, const char *service, const struct addrinfo *hints, struct addrinfo **result) {
    int entry_errno = errno;
    pthread_once(&once, initialize);
    if (!next_getaddrinfo) { errno = ENOSYS; return EAI_SYSTEM; }
    int index = -1;
    if (enabled && !comparing && node && result && hints &&
        hints->ai_family == AF_UNSPEC && hints->ai_flags == 0 &&
        hints->ai_socktype == SOCK_STREAM && hints->ai_protocol == IPPROTO_TCP) {
        if (!strcmp(node, "store.steampowered.com") && service && !strcmp(service, "80")) index = 0;
        if (!strcmp(node, "api.steampowered.com") && service && !strcmp(service, "443")) index = 1;
    }
    unsigned bit = index < 0 ? 0 : 1u << index;
    int capture = bit && !(atomic_fetch_or(&observed, bit) & bit);
    const char *target = index == 0 ? "STORE_HTTP" : "DIRECTORY_HTTPS";
    if (capture) {
        event(target, "ACTIVE_NETWORK", 0, active_network != NETWORK_UNSPECIFIED, 0);
        binding(target, "PROCESS_NETWORK", android_getprocnetwork);
        binding(target, "PROCESS_DNS", android_getprocdns);
        event(target, "DEFAULT_BEGIN", 0, 0, 0);
    }
    // The original call executes exactly once, with unchanged arguments/errno.
    errno = entry_errno;
    int status = next_getaddrinfo(node, service, hints, result);
    int result_errno = errno;
    if (capture) {
        // value is observed errno; meaningful as an error only for EAI_SYSTEM.
        event(target, "DEFAULT_DNS", status, result_errno, 0);
        counts(target, 0, status == 0 ? *result : NULL);
        if (active_network) {
            struct addrinfo *control = NULL;
            event(target, "ACTIVE_BEGIN", 0, 0, 0);
            comparing = 1;
            errno = 0;
            int active_status = android_getaddrinfofornetwork(active_network, node, service, hints, &control);
            int active_errno = errno;
            comparing = 0;
            event(target, "ACTIVE_DNS", active_status, active_errno, 0);
            counts(target, 1, active_status == 0 ? control : NULL);
            if (active_status == 0 && control) freeaddrinfo(control);
            comparing = 1;
            aura_raw_dns_compare(active_network, node, target, event);
            comparing = 0;
            if ((atomic_fetch_or(&raw_completed, bit) | bit) == 3)
                event("RUNTIME", "RAW_DONE", 0, 0, 0);
        } else {
            event(target, "ACTIVE_UNAVAILABLE", 0, 0, 0);
            event(target, "RAW_UNAVAILABLE", 0, 0, 0);
        }
        event(target, "TARGET_DONE", 0, 0, 0);
        if ((atomic_fetch_or(&completed, bit) | bit) == 3)
            event("RUNTIME", "DONE", 0, 0, 0);
    }
    errno = result_errno;
    return status;
}
