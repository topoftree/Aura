/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT
 * Aura local AHB presentation v1. Native little endian, same Android UID only.
 * A PRESENT reply releases the PREVIOUS slot, never the newly displayed slot.
 */
#ifndef AURA_SHARED_GPU_PROTOCOL_H
#define AURA_SHARED_GPU_PROTOCOL_H
#include <stdint.h>
#define AURA_GPU_MAGIC UINT32_C(0x41554731)
#define AURA_GPU_MAX_PIXELS UINT32_C(8847360)
struct aura_gpu_hello { uint32_t magic, window, width, height; };
struct aura_gpu_present { uint32_t magic, slot, serial; };
static inline int aura_gpu_dimensions(uint32_t w, uint32_t h) {
    return w > 0 && h > 0 && w <= 8192 && h <= 8192 &&
           (uint64_t)w * h <= AURA_GPU_MAX_PIXELS;
}
#endif
