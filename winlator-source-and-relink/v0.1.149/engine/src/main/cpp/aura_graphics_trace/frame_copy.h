/* Aura integration changes for 0.1.149, 2026-10-27.
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
/* Large enough for libc's streaming loop, small enough to balance unlike cores. */
#define AURA_FRAME_COPY_CHUNK_BYTES (256u * 1024u)
#define AURA_FRAME_REGIONS_VERSION 2u
#define AURA_FRAME_REGIONS_BLOCK_BYTES 64u
#define AURA_FRAME_REGIONS_DENSE_BYTES 4096u
typedef void *(*aura_copy_fn)(void *, const void *, size_t);
struct aura_frame_copy_work {
    size_t chunks, caller_bytes, helper_bytes;
    size_t compared_bytes, written_bytes;
};

/* Install fork handling before allowing jobs. Failure keeps the serial path. */
int aura_frame_copy_init(void);
/* Always completes all bytes before returning. Returns the helper pool size,
 * or zero for the original serial copy. No allocation per frame. */
unsigned aura_frame_copy(void *dst, const void *src, size_t bytes, aura_copy_fn copy);
/* Optional counts describe completed work, not time or CPU utilization. */
unsigned aura_frame_copy_measured(void *dst, const void *src, size_t bytes, aura_copy_fn copy,
                                 struct aura_frame_copy_work *work);
/* Only for a verified readable SHM destination. Equality is checked in full;
 * no previous address/content cache is retained. Other callers use the API above. */
unsigned aura_frame_copy_regions(void *dst, const void *src, size_t bytes, aura_copy_fn copy,
                                 struct aura_frame_copy_work *work);
void aura_frame_copy_shutdown(void);
#endif
