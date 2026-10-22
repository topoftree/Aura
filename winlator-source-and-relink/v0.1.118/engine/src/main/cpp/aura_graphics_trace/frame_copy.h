/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT */
#ifndef AURA_FRAME_COPY_H
#define AURA_FRAME_COPY_H
#include <stddef.h>

#define AURA_FRAME_COPY_MIN_BYTES (8u * 1024u * 1024u)
#define AURA_FRAME_COPY_MAX_BYTES (256u * 1024u * 1024u)
#define AURA_FRAME_COPY_WORKERS 2u
typedef void *(*aura_copy_fn)(void *, const void *, size_t);

/* Install fork handling before allowing jobs. Failure keeps the serial path. */
int aura_frame_copy_init(void);
/* Always completes all bytes before returning. Returns the number of helpers
 * actually used, or zero for the original serial copy. No allocation per frame. */
unsigned aura_frame_copy(void *dst, const void *src, size_t bytes, aura_copy_fn copy);
void aura_frame_copy_shutdown(void);
#endif
