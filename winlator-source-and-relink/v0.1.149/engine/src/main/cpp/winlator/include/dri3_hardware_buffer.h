/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT */
#ifndef AURA_DRI3_HARDWARE_BUFFER_H
#define AURA_DRI3_HARDWARE_BUFFER_H
#include <android/hardware_buffer.h>
#include <stdint.h>

/* Modifier 1255 carries a socket, not a memory-mappable dma-buf. The caller
 * owns fd; a successful receive transfers exactly one AHB reference to it. */
AHardwareBuffer *aura_dri3_receive_hardware_buffer(int fd, uint32_t width, uint32_t height);
void aura_dri3_abort_hardware_buffer(int fd);
#endif
