/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
#pragma once
#include <stddef.h>
#include <stdint.h>

#define AURA_DNS_ADDRESSES 16
struct aura_dns_answer { unsigned count; unsigned char address[AURA_DNS_ADDRESSES][16]; };
int aura_dns_hostname(const char *name);
/* Fixed negative validation codes, not POSIX/DNS codes. No payload logging. */
int aura_dns_parse(const uint8_t *message, size_t length, const char *question,
                   int type, int rcode, struct aura_dns_answer *answer);
