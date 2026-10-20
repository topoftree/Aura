/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT */
#include "wine_sync_probe.h"
#include <string.h>

struct sync_signature { uint64_t offset, fingerprint; unsigned bytes; };
/* Hashes only: no proprietary game code is embedded. Verified against the pinned
 * 84eea8fd EXE and ee8b621d native ntdll. Fixed base/layout, fail closed on drift.
 * The critical-section address is a repeated historical log candidate, NOT an
 * inferred lock held/waited on by this sample or a general heap scanner. */
static const struct sync_signature sync_game_signatures[] = {
    {0x198, UINT64_C(0x44b4f28dc6cf9ff7), 64},
    {0x1000, UINT64_C(0x2dd7bb66c70ce39b), 32},
    {0x1020, UINT64_C(0x61bb34f94b55ea2d), 32}
};
static const struct sync_signature sync_wine_signatures[] = {
    {0, UINT64_C(0x42f04ee13f71da25), 512},
    {0xf60a4, UINT64_C(0x2978f33234e9e89d), 128},
    {0xf61e0, UINT64_C(0xe7a36f49d2385b4d), 128}
};
/* The shipped 0.1.95 x86 PE ntdll. Normalize only its known ImageBase field and
 * HIGHLOW relocations, verified from that file's relocation table. All remaining
 * bytes must match; this does not accept arbitrary patched/other DLLs. */
static const struct sync_signature sync_queue_signatures[] = {
    {0, UINT64_C(0xa61dcb5e2ac9a9ac), 512},
    {0x53470, UINT64_C(0xc9332da76453ef0d), 128},
    {0x52f70, UINT64_C(0x221fe90aed870e8d), 128}
};

static uint64_t sync_hash(const unsigned char *data, unsigned size) {
    uint64_t hash = UINT64_C(0xcbf29ce484222325);
    for (unsigned i = 0; i < size; i++) hash = (hash ^ data[i]) * UINT64_C(0x100000001b3);
    return hash;
}

static unsigned sync_match(uint64_t base, const struct sync_signature *signatures,
                            aura_ec_read read_memory, void *opaque) {
    unsigned char data[512];
    for (unsigned i = 0; i < 3; i++) {
        const struct sync_signature *s = &signatures[i];
        if (base > UINT64_MAX - s->offset - s->bytes) return VA_LAYOUT;
        if (!read_memory(opaque, base + s->offset, data, s->bytes)) return VA_UNREADABLE;
        if (sync_hash(data, s->bytes) != s->fingerprint) return VA_LAYOUT;
    }
    return VA_CAPTURED;
}

static unsigned sync_lock_values(aura_ec_read read_memory, void *opaque, uint32_t out[6]) {
    const uint64_t address = UINT64_C(0x05ad8c6c);
    uint32_t values[2][6];
    if (!read_memory(opaque, address, values[0], 24) ||
        !read_memory(opaque, address, values[1], 24)) return VA_UNREADABLE;
    if (memcmp(values[0], values[1], 24)) return VA_CHANGED;
    // Loose structural limits reject obvious reuse; valid-looking data still is only a candidate.
    if ((int32_t)values[0][1] < -1 || (values[0][1] > 65536u && values[0][1] != UINT32_MAX) ||
        values[0][2] > 65536u || (values[0][3] & 3)) return VA_INVALID;
    uint32_t debug = values[0][0];
    if (debug && debug != UINT32_MAX) {
        uint32_t header[2];
        if ((debug & 3) || !read_memory(opaque, debug, header, 8)) return VA_UNREADABLE;
        if (header[1] != address) return VA_INVALID;
    }
    memcpy(out, values[0], 24);
    return VA_CAPTURED;
}

void aura_sync_lock(aura_ec_read read_memory, void *opaque, struct aura_sync_snapshot *out) {
    memset(out->lock, 0, sizeof(out->lock));
    out->lock_state = sync_match(UINT64_C(0x400000), sync_game_signatures, read_memory, opaque);
    if (out->lock_state == VA_CAPTURED) out->lock_state = sync_lock_values(read_memory, opaque, out->lock);
}

static unsigned sync_wait_values(uint64_t futex, uint64_t timeout, aura_ec_read read_memory,
                                 void *opaque, struct aura_sync_snapshot *out) {
    uint32_t words[2][2];
    int64_t times[2] = {0};
    if (!futex || (futex & 3) || (timeout & 7)) return VA_INVALID;
    for (unsigned i = 0; i < 2; i++) {
        // Eight-byte read permits the existing bounded PEEKDATA fallback. Export only the DWORD flag.
        if (!read_memory(opaque, futex, words[i], 8) ||
            (timeout && !read_memory(opaque, timeout, &times[i], 8))) return VA_UNREADABLE;
    }
    if (words[0][0] != words[1][0] || times[0] != times[1]) return VA_CHANGED;
    if (words[0][0] > 1) return VA_INVALID;
    out->futex = futex; out->flag = words[0][0]; out->timeout = times[0]; out->timed = timeout != 0;
    return VA_CAPTURED;
}

void aura_sync_wait(uint64_t base, uint64_t lr, uint64_t syscall_number, uint64_t futex,
                    uint64_t timeout, aura_ec_read read_memory, void *opaque, struct aura_sync_snapshot *out) {
    out->wait_state = VA_NOT_APPLICABLE;
    out->futex = 0; out->flag = 0; out->timeout = 0; out->timed = 0;
    if (!base || lr < base || syscall_number != 98) return;
    uint64_t offset = lr - base;
    if (offset != 0xf6224 && offset != 0xf625c) return;
    if ((offset == 0xf6224) != (timeout != 0)) { out->wait_state = VA_LAYOUT; return; }
    out->wait_state = sync_match(base, sync_wine_signatures, read_memory, opaque);
    if (out->wait_state == VA_CAPTURED)
        out->wait_state = sync_wait_values(futex, timeout, read_memory, opaque, out);
}

enum { SYNC_QUEUE_MAX = 8 };
struct sync_queue_node { uint32_t next, prev, addr, tid; };
struct sync_queue_pass {
    uint32_t head[3], count, addresses[SYNC_QUEUE_MAX];
    struct sync_queue_node nodes[SYNC_QUEUE_MAX];
};
/* futex_queues + (((0x05ad8c6c + offsetof(LockSemaphore)) >> 4) % 256) * 12.
 * Verified against the pinned PE symbol table and wait/wake machine code. */
#define SYNC_QUEUE_CANDIDATE UINT32_C(0x05ad8c7c)
#define SYNC_PE_PREFERRED UINT32_C(0x7bc00000)
#define SYNC_QUEUE_RVA UINT32_C(0x89abc)
#define SYNC_LOADER_PTR_RVA UINT64_C(0x1f67b8)
#define SYNC_ALERT_BLOCKS_RVA UINT64_C(0x217990)

const char *aura_sync_queue_stage_name(unsigned stage) {
    switch (stage) {
        case SYNC_STAGE_NONE: return "NOT_REQUESTED";
        case SYNC_STAGE_LOADER: return "LOADER_IDENTITY";
        case SYNC_STAGE_PE_HEADER: return "PE_HEADER";
        case SYNC_STAGE_PE_WAIT: return "PE_WAIT_CODE";
        case SYNC_STAGE_PE_WAKE: return "PE_WAKE_CODE";
        case SYNC_STAGE_ALERT_BLOCK: return "ALERT_BLOCK";
        case SYNC_STAGE_QUEUE: return "QUEUE_NODES";
        case SYNC_STAGE_RECHECK: return "IDENTITY_RECHECK";
        case SYNC_STAGE_COMPLETE: return "COMPLETE";
        default: return "UNKNOWN";
    }
}

/* Wine's load_ntdll_wow64_functions records the actual x86 module in the
 * native LdrSystemDllInitBlock, including when the preferred base is occupied.
 * Only fixed fields of the pinned layout are read; no address-space scan. */
static unsigned sync_pe_base(uint64_t native_base, aura_ec_read read_memory, void *opaque,
                             uint32_t *base) {
    uint64_t pointers[2], blocks[2][12];
    if (native_base > UINT64_MAX - SYNC_LOADER_PTR_RVA - 8) return VA_LAYOUT;
    for (unsigned i = 0; i < 2; i++) {
        if (!read_memory(opaque, native_base + SYNC_LOADER_PTR_RVA, &pointers[i], 8))
            return VA_UNREADABLE;
        if (pointers[i] < 0x10000 || (pointers[i] & 7) || pointers[i] > UINT64_MAX - 96)
            return VA_LAYOUT;
        if (!read_memory(opaque, pointers[i], blocks[i], sizeof(blocks[i]))) return VA_UNREADABLE;
    }
    if (pointers[0] != pointers[1] || memcmp(blocks[0], blocks[1], sizeof(blocks[0]))) return VA_CHANGED;
    uint64_t pe = blocks[0][9]; /* offsetof(SYSTEM_DLL_INIT_BLOCK, ntdll_handle) == 0x48 */
    if ((uint32_t)blocks[0][0] != 0xf0 || pe < 0x10000 || (pe & 0xffff) ||
        pe > UINT32_MAX - 0x9c000 || blocks[0][10] != pe + 0x87170) return VA_LAYOUT;
    *base = (uint32_t)pe;
    return VA_CAPTURED;
}

static unsigned sync_pe_match(uint32_t base, aura_ec_read read_memory, void *opaque,
                              struct aura_sync_snapshot *out) {
    static const unsigned short relocations[2][6] = {
        {0x16, 0x2e, 0x3f, 0x48, 0x6e, 0x76},
        {0x0f, 0x1e, 0x26, 0x4c}
    };
    const unsigned counts[] = {6, 4};
    uint32_t delta = base - SYNC_PE_PREFERRED;
    unsigned char data[512];
    for (unsigned i = 0; i < 3; i++) {
        const struct sync_signature *s = &sync_queue_signatures[i];
        out->queue_stage = SYNC_STAGE_PE_HEADER + i;
        out->pe_fingerprint = 0;
        if (!read_memory(opaque, (uint64_t)base + s->offset, data, s->bytes)) return VA_UNREADABLE;
        if (!i) {
            uint32_t image_base;
            memcpy(&image_base, data + 0xac, 4);
            // Wine can update ImageBase for dynamic rebasing, or leave the
            // preferred value when virtual_relocate_module applies the delta.
            if (image_base != base && image_base != SYNC_PE_PREFERRED) return VA_LAYOUT;
            image_base = SYNC_PE_PREFERRED;
            memcpy(data + 0xac, &image_base, 4);
        } else {
            for (unsigned j = 0; j < counts[i - 1]; j++) {
                uint32_t value;
                memcpy(&value, data + relocations[i - 1][j], 4);
                value -= delta;
                memcpy(data + relocations[i - 1][j], &value, 4);
            }
        }
        out->pe_fingerprint = sync_hash(data, s->bytes);
        if (out->pe_fingerprint != s->fingerprint) return VA_LAYOUT;
    }
    return VA_CAPTURED;
}

static int sync_queue_pointer(uint32_t p) { return p >= 0x10000 && p <= UINT32_MAX - 15 && !(p & 3); }

static unsigned sync_queue_read(uint32_t head, aura_ec_read read_memory, void *opaque,
                                struct sync_queue_pass *out) {
    memset(out, 0, sizeof(*out));
    if (!read_memory(opaque, head, out->head, sizeof(out->head))) return VA_UNREADABLE;
    if (out->head[2] == UINT32_MAX) return VA_CHANGED; /* Waker/waiter is editing this bucket. */
    if (out->head[2]) return VA_INVALID;
    uint32_t at = out->head[0], previous = head;
    if (!at && !out->head[1]) goto check_head; /* Stable never-used bucket. */
    if (!sync_queue_pointer(at) || !sync_queue_pointer(out->head[1])) return VA_INVALID;
    while (at != head) {
        if (out->count == SYNC_QUEUE_MAX) return VA_LIMIT;
        for (unsigned i = 0; i < out->count; i++) if (out->addresses[i] == at) return VA_INVALID;
        struct sync_queue_node *node = &out->nodes[out->count];
        if (!read_memory(opaque, at, node, sizeof(*node))) return VA_UNREADABLE;
        if (node->prev != previous || !node->addr) return VA_CHANGED;
        if (!sync_queue_pointer(node->next) || !node->tid || (node->tid & 3)) return VA_INVALID;
        out->addresses[out->count++] = at;
        previous = at; at = node->next;
    }
    if (out->head[1] != previous) return VA_CHANGED;
check_head:;
    uint32_t after[3];
    if (!read_memory(opaque, head, after, sizeof(after))) return VA_UNREADABLE;
    return memcmp(after, out->head, sizeof(after)) ? VA_CHANGED : VA_CAPTURED;
}

void aura_sync_queue(uint64_t native_base, aura_ec_read read_memory, void *opaque,
                      struct aura_sync_snapshot *out) {
    out->queue_state = VA_NOT_APPLICABLE;
    out->waiter_wine_tid = out->queue_nodes = out->candidate_nodes = 0;
    out->selected_candidate = out->selected_other = 0;
    out->queue_stage = SYNC_STAGE_NONE;
    out->pe_base = 0;
    out->pe_fingerprint = 0;
    memset(out->candidate_tids, 0, sizeof(out->candidate_tids));
    if (out->lock_state != VA_CAPTURED || out->wait_state != VA_CAPTURED || !native_base) return;
    out->queue_stage = SYNC_STAGE_LOADER;
    uint32_t base = 0;
    out->queue_state = sync_pe_base(native_base, read_memory, opaque, &base);
    if (out->queue_state != VA_CAPTURED) return;
    out->pe_base = base;
    out->queue_state = sync_pe_match(base, read_memory, opaque, out);
    if (out->queue_state != VA_CAPTURED) return;
    out->queue_stage = SYNC_STAGE_ALERT_BLOCK;
    if (native_base > UINT64_MAX - SYNC_ALERT_BLOCKS_RVA - 8) { out->queue_state = VA_LAYOUT; return; }
    // NtWaitForAlertByThreadId's validated futex belongs to a Wine TID, not a
    // Linux TID. Only the pinned native DLL's first 64 KiB alert block is read.
    uint64_t blocks[2];
    for (unsigned i = 0; i < 2; i++)
        if (!read_memory(opaque, native_base + SYNC_ALERT_BLOCKS_RVA, &blocks[i], 8)) {
            out->queue_state = VA_UNREADABLE; return;
        }
    if (blocks[0] != blocks[1]) { out->queue_state = VA_CHANGED; return; }
    uint64_t block = blocks[0];
    if (!block || (block & 4095) || out->futex < block || out->futex - block >= 65536 ||
        ((out->futex - block) & 3)) { out->queue_state = VA_LAYOUT; return; }
    uint32_t tid = (uint32_t)(out->futex - block) + 4;
    struct sync_queue_pass passes[2];
    out->queue_stage = SYNC_STAGE_QUEUE;
    for (unsigned i = 0; i < 2; i++) {
        out->queue_state = sync_queue_read(base + SYNC_QUEUE_RVA, read_memory, opaque, &passes[i]);
        if (out->queue_state != VA_CAPTURED) return;
    }
    if (memcmp(&passes[0], &passes[1], sizeof(passes[0]))) { out->queue_state = VA_CHANGED; return; }
    out->queue_stage = SYNC_STAGE_RECHECK;
    uint32_t base_after = 0;
    out->queue_state = sync_pe_base(native_base, read_memory, opaque, &base_after);
    if (out->queue_state != VA_CAPTURED) return;
    uint64_t block_after;
    if (!read_memory(opaque, native_base + SYNC_ALERT_BLOCKS_RVA, &block_after, 8)) {
        out->queue_state = VA_UNREADABLE; return;
    }
    if (base_after != base || block_after != block) { out->queue_state = VA_CHANGED; return; }
    uint32_t lock[6];
    out->queue_state = sync_lock_values(read_memory, opaque, lock);
    if (out->queue_state != VA_CAPTURED) return;
    if (memcmp(lock, out->lock, sizeof(lock))) { out->queue_state = VA_CHANGED; return; }
    out->queue_stage = SYNC_STAGE_COMPLETE;
    out->waiter_wine_tid = tid;
    out->queue_nodes = passes[0].count;
    for (unsigned i = 0; i < passes[0].count; i++) {
        const struct sync_queue_node *node = &passes[0].nodes[i];
        if (node->addr == SYNC_QUEUE_CANDIDATE) {
            out->candidate_tids[out->candidate_nodes++] = node->tid;
            if (node->tid == tid) out->selected_candidate++;
        } else if (node->tid == tid) out->selected_other++;
    }
    // The two reads are not atomic: other threads keep running, ABA and an
    // already-removed wake still in flight remain possible. No cause is inferred.
}

static unsigned sync_frame_code(uint32_t base, aura_ec_read read_memory, void *opaque) {
    /* Pinned RtlpWaitForCriticalSection prologue, its wait_semaphore call site,
     * and RtlWaitOnAddress's node setup/syscall site. Verified by executing the
     * real PE instructions in a host emulator for both caller stack alignments.
     * HIGHLOW offsets come from the PE relocation directory, not guessed bytes. */
    static const struct sync_signature signatures[] = {
        {0x52a60, UINT64_C(0xd2c0b40a0b50a9ff), 24},
        {0x52acf, UINT64_C(0xecc7f2fab0d5d589), 117},
        {0x53517, UINT64_C(0xad7af7da5b77d7b0), 171}
    };
    static const unsigned short relocations[3][2] = {{0, 0}, {0x6a, 0}, {0xe, 0x3f}};
    const unsigned counts[] = {0, 1, 2};
    unsigned char data[192];
    for (unsigned i = 0; i < 3; i++) {
        const struct sync_signature *s = &signatures[i];
        if (!read_memory(opaque, (uint64_t)base + s->offset, data, s->bytes)) return VA_UNREADABLE;
        for (unsigned j = 0; j < counts[i]; j++) {
            uint32_t value;
            memcpy(&value, data + relocations[i][j], 4);
            value -= base - SYNC_PE_PREFERRED;
            memcpy(data + relocations[i][j], &value, 4);
        }
        if (sync_hash(data, s->bytes) != s->fingerprint) return VA_LAYOUT;
    }
    return VA_CAPTURED;
}

void aura_sync_frame(uint64_t timeout_pointer, aura_ec_read read_memory, void *opaque,
                      struct aura_sync_snapshot *out) {
    out->frame_state = VA_NOT_APPLICABLE;
    out->frame_critical = out->frame_node_address = out->frame_node_tid = 0;
    if (out->queue_state != VA_CAPTURED || out->wait_state != VA_CAPTURED || !out->timed ||
        !timeout_pointer || timeout_pointer > UINT32_MAX || (timeout_pointer & 7)) return;
    if (timeout_pointer < 0x10040 || timeout_pointer > UINT32_MAX - 0x150 ||
        out->pe_base < 0x10000 || out->pe_base > UINT32_MAX - 0x9c000 ||
        !out->waiter_wine_tid || (out->waiter_wine_tid & 3)) {
        out->frame_state = VA_INVALID; return;
    }
    out->frame_state = sync_frame_code(out->pe_base, read_memory, opaque);
    if (out->frame_state != VA_CAPTURED) return;

    /* time = caller ESP + 8. Four arguments, return address and saved EBP put
     * the callee EBP at time - 32. Its 16-byte list node starts at EBP - 28.
     * This relationship is specific to the fingerprinted PE, not a generic ABI. */
    uint32_t frames[2][17], outers[2][3];
    uint32_t critical = 0;
    for (unsigned i = 0; i < 2; i++) {
        if (!read_memory(opaque, timeout_pointer - 60, frames[i], sizeof(frames[i]))) {
            out->frame_state = VA_UNREADABLE; return;
        }
        const uint32_t *f = frames[i];
        uint64_t outer = f[7];
        int64_t timeout;
        memcpy(&timeout, f + 15, 8);
        if ((outer != timeout_pointer + 0x13c && outer != timeout_pointer + 0x140) ||
            f[8] != out->pe_base + 0x52b43 || f[9] < 0x10010 || (f[9] & 3) ||
            f[10] != out->pe_base + 0x74e04 || f[11] != 4 || f[12] != timeout_pointer) {
            out->frame_state = VA_NOT_APPLICABLE; return;
        }
        if (timeout != out->timeout) { out->frame_state = VA_CHANGED; return; }
        if (!sync_queue_pointer(f[0]) || !sync_queue_pointer(f[1]) ||
            (f[2] && f[2] != f[9]) || f[3] != out->waiter_wine_tid) {
            out->frame_state = VA_INVALID; return;
        }
        critical = f[9] - 16;
        if (!read_memory(opaque, outer, outers[i], sizeof(outers[i]))) {
            out->frame_state = VA_UNREADABLE; return;
        }
        if (outers[i][2] != critical) { out->frame_state = VA_INVALID; return; }
    }
    if (memcmp(frames[0], frames[1], sizeof(frames[0])) ||
        memcmp(outers[0], outers[1], sizeof(outers[0]))) {
        out->frame_state = VA_CHANGED; return;
    }
    out->frame_state = VA_CAPTURED;
    out->frame_critical = critical;
    out->frame_node_address = frames[0][2];
    out->frame_node_tid = frames[0][3];
    /* addr == NULL means the waker published removal; it says nothing about
     * alert delivery/completion. A non-NULL addr does not prove membership.
     * The selected thread is stopped, other threads can still edit its node. */
}
