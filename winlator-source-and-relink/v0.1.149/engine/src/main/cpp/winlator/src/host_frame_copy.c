/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT
 * Host-side contiguous drawable copies. This is separate from the guest Mesa
 * pool: at most two reusable helpers per host process, all writes complete
 * before texture upload or a response to the producer can proceed.
 */
#include "host_frame_copy.h"
#include "../../aura_graphics_trace/frame_copy.h"
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>

static _Atomic int enabled = 1;
static _Atomic uint64_t parallel_calls, parallel_bytes, serial_calls, workers;
static _Atomic int damage_enabled = 1;
static _Atomic uint64_t damage_frames, damage_identical, damage_partial, damage_input, damage_copied;
static _Atomic uint64_t damage_multi, damage_regions;

void aura_host_copy_enable(int value) { atomic_store(&enabled, !!value); }
void aura_host_copy_counts(uint64_t values[4]) {
    values[0] = atomic_load(&parallel_calls); values[1] = atomic_load(&parallel_bytes);
    values[2] = atomic_load(&serial_calls); values[3] = atomic_load(&workers);
}
void aura_host_copy(void *dst, const void *src, size_t bytes) {
    uintptr_t d = (uintptr_t)dst, s = (uintptr_t)src;
    /* Drawable CopyArea can alias itself. Preserve pixels in that case instead
     * of handing overlapping rectangles to parallel memcpy workers. */
    if ((d <= s && s - d < bytes) || (s < d && d - s < bytes)) {
        memmove(dst, src, bytes);
        return;
    }
    if (bytes < AURA_FRAME_COPY_MIN_BYTES) { memcpy(dst, src, bytes); return; }
    unsigned helpers = 0;
    if (atomic_load(&enabled) && aura_frame_copy_init()) helpers = aura_frame_copy(dst, src, bytes, memcpy);
    else memcpy(dst, src, bytes);
    if (helpers) {
        atomic_fetch_add(&parallel_calls, 1); atomic_fetch_add(&parallel_bytes, bytes);
        atomic_store(&workers, helpers);
    } else atomic_fetch_add(&serial_calls, 1);
}

void aura_host_damage_enable(int value) { atomic_store(&damage_enabled, !!value); }
void aura_host_damage_counts(uint64_t values[7]) {
    values[0] = atomic_load(&damage_frames); values[1] = atomic_load(&damage_identical);
    values[2] = atomic_load(&damage_partial); values[3] = atomic_load(&damage_input);
    values[4] = atomic_load(&damage_copied);
    values[5] = atomic_load(&damage_multi); values[6] = atomic_load(&damage_regions);
}

int aura_host_copy_changed_regions(void *dst, const void *src, size_t row_bytes, size_t rows,
                                  uint64_t regions[AURA_HOST_DAMAGE_REGIONS]) {
    if (!atomic_load(&damage_enabled) || !dst || !src || !regions || !row_bytes || !rows ||
            rows > INT32_MAX || row_bytes > AURA_FRAME_COPY_MAX_BYTES / rows) return -1;
    const size_t bytes = row_bytes * rows;
    uintptr_t d = (uintptr_t)dst, s = (uintptr_t)src;
    if (bytes < AURA_FRAME_COPY_MIN_BYTES || d > UINTPTR_MAX - bytes || s > UINTPTR_MAX - bytes ||
            (d <= s && s - d < bytes) || (s < d && d - s < bytes)) return -1;
    const unsigned char *input = src;
    unsigned char *output = dst;
    size_t first = 0, end = rows;
    /* Preserve exact edge trimming, then bound interior comparisons to 16
     * bands. Every omitted byte is compared; no hashes or sampled pixels.
     * A moving whole screen quickly fails memcmp in each band and still uses
     * one contiguous copy. Sparse changes can leave static middle bands alone. */
    while (first < rows && !memcmp(output + first * row_bytes, input + first * row_bytes, row_bytes)) ++first;
    unsigned count = 0;
    if (first < rows) {
        while (end > first + 1 && !memcmp(output + (end - 1) * row_bytes, input + (end - 1) * row_bytes, row_bytes)) --end;
        const size_t band_rows = (end - first + AURA_HOST_DAMAGE_BANDS - 1) / AURA_HOST_DAMAGE_BANDS;
        for (size_t top = first; top < end; top += band_rows) {
            size_t bottom = top + band_rows;
            if (bottom > end) bottom = end;
            /* First/last bands contain a row already proved different. */
            if (top != first && bottom != end &&
                    !memcmp(output + top * row_bytes, input + top * row_bytes, (bottom - top) * row_bytes)) continue;
            if (count && (uint32_t)regions[count - 1] == top) {
                regions[count - 1] = (regions[count - 1] & UINT64_C(0xffffffff00000000)) | bottom;
            } else if (count < AURA_HOST_DAMAGE_REGIONS) {
                regions[count++] = ((uint64_t)top << 32) | bottom;
            } else {
                /* Bound copy/upload calls; the prior one-region policy is
                 * the fallback, including regions not compared yet. */
                regions[0] = ((uint64_t)first << 32) | end;
                count = 1;
                break;
            }
        }
    }
    size_t copied = 0;
    for (unsigned i = 0; i < count; ++i) {
        size_t top = regions[i] >> 32, bottom = (uint32_t)regions[i];
        size_t length = (bottom - top) * row_bytes;
        aura_host_copy(output + top * row_bytes, input + top * row_bytes, length);
        copied += length;
    }
    atomic_fetch_add(&damage_frames, 1);
    atomic_fetch_add(&damage_input, bytes);
    atomic_fetch_add(&damage_copied, copied);
    if (!copied) atomic_fetch_add(&damage_identical, 1);
    else if (copied < bytes) atomic_fetch_add(&damage_partial, 1);
    if (count > 1) atomic_fetch_add(&damage_multi, 1);
    atomic_fetch_add(&damage_regions, count);
    return (int)count;
}

__attribute__((destructor)) static void finish_host_copy(void) { aura_frame_copy_shutdown(); }
