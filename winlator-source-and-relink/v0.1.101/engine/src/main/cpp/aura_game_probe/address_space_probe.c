/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT */
#define _GNU_SOURCE
#include "address_space_probe.h"
#include <errno.h>
#include <string.h>
#include <sys/mman.h>

#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif

struct va_signature { uint64_t offset, fingerprint; unsigned bytes; };
/* Reproduced by verify_address_space.py from SHA-256 pinned private/public inputs.
 * No proprietary EXE instructions are embedded in the app or the fixtures. */
static const struct va_signature game_signatures[] = {
    {0xb195c80, UINT64_C(0x74aa7987038dc740), 335},
    {0x16e9b20, UINT64_C(0x77bad85ea9bdc524), 246},
    {0x1796f20, UINT64_C(0x39053788331750fe), 38},
    {0xb19561f, UINT64_C(0xafe1595daaac02a4), 12}
};
static const struct va_signature wine_signatures[] = {
    {0, UINT64_C(0x42f04ee13f71da25), 512},
    {0x106e98, UINT64_C(0xbccb54a31ed4f20a), 128},
    {0x10e0c0, UINT64_C(0x8a23be4e0efa6c09), 128}
};
static const uint64_t wine_offsets[] = {0x1c9e70, 0x1c9e50, 0x1c9e68, 0x2200c0, 0x2200d8};

static unsigned va_match(uint64_t base, const struct va_signature *signatures, unsigned count,
                         aura_ec_read read_memory, void *opaque) {
    unsigned char data[512];
    for (unsigned i = 0; i < count; i++) {
        const struct va_signature *s = &signatures[i];
        if (s->bytes > sizeof(data) || base > UINT64_MAX - s->offset - s->bytes) return VA_LAYOUT;
        if (!read_memory(opaque, base + s->offset, data, s->bytes)) return VA_UNREADABLE;
        uint64_t hash = UINT64_C(0xcbf29ce484222325);
        for (unsigned j = 0; j < s->bytes; j++) hash = (hash ^ data[j]) * UINT64_C(0x100000001b3);
        if (hash != s->fingerprint) return VA_LAYOUT;
    }
    return VA_CAPTURED;
}

void aura_va_wine(uint64_t base, aura_ec_read read_memory, void *opaque, struct aura_va_snapshot *out) {
    memset(out->wine, 0, sizeof(out->wine));
    out->wine_state = VA_NOT_FOUND;
    if (!base) return;
    unsigned state = va_match(base, wine_signatures, 3, read_memory, opaque);
    if (state != VA_CAPTURED) { out->wine_state = state; return; }
    uint64_t values[2][5];
    for (unsigned pass = 0; pass < 2; pass++) for (unsigned i = 0; i < 5; i++) {
        if (base > UINT64_MAX - wine_offsets[i] - 8 ||
            !read_memory(opaque, base + wine_offsets[i], &values[pass][i], 8)) {
            out->wine_state = VA_UNREADABLE; return;
        }
    }
    if (memcmp(values[0], values[1], sizeof(values[0]))) { out->wine_state = VA_CHANGED; return; }
    for (unsigned i = 0; i < 4; i++) if (values[0][i] < 0x10000 || (values[0][i] & 0xffff)) {
        out->wine_state = VA_INVALID; return;
    }
    memcpy(out->wine, values[0], sizeof(out->wine));
    out->wine_state = VA_CAPTURED;
}

void aura_va_southpark(aura_ec_read read_memory, void *opaque, struct aura_va_snapshot *out) {
    const uint64_t base = UINT64_C(0x140000000), pool = UINT64_C(0x143cba368);
    memset(out->game, 0, sizeof(out->game));
    unsigned state = va_match(base, game_signatures, 4, read_memory, opaque);
    if (state != VA_CAPTURED) { out->game_state = state; return; }
    uint64_t values[2][6];
    for (unsigned pass = 0; pass < 2; pass++) {
        if (!read_memory(opaque, pool + 0xa0, &values[pass][0], 8) ||
            !read_memory(opaque, pool + 0x30, &values[pass][1], 24) ||
            !read_memory(opaque, pool + 0x48, &values[pass][4], 8) ||
            !read_memory(opaque, UINT64_C(0x143cca1b0), &values[pass][5], 8)) {
            out->game_state = VA_UNREADABLE; return;
        }
        values[pass][5] &= UINT32_MAX; /* The global error is a DWORD; ignore its neighbour. */
    }
    if (memcmp(values[0], values[1], sizeof(values[0]))) { out->game_state = VA_CHANGED; return; }
    uint64_t arena = values[0][0];
    if ((arena & 0xffff) || arena > UINT64_MAX - UINT64_C(0xc000000000) ||
        values[0][1] != arena + UINT64_C(0x8000000000) ||
        values[0][4] != arena + UINT64_C(0xc000000000)) {
        out->game_state = VA_INVALID; return;
    }
    memcpy(out->game, values[0], sizeof(out->game));
    out->game_state = VA_CAPTURED;
}

void aura_va_kernel(uint64_t page_size, struct aura_va_snapshot *out) {
    const uint64_t addresses[] = {UINT64_C(0x8000000000), UINT64_C(0xc000000000), UINT64_C(0x10000000000)};
    out->page_size = page_size;
    memset(out->kernel_state, 0, sizeof(out->kernel_state));
    memset(out->kernel_errno, 0, sizeof(out->kernel_errno));
    if (sizeof(uintptr_t) != 8 || (page_size != 4096 && page_size != 16384 && page_size != 65536)) return;
    for (unsigned i = 0; i < 3; i++) {
        void *address = (void *)(uintptr_t)addresses[i];
        void *ret = mmap(address, (size_t)page_size, PROT_NONE,
                         MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
        int error = errno;
        if (ret == MAP_FAILED) {
            out->kernel_state[i] = error == EEXIST ? VA_KERNEL_OCCUPIED : VA_KERNEL_REJECTED;
            out->kernel_errno[i] = (uint32_t)error;
        } else {
            out->kernel_state[i] = ret == address ? VA_KERNEL_EXACT : VA_KERNEL_REDIRECTED;
            /* Old kernels can ignore NOREPLACE and treat the address as a hint.
             * Always remove our returned mapping, never unmap the requested address. */
            if (munmap(ret, (size_t)page_size)) {
                out->kernel_state[i] = VA_KERNEL_CLEANUP_FAILED;
                out->kernel_errno[i] = (uint32_t)errno;
            }
        }
    }
}

const char *aura_va_state_name(unsigned state) {
    const char *names[] = {"NOT_REQUESTED", "CAPTURED", "NOT_FOUND", "UNREADABLE", "LAYOUT_MISMATCH",
                          "CHANGED", "NOT_INITIALIZED_OR_INVALID", "NOT_APPLICABLE", "LIMIT"};
    return state < sizeof(names) / sizeof(*names) ? names[state] : "UNKNOWN";
}
const char *aura_va_kernel_name(unsigned state) {
    const char *names[] = {"UNKNOWN", "EXACT", "OCCUPIED", "HINT_REDIRECTED", "REJECTED", "CLEANUP_FAILED"};
    return state < sizeof(names) / sizeof(*names) ? names[state] : "UNKNOWN";
}
