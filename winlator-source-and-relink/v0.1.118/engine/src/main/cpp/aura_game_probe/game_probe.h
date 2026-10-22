/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT */
#ifndef AURA_GAME_PROBE_H
#define AURA_GAME_PROBE_H
#include <stdint.h>
#include <sys/types.h>
#include "wine_alloc_probe.h"
#include "address_space_probe.h"
#include "wine_sync_probe.h"

#define AURA_PC_SAMPLES 3
#define AURA_PC_NAME 96
#define AURA_PC_TARGET 256
#define AURA_PC_TEXT 1380 /* Includes NUL; leave room for GAME_NATIVE_PC_V1 in the 1400-char report field. */
#define AURA_PC_DETAIL 24576
#define AURA_PC_REFS 16
/* Filtered executable references, not an unwound call chain. No raw stack/register dump. */
struct aura_code_ref {
    uint64_t address, map_start, map_offset;
    uint16_t source, index, module, reserved;
};

enum aura_pc_register_state { PC_REG_UNSUPPORTED, PC_REG_UNMAPPED, PC_REG_NON_EXEC, PC_REG_CODE };
struct aura_pc_register {
    uint64_t value, map_offset;
    uint32_t state;
    char module[AURA_PC_NAME];
};

enum aura_pc_status {
    PC_OK, PC_DENIED, PC_IDENTITY, PC_TARGET_CHANGED, PC_GONE, PC_STOPPED,
    PC_TIMEOUT, PC_SIGNAL, PC_UNSUPPORTED, PC_IO, PC_INTERNAL, PC_RECOVERY_PENDING, PC_BUSY, PC_STATE
};
struct aura_pc_sample {
    uint64_t pc, map_start, map_offset, map_end, guest_pc, entry_return;
    uint32_t elapsed_ms, status, mapped, executable, readable, guest_state, entry_state;
    char module[AURA_PC_NAME];
    /* Native register clues only, never a reconstructed stack.
     * Values leave the child only when the current map is executable. */
    struct aura_pc_register lr, x11;
    struct aura_alloc_result allocation;
    uint64_t code_start;
    uint32_t code_size, stack_bytes, context_state, refs_count;
    unsigned char code[64];
    struct aura_code_ref refs[AURA_PC_REFS];
    struct aura_sync_snapshot sync;
};
struct aura_pc_result {
    uint32_t count, status;
    uint32_t worker;
    struct aura_pc_sample samples[AURA_PC_SAMPLES];
    struct aura_va_snapshot address_space;
};

/* Explicit, one-shot Linux leader or worker sampling. No target memory/register writes.
 * The caller must first verify its own current session and selected executable.
 * A disposable tracer owns the attachment; its exit automatically detaches it.
 * Never use PTRACE_O_EXITKILL. Parent and child have independent wall time limits.
 */
void aura_pc_collect(pid_t pid, uint64_t start_ticks, const char *exe,
                     struct aura_pc_result *result);
void aura_pc_collect_detailed(pid_t pid, uint64_t start_ticks, const char *exe,
                              struct aura_pc_result *result);
void aura_pc_collect_thread(pid_t pid, uint64_t start_ticks, pid_t tid, uint64_t thread_start,
                            const char *exe, struct aura_pc_result *result);
const char *aura_pc_status_name(unsigned status);
/* Parent-side formatting, shared with native tests. Never emit a truncated sample. */
void aura_pc_format(const struct aura_pc_result *result, char text[AURA_PC_TEXT]);
void aura_pc_format_detail(const struct aura_pc_result *result, char text[AURA_PC_DETAIL]);
#endif
