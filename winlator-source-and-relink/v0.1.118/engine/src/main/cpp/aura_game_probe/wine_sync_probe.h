/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT */
#ifndef AURA_WINE_SYNC_PROBE_H
#define AURA_WINE_SYNC_PROBE_H
#include "address_space_probe.h"
/* Bounded scalar observations, no raw registers/stack, writes, or target calls. */
struct aura_sync_snapshot {
    uint32_t lock_state, wait_state, lock[6], flag, timed;
    uint64_t futex;
    int64_t timeout;
    uint32_t queue_state, waiter_wine_tid, queue_nodes, candidate_nodes;
    uint32_t selected_candidate, selected_other, candidate_tids[8];
    uint32_t queue_stage, pe_base;
    uint64_t pe_fingerprint;
    uint32_t frame_state, frame_critical, frame_node_address, frame_node_tid;
};
enum aura_sync_queue_stage {
    SYNC_STAGE_NONE, SYNC_STAGE_LOADER, SYNC_STAGE_PE_HEADER, SYNC_STAGE_PE_WAIT,
    SYNC_STAGE_PE_WAKE, SYNC_STAGE_ALERT_BLOCK, SYNC_STAGE_QUEUE, SYNC_STAGE_RECHECK,
    SYNC_STAGE_COMPLETE
};
const char *aura_sync_queue_stage_name(unsigned stage);
void aura_sync_lock(aura_ec_read read_memory, void *, struct aura_sync_snapshot *);
void aura_sync_wait(uint64_t base, uint64_t lr, uint64_t syscall_number, uint64_t futex,
                    uint64_t timeout, aura_ec_read read_memory, void *, struct aura_sync_snapshot *);
/* First point of an existing bounded sample batch only. Never acquires a target
 * lock, writes memory, wakes a thread or extends the observer deadline. */
void aura_sync_queue(uint64_t native_base, aura_ec_read read_memory, void *, struct aura_sync_snapshot *);
/* Only after a successful first-point queue observation. Reads the fixed x86
 * caller layout relative to the validated native timeout argument, not a stack
 * scan/unwind. Does not export the timeout pointer or raw frame contents. */
void aura_sync_frame(uint64_t timeout_pointer, aura_ec_read read_memory, void *, struct aura_sync_snapshot *);
#endif
