/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
#pragma once
#include <android/multinetwork.h>

typedef void (*aura_dns_event)(const char *target, const char *stage, int code, int value, int family);
/* Two fixed A/AAAA controls, current snapshot only. No answers escape this call. */
void aura_raw_dns_compare(net_handle_t network, const char *host, const char *target, aura_dns_event emit);
