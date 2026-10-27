/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT
 * Exact comparison and copy for readable, non-overlapping SHM rectangles.
 * Keep libc's fast equality check for unchanged 4 KiB regions. Within a
 * changed region, compare/store the same loaded 64 bytes instead of copying
 * the entire region. No retained frame state or sampled-equality decisions.
 */
#ifndef AURA_FRAME_COPY_REGIONS_H
#define AURA_FRAME_COPY_REGIONS_H
#include "frame_copy.h"
#include <stdint.h>
#include <string.h>

/* The fixed-size builtin copies support unaligned source/destination pointers
 * without type-punning or over-reading. Clang emits NEON on Android ARM64;
 * the same code is exercised by host guard-page and sanitizer tests. */
typedef uint64_t aura_copy_vector __attribute__((vector_size(16)));
static inline size_t copy_changed_region(void *dst, const void *src, size_t bytes, aura_copy_fn copy) {
    size_t at = 0, write_bytes = 0;
    while (bytes - at >= AURA_FRAME_REGIONS_BLOCK_BYTES) {
        aura_copy_vector s0, s1, s2, s3, d0, d1, d2, d3;
        __builtin_memcpy(&s0, (const char *)src + at, 16);
        __builtin_memcpy(&s1, (const char *)src + at + 16, 16);
        __builtin_memcpy(&s2, (const char *)src + at + 32, 16);
        __builtin_memcpy(&s3, (const char *)src + at + 48, 16);
        __builtin_memcpy(&d0, (const char *)dst + at, 16);
        __builtin_memcpy(&d1, (const char *)dst + at + 16, 16);
        __builtin_memcpy(&d2, (const char *)dst + at + 32, 16);
        __builtin_memcpy(&d3, (const char *)dst + at + 48, 16);
        aura_copy_vector diff = (s0 ^ d0) | (s1 ^ d1) | (s2 ^ d2) | (s3 ^ d3);
        if (diff[0] | diff[1]) {
            __builtin_memcpy((char *)dst + at, &s0, 16);
            __builtin_memcpy((char *)dst + at + 16, &s1, 16);
            __builtin_memcpy((char *)dst + at + 32, &s2, 16);
            __builtin_memcpy((char *)dst + at + 48, &s3, 16);
            write_bytes += AURA_FRAME_REGIONS_BLOCK_BYTES;
        }
        at += AURA_FRAME_REGIONS_BLOCK_BYTES;
    }
    if (at < bytes) {
        const size_t tail = bytes - at; /* At most 63 bytes, no vector over-read. */
        if (memcmp((const char *)dst + at, (const char *)src + at, tail)) {
            copy((char *)dst + at, (const char *)src + at, tail);
            write_bytes += tail;
        }
    }
    return write_bytes;
}

static inline void copy_regions(void *dst, const void *src, size_t bytes, aura_copy_fn copy,
                                size_t *compared, size_t *written) {
    size_t at = 0, read_bytes = 0, write_bytes = 0;
    while (at < bytes) {
        size_t n = bytes - at;
        if (n > AURA_FRAME_REGIONS_DENSE_BYTES) n = AURA_FRAME_REGIONS_DENSE_BYTES;
        read_bytes += n;
        if (memcmp((const char *)dst + at, (const char *)src + at, n)) {
            size_t changed;
            if (n < AURA_FRAME_REGIONS_BLOCK_BYTES) {
                /* The outer comparison already proved this small tail differs. */
                copy((char *)dst + at, (const char *)src + at, n);
                changed = n;
            } else {
                changed = copy_changed_region((char *)dst + at, (const char *)src + at, n, copy);
                /* Include both comparison passes. This is requested work,
                 * not measured DRAM traffic; libc may exit the probe early. */
                read_bytes += n;
            }
            write_bytes += changed;
            at += n;
            if (changed == AURA_FRAME_REGIONS_DENSE_BYTES) {
                /* Every 64-byte block in the 4 KiB region differed. Copy ALL
                 * remaining bytes, including the uninspected tail, before
                 * unmap/reuse. No unchecked pixels are skipped. */
                if (at < bytes) {
                    copy((char *)dst + at, (const char *)src + at, bytes - at);
                    write_bytes += bytes - at;
                }
                break;
            }
        } else at += n;
    }
    /* Publish counters once per chunk; buffers may not alias these counters. */
    *compared += read_bytes;
    *written += write_bytes;
}
#endif
