/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT */
#ifndef AURA_HOST_FRAME_COPY_H
#define AURA_HOST_FRAME_COPY_H
#include <stddef.h>
#include <stdint.h>
void aura_host_copy(void *dst, const void *src, size_t bytes);
void aura_host_copy_enable(int enabled);
void aura_host_copy_counts(uint64_t values[4]);
void aura_host_damage_enable(int enabled);
#define AURA_HOST_DAMAGE_REGIONS 4
#define AURA_HOST_DAMAGE_BANDS 16
/* Full tightly packed SHM surfaces only. -1 means use the original copy;
 * zero means identical. Each output is first row << 32 | exclusive end.
 * All copies complete before return. The caller owns the fixed output array. */
int aura_host_copy_changed_regions(void *dst, const void *src, size_t row_bytes, size_t rows,
                                  uint64_t regions[AURA_HOST_DAMAGE_REGIONS]);
void aura_host_damage_counts(uint64_t values[7]);
#endif
