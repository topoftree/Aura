/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT
 * This observer runs only after an explicit in-game diagnostic request.
 * The post-fork child uses fixed buffers and syscall/async-signal-safe operations:
 * no ART, JNI, malloc, stdio, logging, dladdr, Wine APIs or application callbacks.
 */
#define _GNU_SOURCE
#include "game_probe.h"
#include "fex_guest_pc.h"
#include "ec_entry_return.h"
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#if defined(__aarch64__)
#include <asm/ptrace.h>
#elif defined(__x86_64__)
#include <sys/user.h>
#endif

#define PC_DEADLINE_MS 1500
#define PC_STOP_MS 150
#define PC_INTERVAL_MS 150
#define PC_MAP_LIMIT (1024 * 1024)
#define NT_PRSTATUS_VALUE 1

_Static_assert(sizeof(struct aura_pc_result) <= PIPE_BUF, "Observer result must be an atomic pipe packet");

/* One outstanding tracer at most, including an exceptionally slow kernel exit.
 * Only the parent touches these. A later request reaps a pending exit before any new fork. */
static atomic_flag collecting = ATOMIC_FLAG_INIT;
static pid_t pending_tracer;

struct request {
    pid_t pid, tid, parent;
    uid_t uid;
    uint64_t start, thread_start;
    uint64_t page_size;
    int detailed;
    char stat_path[64], task_stat_path[64], cmd_path[64], maps_path[64], proc_path[64];
    char exe[AURA_PC_TARGET];
};

static uint64_t now_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts)) return 0;
    return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}

static void pause_ms(unsigned ms) {
    struct timespec ts = { ms / 1000, (long)(ms % 1000) * 1000000 };
    nanosleep(&ts, NULL);
}

static int read_small(const char *path, char *data, size_t cap) {
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return -1;
    size_t used = 0;
    while (used < cap) {
        ssize_t n = read(fd, data + used, cap - used);
        if (n == 0) break;
        if (n < 0) { close(fd); return -1; }
        used += (size_t)n;
    }
    char extra;
    ssize_t more = read(fd, &extra, 1);
    close(fd);
    return more == 0 ? (int)used : -1;
}

static int number(const char **at, uint64_t *value, unsigned base) {
    const char *p = *at;
    uint64_t n = 0;
    unsigned count = 0;
    for (;;) {
        unsigned digit = *p >= '0' && *p <= '9' ? (unsigned)(*p - '0') :
            *p >= 'a' && *p <= 'f' ? (unsigned)(*p - 'a' + 10) :
            *p >= 'A' && *p <= 'F' ? (unsigned)(*p - 'A' + 10) : UINT_MAX;
        if (digit >= base) break;
        if (n > (UINT64_MAX - digit) / base) return 0;
        n = n * base + digit; p++; count++;
    }
    if (!count) return 0;
    *at = p; *value = n; return 1;
}

static void spaces(const char **p) { while (**p == ' ' || **p == '\t') (*p)++; }

static int stat_identity(const char *text, pid_t pid, uint64_t expected, char *state) {
    const char *p = text, *close = NULL;
    uint64_t actual, start;
    if (!number(&p, &actual, 10) || actual != (uint64_t)pid) return 0;
    spaces(&p);
    if (*p != '(') return 0;
    for (const char *q = p; *q; q++) if (*q == ')') close = q;
    if (!close || close[1] != ' ' || !close[2] || close[3] != ' ') return 0;
    *state = close[2]; p = close + 4;
    /* fields 4..21, followed by starttime (22); comm may contain spaces/parentheses. */
    for (int field = 4; field < 22; field++) {
        spaces(&p);
        if (!*p) return 0;
        while (*p && *p != ' ' && *p != '\t') p++;
    }
    spaces(&p);
    return number(&p, &start, 10) && (*p == ' ' || *p == '\n') && start == expected;
}

static unsigned char lower(unsigned char c) { return c >= 'A' && c <= 'Z' ? c + ('a' - 'A') : c; }
static int same(const char *a, const char *b) {
    while (*a && *b && lower((unsigned char)*a) == lower((unsigned char)*b)) { a++; b++; }
    return !*a && !*b;
}
static const char *base_name(const char *text) {
    const char *base = text;
    for (; *text; text++) if (*text == '/' || *text == '\\') base = text + 1;
    return base;
}
static int command_matches(const char *data, int size, const char *exe) {
    if (size <= 0 || data[size - 1]) return 0;
    const char *first = base_name(data);
    if (same(first, exe)) return 1;
    if (!same(first, "wine") && !same(first, "wine64") && !same(first, "wine-preloader") &&
        !same(first, "wine64-preloader") && !same(first, "libwine.so")) return 0;
    int next = 0;
    while (next < size && data[next]) next++;
    return ++next < size && same(base_name(data + next), exe);
}

static unsigned identity(const struct request *r, int allow_trace_stop) {
    struct stat st;
    char data[16385], state;
    if (stat(r->proc_path, &st)) return errno == ENOENT ? PC_GONE : PC_DENIED;
    if (st.st_uid != r->uid) return PC_IDENTITY;
    int n = read_small(r->stat_path, data, 4096);
    if (n <= 0) return PC_IO;
    data[n] = 0;
    if (!stat_identity(data, r->pid, r->start, &state)) return PC_IDENTITY;
    if (state == 'Z' || state == 'X' || state == 'x') return PC_GONE;
    int leader_trace_stop = allow_trace_stop && r->tid == r->pid;
    if (state == 'T' || (state == 't' && !leader_trace_stop)) return PC_STOPPED;
    if (state != 'R' && state != 'S' && !(leader_trace_stop && state == 't')) return PC_STATE;
    if (r->tid != r->pid) {
        /* Reading through /proc/pid/task proves membership as well as thread identity.
         * The process leader may remain running while only this worker is stopped. */
        n = read_small(r->task_stat_path, data, 4096);
        if (n <= 0) return PC_GONE;
        data[n] = 0;
        if (!stat_identity(data, r->tid, r->thread_start, &state)) return PC_IDENTITY;
        if (state == 'Z' || state == 'X' || state == 'x') return PC_GONE;
        if (state == 'T' || (state == 't' && !allow_trace_stop)) return PC_STOPPED;
        if (state != 'R' && state != 'S' && !(allow_trace_stop && state == 't')) return PC_STATE;
    }
    n = read_small(r->cmd_path, data, 16384);
    if (n <= 0) return PC_IO;
    return command_matches(data, n, r->exe) ? PC_OK : PC_TARGET_CHANGED;
}

static int token(const char **p) {
    spaces(p);
    if (!**p) return 0;
    while (**p && **p != ' ' && **p != '\t') (*p)++;
    return 1;
}

/* Only public runtime component basenames (or a bundled content hash) leave this parser. */
static void classify(const char *path, const char *exe, struct aura_pc_sample *out) {
    const char *name = base_name(path);
    const char *label = !*path ? (out->executable ? "ANON_EXEC" : "ANON_OTHER") : "OTHER_FILE";
    if (same(name, exe)) label = "GAME_IMAGE";
    const char *known[] = { "libc.so", "libarm64ecfex.dll", "libwow64fex.dll", "libarm64ecfex.so",
        "ntdll.dll", "kernelbase.dll", "kernel32.dll", "uplay_aux_r164.dll", "dbdata.dll", "overlay64.dll" };
    for (unsigned i = 0; i < sizeof(known) / sizeof(*known); i++) if (same(name, known[i])) label = known[i];
    unsigned len = 0;
    while (name[len] && len < AURA_PC_NAME) len++;
    int hashed = len == 35;
    const char prefix[] = "libaura_";
    for (unsigned i = 0; i < 8 && hashed; i++) if (name[i] != prefix[i]) hashed = 0;
    for (unsigned i = 8; i < 32 && hashed; i++)
        if (!((name[i] >= '0' && name[i] <= '9') || (name[i] >= 'a' && name[i] <= 'f'))) hashed = 0;
    if (hashed && name[32] == '.' && name[33] == 's' && name[34] == 'o') label = name;
    if (*path == '[') label = out->executable ? "ANON_EXEC" : "ANON_OTHER";
    /* Exact public VMA labels from FEX 2610. A name is a clue, not a symbol or ownership proof. */
    if (same(path, "[anon:FEXMemJIT]")) label = "FEX_NAMED_JIT";
    if (same(path, "[anon:FEXMem_Misc]")) label = "FEX_NAMED_MISC";
    unsigned i = 0;
    while (label[i] && i + 1 < AURA_PC_NAME) { out->module[i] = label[i]; i++; }
    out->module[i] = 0;
}

static int map_line(const char *line, const char *exe, struct aura_pc_sample *out) {
    const char *p = line;
    uint64_t begin, end, offset;
    if (!number(&p, &begin, 16) || *p++ != '-' || !number(&p, &end, 16) || begin >= end) return 0;
    spaces(&p);
    if (!p[0] || !p[1] || !p[2] || !p[3] || p[4] != ' ') return 0;
    int readable = p[0] == 'r', executable = p[2] == 'x'; p += 4; spaces(&p);
    if (!number(&p, &offset, 16) || !token(&p) || !token(&p)) return 0;
    spaces(&p);
    if (out->pc < begin || out->pc >= end || offset > UINT64_MAX - (out->pc - begin)) return 0;
    out->map_start = begin; out->map_end = end; out->map_offset = offset + out->pc - begin;
    out->mapped = 1; out->executable = (uint32_t)executable;
    out->readable = (uint32_t)readable;
    classify(p, exe, out); return 1;
}

static void register_mapping(struct aura_pc_register *reg, const struct aura_pc_sample *mapping) {
    if (reg->state == PC_REG_UNSUPPORTED) return;
    if (mapping->mapped && mapping->executable) {
        reg->state = PC_REG_CODE;
        reg->map_offset = mapping->map_offset;
        memcpy(reg->module, mapping->module, sizeof(reg->module));
    } else {
        reg->state = mapping->mapped ? PC_REG_NON_EXEC : PC_REG_UNMAPPED;
        reg->value = 0; /* No argument, data pointer or non-code value leaves the child. */
    }
}

static void read_mappings(const struct request *r, struct aura_pc_sample *out,
                          struct aura_fex_view *view, struct aura_ec_view *ec) {
    struct aura_pc_sample lr = { .pc = out->lr.value }, x11 = { .pc = out->x11.value };
    struct aura_pc_sample frame = { .pc = view ? view->frame : 0 };
    struct aura_pc_sample stack = { .pc = ec ? ec->sp : 0 };
    int maps_complete = 0, maps_truncated = 0;
    int fd = open(r->maps_path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) goto finish;
    char buffer[4096], line[1024]; unsigned used = 0, total = 0; int overflow = 0;
    while (total < PC_MAP_LIMIT) {
        ssize_t n = read(fd, buffer, sizeof(buffer));
        if (n <= 0) {
            maps_complete = !n && !used && !overflow && !maps_truncated;
            break;
        }
        total += (unsigned)n;
        for (ssize_t i = 0; i < n; i++) {
            if (buffer[i] == '\n') {
                if (overflow) maps_truncated = 1;
                if (!overflow) {
                    line[used] = 0;
                    if (!out->mapped) map_line(line, r->exe, out);
                    if (out->lr.state && !lr.mapped) map_line(line, r->exe, &lr);
                    if (out->x11.state && !x11.mapped) map_line(line, r->exe, &x11);
                    if (view && !frame.mapped) map_line(line, r->exe, &frame);
                    if (ec && !stack.mapped) map_line(line, r->exe, &stack);
                    if (out->mapped && (!out->lr.state || lr.mapped) && (!out->x11.state || x11.mapped) &&
                        (!view || frame.mapped) && (!ec || stack.mapped)) goto close_maps;
                }
                used = 0; overflow = 0;
            } else if (used + 1 < sizeof(line)) line[used++] = buffer[i];
            else overflow = 1;
        }
    }
close_maps:
    close(fd);
finish:
    register_mapping(&out->lr, &lr);
    register_mapping(&out->x11, &x11);
    if (ec) {
        if (out->mapped && out->readable && out->executable) {
            ec->pc_begin = out->map_start; ec->pc_end = out->map_end;
        }
        if (lr.mapped && lr.readable && lr.executable) {
            ec->lr_begin = lr.map_start; ec->lr_end = lr.map_end;
        }
        if (stack.mapped && stack.readable && !stack.executable) {
            ec->stack_begin = stack.map_start; ec->stack_end = stack.map_end;
        }
    }
    if (view) {
        view->pc = out->pc;
        if (out->mapped && out->executable && out->readable && !strcmp(out->module, "FEX_NAMED_JIT")) {
            view->jit_begin = out->map_start; view->jit_end = out->map_end;
        }
        view->frame_flags = FEX_FRAME_CHECKED | (frame.mapped ? FEX_FRAME_MAPPED : 0) |
            (frame.readable ? FEX_FRAME_READABLE : 0) | (frame.executable ? FEX_FRAME_EXECUTABLE : 0);
        if (!frame.mapped && !maps_complete) view->frame_flags |= FEX_FRAME_MAPS_INCOMPLETE;
        /* Wine's force_exec_prot can make ordinary WOW64 data allocations RWX.
         * Read permission, alignment and the entire FEX metadata chain establish
         * this read's validity; host execute permission is not a data-role test. */
        if (frame.mapped && frame.readable) {
            view->frame_begin = frame.map_start; view->frame_end = frame.map_end;
        }
    }
}

static int trace_call(unsigned request, pid_t pid, void *addr, void *data) {
    return (int)syscall(SYS_ptrace, request, pid, addr, data);
}

struct private_context { uint64_t registers[31], sp; };

static unsigned read_pc(pid_t pid, struct aura_pc_sample *sample,
                        struct aura_fex_view *view, struct aura_ec_view *ec, struct aura_alloc_view *allocation,
                        struct private_context *context) {
#if defined(__aarch64__)
    struct user_pt_regs registers;
#elif defined(__x86_64__)
    struct user_regs_struct registers;
#else
    (void)pid; (void)sample; (void)view; (void)ec; (void)allocation; (void)context;
    return PC_UNSUPPORTED;
#endif
#if defined(__aarch64__) || defined(__x86_64__)
    struct iovec iov = { &registers, sizeof(registers) };
    if (trace_call(PTRACE_GETREGSET, pid, (void *)(uintptr_t)NT_PRSTATUS_VALUE, &iov) || iov.iov_len != sizeof(registers)) return PC_IO;
#if defined(__aarch64__)
    memcpy(context->registers, registers.regs, sizeof(context->registers));
    context->sp = registers.sp;
    sample->pc = registers.pc;
    view->pc = registers.pc;
    sample->lr.value = registers.regs[30]; sample->lr.state = PC_REG_UNMAPPED;
    sample->x11.value = registers.regs[11]; sample->x11.state = PC_REG_UNMAPPED;
    view->frame = registers.regs[28]; /* Private until discarded with the tracer. */
    ec->pc = registers.pc; ec->lr = registers.regs[30];
    ec->sp = registers.sp; ec->fp = registers.regs[29]; /* Never exported. */
    allocation->pc = registers.pc; allocation->lr = registers.regs[30];
    allocation->size = registers.regs[1]; allocation->type = registers.regs[22];
    allocation->protect = registers.regs[19]; allocation->status = registers.regs[26];
#else
    (void)view; (void)allocation;
    ec->sp = registers.rsp;
    context->registers[0] = registers.rbp;
    sample->pc = registers.rip;
#endif
    return PC_OK;
#endif
}

static int read_target_metadata(void *opaque, uint64_t address, void *buffer, size_t length) {
    const struct request *r = opaque;
    struct iovec local = { buffer, length }, remote = { (void *)(uintptr_t)address, length };
    ssize_t n = syscall(SYS_process_vm_readv, r->pid, &local, 1, &remote, 1, 0);
    if (n == (ssize_t)length) return 1;
    if (n >= 0 || (errno != ENOSYS && errno != EPERM && errno != EACCES)) return 0;
    /* Some hosts lack process_vm_readv. PEEKDATA uses the attachment we already own;
     * no new permission, target call or write. Do not retry partial reads. Parent/child
     * deadlines still apply to the bounded metadata buffer (at most 16 KiB per read). */
    if (length < sizeof(unsigned long) || length > 16384 || address > UINT64_MAX - length) return 0;
    for (size_t offset = 0; offset < length;) {
        unsigned long word;
        size_t bytes = length - offset < sizeof(word) ? length - offset : sizeof(word);
        size_t back = sizeof(word) - bytes;
        /* Overlap the final word instead of reading beyond the validated byte range. */
        uint64_t at = address + offset - back;
        if (trace_call(PTRACE_PEEKDATA, r->tid, (void *)(uintptr_t)at, &word)) return 0;
        memcpy((char *)buffer + offset, (char *)&word + back, bytes);
        offset += bytes;
    }
    return 1;
}

/* A validated readable mapping must contain the complete fixed read. Data from
 * another mapping or a short read never supplies part of an address-space result. */
static int read_va_metadata(void *opaque, uint64_t address, void *buffer, size_t length) {
    const struct request *r = opaque;
    struct aura_pc_sample mapping = { .pc = address };
    if (!length || length > 512 || address > UINT64_MAX - length) return 0;
    read_mappings(r, &mapping, NULL, NULL);
    if (!mapping.mapped || !mapping.readable || length > mapping.map_end - address) return 0;
    return read_target_metadata(opaque, address, buffer, length);
}

static uint64_t find_va_ntdll(const struct request *r) {
    int fd = open(r->maps_path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return 0;
    char buffer[4096], line[1024];
    unsigned used = 0, total = 0;
    int overflow = 0, complete = 0;
    uint64_t found = 0;
    while (total < PC_MAP_LIMIT) {
        ssize_t n = read(fd, buffer, sizeof(buffer));
        if (!n) { complete = 1; break; }
        if (n < 0) break;
        total += (unsigned)n;
        for (ssize_t i = 0; i < n; i++) {
            if (buffer[i] != '\n') {
                if (used + 1 < sizeof(line)) line[used++] = buffer[i]; else overflow = 1;
                continue;
            }
            if (overflow) { close(fd); return 0; } /* Cannot establish uniqueness. */
            if (!overflow) {
                line[used] = 0;
                const char *p = line;
                uint64_t begin;
                if (number(&p, &begin, 16)) {
                    struct aura_pc_sample map = { .pc = begin };
                    if (map_line(line, r->exe, &map) && map.readable && !map.map_offset &&
                        !strcmp(map.module, AURA_VA_NTDLL_NAME)) {
                        if (found) { close(fd); return 0; } /* Ambiguous image identity. */
                        found = begin;
                    }
                }
            }
            used = 0; overflow = 0;
        }
    }
    close(fd);
    return complete && !used && !overflow ? found : 0;
}

static void read_address_space(const struct request *r, struct aura_va_snapshot *out) {
    aura_va_wine(find_va_ntdll(r), read_va_metadata, (void *)r, out);
    if (same(r->exe, "SouthPark_TFBW.exe")) aura_va_southpark(read_va_metadata, (void *)r, out);
    else out->game_state = VA_NOT_APPLICABLE;
}

static unsigned ref_module(const char *name);
static void read_guest_pc(struct request const *r, struct aura_pc_sample *sample, const struct aura_fex_view *view) {
    uint64_t guest = 0;
    sample->guest_pc = 0;
    sample->guest_state = aura_fex_guest_pc_detail(view, read_target_metadata, (void *)r, &guest, &sample->guest_detail);
    if (sample->guest_state == FEX_PC_MAPPED) {
        struct aura_pc_sample mapping = { .pc = guest };
        read_mappings(r, &mapping, NULL, NULL);
        /* Wine can keep translated x86 code non-executable on the host. Require a
         * readable guest mapping, not host PROT_EXEC; metadata supplies the code role. */
        if (mapping.mapped && mapping.readable) {
            sample->guest_pc = guest;
            sample->guest_module = ref_module(mapping.module);
            sample->guest_file_offset = mapping.map_offset;
        }
        else { sample->guest_state = FEX_PC_UNMAPPED; sample->guest_detail = FEX_DETAIL_GUEST_UNMAPPED; }
    }
}

static void read_wine_request(const struct request *r, struct aura_pc_sample *sample,
                              const struct private_context *context) {
    sample->request.request_id = UINT32_MAX;
    if (!r->detailed || !sample->mapped || !sample->readable || !sample->executable ||
        strcmp(sample->module, "libc.so") || sample->lr.state != PC_REG_CODE ||
        strcmp(sample->lr.module, AURA_REQUEST_NTDLL) ||
        sample->lr.map_offset > UINT64_MAX - 0x4000 ||
        sample->lr.value < sample->lr.map_offset + 0x4000) return;
    /* This exact ELF's executable PT_LOAD has VA-file offset = 0x4000.
     * No base inference is attempted for any other native Wine image. */
    struct aura_request_view view = {
        .base = sample->lr.value - sample->lr.map_offset - 0x4000,
        .pc = sample->pc, .pc_begin = sample->map_start, .pc_end = sample->map_end,
        .lr = sample->lr.value, .syscall_number = context->registers[8],
        .sp = context->sp, .request = context->registers[20], .fp = context->registers[29]
    };
    aura_wine_request_probe(&view, read_va_metadata, (void *)r, &sample->request);
}

static void read_entry_return(const struct request *r, struct aura_pc_sample *sample,
                              const struct aura_ec_view *view) {
    uint64_t address = 0;
    sample->entry_return = 0;
    sample->entry_state = aura_ec_entry_return(view, read_target_metadata, (void *)r, &address);
    if (sample->entry_state == EC_RETURN_CAPTURED) {
        struct aura_pc_sample mapping = { .pc = address };
        read_mappings(r, &mapping, NULL, NULL);
        /* The validated bridge slot supplies its role. Translated x86 code may
         * be non-executable to the host. This is not a general readable-pointer dump. */
        if (mapping.mapped && mapping.readable) sample->entry_return = address;
        else sample->entry_state = EC_RETURN_UNMAPPED;
    }
}

static const char *const ref_modules[] = { "OTHER_CODE", "ANON_EXEC", "FEX_NAMED_JIT", "FEX_NAMED_MISC",
    "GAME_IMAGE", "ntdll.dll", "kernelbase.dll", "kernel32.dll", "libarm64ecfex.dll", "libc.so",
    "uplay_aux_r164.dll", "dbdata.dll", "overlay64.dll", "APK_NATIVE" };

static unsigned ref_module(const char *name) {
    for (unsigned i = 1; i + 1 < sizeof(ref_modules) / sizeof(*ref_modules); i++)
        if (!strcmp(name, ref_modules[i])) return i;
    return !strncmp(name, "libaura_", 8) ? 13 : 0;
}

/* Read only 4 KiB at SP and retain executable references. Raw words, SP and
 * data-bearing registers never cross the pipe. These are candidates, not frames.
 * Translated x86 pages without host execute permission are intentionally omitted. */
static void read_code_context(const struct request *r, struct aura_pc_sample *sample,
                              const struct private_context *context, const struct aura_ec_view *ec) {
    if (sample->mapped && sample->readable && sample->executable && sample->pc >= sample->map_start + 16 &&
        sample->pc <= UINT64_MAX - 48 && sample->pc + 48 <= sample->map_end) {
        sample->code_start = sample->pc - 16;
        if (read_target_metadata((void *)r, sample->code_start, sample->code, sizeof(sample->code)))
            sample->code_size = sizeof(sample->code);
        else { memset(sample->code, 0, sizeof(sample->code)); sample->context_state |= 1; }
    } else sample->context_state |= 1;
    uint64_t stack[512] = {0};
    size_t words = 0;
    if (ec->sp && ec->sp >= ec->stack_begin && ec->sp < ec->stack_end && !(ec->sp & 7)) {
        uint64_t available = ec->stack_end - ec->sp;
        words = (available < sizeof(stack) ? (size_t)available : sizeof(stack)) / sizeof(*stack);
        if (!words || !read_target_metadata((void *)r, ec->sp, stack, words * sizeof(*stack))) words = 0;
    }
    sample->stack_bytes = (uint32_t)(words * sizeof(*stack));
    if (!words) sample->context_state |= 2;
    int fd = open(r->maps_path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) { sample->context_state |= 4; return; }
    char buffer[4096], line[1024]; unsigned used = 0, total = 0, regs = 0, slots = 0; int overflow = 0;
    while (total < PC_MAP_LIMIT) {
        ssize_t n = read(fd, buffer, sizeof(buffer));
        if (n <= 0) { if (n < 0) sample->context_state |= 4; break; }
        total += (unsigned)n;
        for (ssize_t i = 0; i < n; i++) {
            if (buffer[i] != '\n') {
                if (used + 1 < sizeof(line)) line[used++] = buffer[i]; else overflow = 1;
                continue;
            }
            line[used] = 0;
            const char *at = line; uint64_t begin, end;
            int valid = !overflow && number(&at, &begin, 16) && *at++ == '-' && number(&at, &end, 16);
            if (valid) spaces(&at);
            valid = valid && begin < end && strlen(at) >= 4 && at[0] == 'r' && at[2] == 'x';
            if (valid) for (unsigned source = 0; source < 2; source++) {
                unsigned count = source ? (unsigned)words : 31;
                for (unsigned index = 0; index < count; index++) {
                    uint64_t address = source ? stack[index] : context->registers[index];
                    if (address < begin || address >= end) continue;
                    unsigned *retained = source ? &slots : &regs;
                    if (*retained >= AURA_PC_REFS / 2) { sample->context_state |= 16; continue; }
                    struct aura_pc_sample mapping = { .pc = address };
                    if (!map_line(line, r->exe, &mapping) || !mapping.executable || !mapping.readable) continue;
                    struct aura_code_ref *ref = &sample->refs[sample->refs_count++];
                    ref->address = address; ref->map_start = mapping.map_start; ref->map_offset = mapping.map_offset;
                    ref->source = (uint16_t)source; ref->index = (uint16_t)(source ? index * 8 : index);
                    ref->module = (uint16_t)ref_module(mapping.module); (*retained)++;
                }
            }
            used = 0; overflow = 0;
        }
    }
    if (total >= PC_MAP_LIMIT) sample->context_state |= 8;
    close(fd);
}

#ifdef AURA_PC_TEST
static int test_hang_after_stop;
static int test_signal_after_seize;
#endif

static void collect_child(const struct request *r, int pipe_fd) {
    /* Reset/block inherited ART signals before doing work. SIGKILL always remains effective. */
    sigset_t blocked;
    sigfillset(&blocked); sigdelset(&blocked, SIGALRM);
    struct sigaction action = { .sa_handler = SIG_DFL };
    sigemptyset(&action.sa_mask);
    struct aura_pc_result result = { .worker = r->tid != r->pid };
    if (sigaction(SIGALRM, &action, NULL) || sigprocmask(SIG_SETMASK, &blocked, NULL) ||
        prctl(PR_SET_PDEATHSIG, SIGKILL)) {
        result.status = PC_IO; (void)write(pipe_fd, &result, sizeof(result)); _exit(1);
    }
    if (getppid() != r->parent) _exit(1);
    alarm(2);
    uint64_t began = now_ms();
    if (!began) { result.status = PC_IO; (void)write(pipe_fd, &result, sizeof(result)); _exit(1); }
    for (unsigned i = 0; i < AURA_PC_SAMPLES; i++) {
        if (i) pause_ms(PC_INTERVAL_MS);
        unsigned state = identity(r, 0);
        if (state) { result.status = state; break; }
        /* Run only in this short-lived observer, before stopping the target. */
        if (!i && r->detailed) aura_va_kernel(r->page_size, &result.address_space);
        if (trace_call(PTRACE_SEIZE, r->tid, NULL, NULL)) {
            result.status = errno == EPERM || errno == EACCES ? PC_DENIED : errno == ESRCH ? PC_GONE : PC_IO;
            break;
        }
#ifdef AURA_PC_TEST
        if (test_signal_after_seize) {
            syscall(SYS_tgkill, r->pid, r->tid, SIGUSR1); test_signal_after_seize = 0;
            /* Force the real signal-delivery stop before the second identity check. */
            for (unsigned attempt = 0; attempt < 100 && identity(r, 0) != PC_STOPPED; attempt++) pause_ms(1);
        }
#endif
        /* A real signal may already have stopped this thread under our attachment.
         * Allow lowercase t here, then classify/forward its actual wait status below.
         * Pre-existing stops and uppercase T remain rejected; identity is still rechecked. */
        state = identity(r, 1);
        if (state) { result.status = state; break; } /* Child exit detaches even if target is running. */
        if (trace_call(PTRACE_INTERRUPT, r->tid, NULL, NULL)) { result.status = PC_IO; break; }
        int status = 0; pid_t waited;
        for (unsigned attempt = 0; attempt < PC_STOP_MS; attempt++) {
            waited = waitpid(r->tid, &status, __WALL | WNOHANG);
            if (waited) break;
            pause_ms(1);
        }
        if (waited != r->tid || !WIFSTOPPED(status)) { result.status = waited == 0 ? PC_TIMEOUT : PC_GONE; break; }
#ifdef AURA_PC_TEST
        if (test_hang_after_stop) { for (;;) pause_ms(100); }
#endif
        int signal = WSTOPSIG(status);
        /* Real signal-delivery stops are forwarded unchanged. Do not consume FEX's signals. */
        int interrupt_stop = (unsigned)status >> 16 == PTRACE_EVENT_STOP && signal == SIGTRAP;
        if (!interrupt_stop) {
            int forward = (unsigned)status >> 16 ? 0 : signal;
            result.stop_signal = (uint32_t)signal;
            result.stop_event = (unsigned)status >> 16;
            /* An interrupted capture previously lost even the signal and PC. For
             * an explicit detailed capture, observe this existing delivery stop
             * once; do not retry, consume the signal, or run its handler ourselves.
             * Group/ptrace event stops are never treated as signal-delivery PCs. */
            if (r->detailed && !result.stop_event) {
                state = identity(r, 1);
                if (!state) {
                    siginfo_t info;
                    if (!trace_call(PTRACE_GETSIGINFO, r->tid, NULL, &info) && info.si_signo == signal) {
                        result.stop_info = 1;
                        result.stop_code = info.si_code;
                    }
                    struct aura_pc_sample *sample = &result.samples[result.count];
                    struct aura_fex_view view = {0};
                    struct aura_ec_view ec = {0};
                    struct aura_alloc_view allocation = {0};
                    struct private_context context = {0};
                    state = read_pc(r->tid, sample, &view, &ec, &allocation, &context);
                    if (!state) read_mappings(r, sample, view.pc ? &view : NULL, ec.sp ? &ec : NULL);
                    /* Decode only validated FEX metadata while this existing stop
                     * is held. A nearest guest mapping is not a crash verdict or
                     * an exact faulting x86 register snapshot. No signal retry,
                     * target writes, handler execution or extra attachment. */
                    if (!state && view.pc) read_guest_pc(r, sample, &view);
                    /* The signal boundary can otherwise hide the only busy
                     * thread. Reuse the same bounded executable-only context
                     * as an ordinary point, inside this existing stop. Raw
                     * stack words and data registers still never leave here. */
                    if (!state) read_code_context(r, sample, &context, &ec);
                    sample->elapsed_ms = (uint32_t)(now_ms() - began);
                }
                result.stop_pc_state = state + 1;
            }
            if (trace_call(PTRACE_DETACH, r->tid, NULL, (void *)(uintptr_t)forward)) { result.status = PC_IO; break; }
            result.stop_forwarded = forward ? 1 : 2;
            result.status = PC_SIGNAL; break;
        }
        state = identity(r, 1);
        struct aura_pc_sample *sample = &result.samples[result.count];
        struct aura_fex_view view = {0};
        struct aura_ec_view ec = {0};
        struct aura_alloc_view allocation = {0};
        struct private_context context = {0};
        if (!state) state = read_pc(r->tid, sample, &view, &ec, &allocation, &context);
        if (!state) read_mappings(r, sample, view.pc ? &view : NULL, ec.sp ? &ec : NULL);
        /* Read time-sensitive sync fields before optional code/stack/address-space context. */
        sample->sync.lock_state = sample->sync.wait_state = sample->sync.queue_state = VA_NOT_APPLICABLE;
        if (!state && r->detailed && same(r->exe, "MirrorsEdge.exe")) {
            aura_sync_lock(read_va_metadata, (void *)r, &sample->sync);
            if (!strcmp(sample->module, "libc.so") && sample->lr.state == PC_REG_CODE &&
                !strcmp(sample->lr.module, AURA_VA_NTDLL_NAME)) {
                uint64_t native_base = find_va_ntdll(r);
                aura_sync_wait(native_base, context.registers[30], context.registers[8],
                    context.registers[20], context.registers[19], read_va_metadata, (void *)r, &sample->sync);
                // No extra sampling batch/attachment. Read at most eight queue
                // nodes, only at the first point while the existing stop is held.
                if (!i) {
                    aura_sync_queue(native_base, read_va_metadata, (void *)r, &sample->sync);
                    aura_sync_frame(context.registers[19], read_va_metadata, (void *)r, &sample->sync);
                }
            }
        }
        if (!state) read_wine_request(r, sample, &context);
        /* Both bundled FEX Windows backends use x28; the reader validates
         * the complete ARM64EC or WOW64 entry signature before decoding. */
        if (!state && view.pc) read_guest_pc(r, sample, &view);
        if (!state && ec.sp && (sample->guest_state == FEX_PC_NOT_JIT ||
            sample->guest_state == FEX_PC_UNSUPPORTED)) {
            allocation.code_begin = ec.pc_begin; allocation.code_end = ec.pc_end;
            aura_wine_alloc_probe(&allocation, read_target_metadata, (void *)r, &sample->allocation);
            if (sample->allocation.state == ALLOC_NOT_APPLICABLE) read_entry_return(r, sample, &ec);
        }
        if (!state) read_code_context(r, sample, &context, &ec);
        if (!state && !i && r->detailed) read_address_space(r, &result.address_space);
        sample->elapsed_ms = (uint32_t)(now_ms() - began);
        sample->status = state;
        if (trace_call(PTRACE_DETACH, r->tid, NULL, NULL)) { result.status = PC_IO; break; }
        if (state) { result.status = state; break; }
        result.count++;
    }
    /* < PIPE_BUF: one bounded packet; no private proc text is returned. */
    (void)write(pipe_fd, &result, sizeof(result));
    _exit(0); /* Kernel detaches any attachment left by an error path; no EXITKILL option. */
}

static void collect_parent(pid_t pid, uint64_t start_ticks, pid_t tid, uint64_t thread_start,
                           const char *exe, int detailed, struct aura_pc_result *result) {
    memset(result, 0, sizeof(*result)); result->status = PC_INTERNAL;
    result->worker = tid != pid;
    if (pending_tracer) {
        int status;
        pid_t reaped = waitpid(pending_tracer, &status, WNOHANG);
        if (reaped != pending_tracer && !(reaped < 0 && errno == ECHILD)) {
            result->status = PC_RECOVERY_PENDING; return;
        }
        pending_tracer = 0;
    }
    struct request r = { .pid = pid, .tid = tid, .parent = getpid(), .uid = getuid(),
        .start = start_ticks, .thread_start = thread_start };
    r.detailed = detailed;
    long page_size = detailed ? sysconf(_SC_PAGESIZE) : 0;
    r.page_size = page_size > 0 ? (uint64_t)page_size : 0;
    if (pid <= 1 || pid == r.parent || tid <= 1 || tid == r.parent || !start_ticks || !thread_start ||
        (tid == pid && thread_start != start_ticks) || !exe || !*exe || strlen(exe) >= sizeof(r.exe)) return;
    for (const char *p = exe; *p; p++) if (*p == '/' || *p == '\\' || (unsigned char)*p < 32) return;
    strcpy(r.exe, exe);
    snprintf(r.proc_path, sizeof(r.proc_path), "/proc/%d", pid);
    snprintf(r.stat_path, sizeof(r.stat_path), "/proc/%d/stat", pid);
    snprintf(r.task_stat_path, sizeof(r.task_stat_path), "/proc/%d/task/%d/stat", pid, tid);
    snprintf(r.cmd_path, sizeof(r.cmd_path), "/proc/%d/cmdline", pid);
    snprintf(r.maps_path, sizeof(r.maps_path), "/proc/%d/maps", pid);
    uint64_t began = now_ms();
    if (!began) return;
    int fds[2];
    if (pipe2(fds, O_CLOEXEC)) return;
    pid_t child = fork();
    if (child == 0) { close(fds[0]); collect_child(&r, fds[1]); }
    close(fds[1]);
    if (child < 0) { close(fds[0]); return; }
    uint64_t deadline = began + PC_DEADLINE_MS;
    size_t used = 0;
    struct aura_pc_result packet = {0};
    struct pollfd ready = { .fd = fds[0], .events = POLLIN };
    while (used < sizeof(packet)) {
        uint64_t current = now_ms();
        if (!current || current >= deadline) break;
        int time_left = (int)(deadline - current);
        int polled = poll(&ready, 1, time_left);
        if (polled < 0 && errno == EINTR) continue;
        if (polled <= 0) break;
        ssize_t n = read(fds[0], (char *)&packet + used, sizeof(packet) - used);
        if (n <= 0) break;
        used += (size_t)n;
    }
    close(fds[0]);
    /* Only our own disposable tracer is killed. The target is never killed or SIGCONT'ed. */
    // Also terminate a tracer suspended after sending its completed packet. Never wait indefinitely.
    kill(child, SIGKILL);
    int status, reaped = 0;
    for (unsigned attempt = 0; attempt < 100; attempt++) {
        pid_t waited = waitpid(child, &status, WNOHANG);
        if (waited == child || (waited < 0 && errno == ECHILD)) { reaped = 1; break; }
        pause_ms(1);
    }
    if (!reaped) { pending_tracer = child; result->status = PC_RECOVERY_PENDING; return; }
    if (used == sizeof(packet) && packet.count <= AURA_PC_SAMPLES && packet.status <= PC_STATE) *result = packet;
    else result->status = PC_TIMEOUT;
}

static void collect(pid_t pid, uint64_t start_ticks, pid_t tid, uint64_t thread_start,
                    const char *exe, int detailed, struct aura_pc_result *result) {
    if (atomic_flag_test_and_set(&collecting)) {
        memset(result, 0, sizeof(*result)); result->status = PC_BUSY;
        result->worker = tid != pid; return;
    }
    collect_parent(pid, start_ticks, tid, thread_start, exe, detailed, result);
    atomic_flag_clear(&collecting);
}

void aura_pc_collect(pid_t pid, uint64_t start_ticks, const char *exe, struct aura_pc_result *result) {
    collect(pid, start_ticks, pid, start_ticks, exe, 0, result);
}

void aura_pc_collect_detailed(pid_t pid, uint64_t start_ticks, const char *exe, struct aura_pc_result *result) {
    collect(pid, start_ticks, pid, start_ticks, exe, 1, result);
}

void aura_pc_collect_thread(pid_t pid, uint64_t start_ticks, pid_t tid, uint64_t thread_start,
                            const char *exe, struct aura_pc_result *result) {
    collect(pid, start_ticks, tid, thread_start, exe, 1, result);
}

const char *aura_pc_status_name(unsigned status) {
    const char *names[] = { "CAPTURED", "DENIED", "IDENTITY_CHANGED", "TARGET_CHANGED", "GONE", "STOPPED",
        "TIMEOUT", "SIGNAL_INTERRUPTED", "UNSUPPORTED", "IO_ERROR", "UNAVAILABLE", "RECOVERY_PENDING", "BUSY", "STATE_UNSUITABLE" };
    return status < sizeof(names) / sizeof(*names) ? names[status] : "UNAVAILABLE";
}

/* This code runs in the parent only, after the tracer has exited and detached. */
static void format_register(const struct aura_pc_register *reg, char text[160]) {
    if (reg->state == PC_REG_CODE)
        snprintf(text, 160, "0x%" PRIx64 "/%s+0x%" PRIx64, reg->value, reg->module, reg->map_offset);
    else snprintf(text, 160, "%s", reg->state == PC_REG_UNSUPPORTED ? "UNSUPPORTED" :
        reg->state == PC_REG_NON_EXEC ? "NON_EXEC" : "UNMAPPED");
}

static void format_guest(const struct aura_pc_sample *s, char text[32]) {
    const char *names[] = { "UNSUPPORTED", "NOT_JIT", "UNREADABLE", "LAYOUT", "LIMIT", "CHANGED", "UNMAPPED" };
    const char *entry_names[] = { "NOT_APPLICABLE", "UNREADABLE", "LAYOUT", "CHANGED", "UNMAPPED" };
    if (s->entry_state) {
        if (s->entry_state == EC_RETURN_CAPTURED && s->entry_return)
            snprintf(text, 32, "ec_ret=0x%" PRIx64, s->entry_return);
        else snprintf(text, 32, "ec_ret=%s", s->entry_state < sizeof(entry_names) / sizeof(*entry_names) ?
                      entry_names[s->entry_state] : "UNKNOWN");
    } else if (s->guest_state == FEX_PC_MAPPED && s->guest_pc)
        snprintf(text, 32, "guest=0x%" PRIx64, s->guest_pc);
    else snprintf(text, 32, "guest=%s", s->guest_state < sizeof(names) / sizeof(*names) ? names[s->guest_state] : "UNKNOWN");
}

static void format_context(const struct aura_pc_sample *s, char text[336]) {
    const struct aura_alloc_result *a = &s->allocation;
    if (a->state == ALLOC_PRE || a->state == ALLOC_POST) {
        char status[16];
        if (a->state == ALLOC_POST) snprintf(status, sizeof(status), "0x%08" PRIx32, a->status);
        else snprintf(status, sizeof(status), "UNKNOWN");
        snprintf(text, 336, "alloc=%s; size=0x%" PRIx64 "; type=0x%" PRIx32
            "; prot=0x%" PRIx32 "; ntstatus=%s", a->state == ALLOC_PRE ? "PRE" : "POST",
            a->size, a->type, a->protect, status);
    } else if (a->state) {
        snprintf(text, 336, "alloc=%s", a->state == ALLOC_UNREADABLE ? "UNREADABLE" :
                 a->state == ALLOC_CHANGED ? "CHANGED" : "UNKNOWN");
    } else {
        char lr[160], x11[160];
        format_register(&s->lr, lr); format_register(&s->x11, x11);
        snprintf(text, 336, "lr=%s; x11=%s", lr, x11);
    }
}

void aura_pc_format(const struct aura_pc_result *result, char text[AURA_PC_TEXT]) {
#if defined(__aarch64__)
    const char *architecture = "ARM64";
#elif defined(__x86_64__)
    const char *architecture = "X86_64";
#else
    const char *architecture = "UNKNOWN";
#endif
    int written = snprintf(text, AURA_PC_TEXT,
        "state=%s; samples=%u; scope=%s; architecture=%s; guest_pc=FEX2610_MAP; context=CODE_REGS_V4",
        aura_pc_status_name(result->status), result->count, result->worker ? "LINUX_THREAD" : "LINUX_LEADER", architecture);
    if (result->count > AURA_PC_SAMPLES) goto overflow;
    for (unsigned i = 0; i < result->count; i++) {
        const struct aura_pc_sample *s = &result->samples[i];
        char context[336], guest[32];
        format_context(s, context); format_guest(s, guest);
        int n = snprintf(text + written, AURA_PC_TEXT - (size_t)written,
            "\nGAME_NATIVE_PC_SAMPLE_V1 index=%u; elapsed_ms=%u; pc=0x%" PRIx64 "; module=%s; map_start=0x%" PRIx64
            "; file_offset=0x%" PRIx64 "; executable=%s; %s; %s; ready=UNCONFIRMED",
            i + 1, s->elapsed_ms, s->pc, s->mapped ? s->module : "UNKNOWN", s->map_start, s->map_offset,
            s->mapped ? (s->executable ? "YES" : "NO") : "UNKNOWN", context, guest);
        if (n < 0 || (size_t)n >= AURA_PC_TEXT - (size_t)written) goto overflow;
        written += n;
    }
    return;
overflow:
    snprintf(text, AURA_PC_TEXT, "state=OUTPUT_LIMIT; samples=0; ready=UNCONFIRMED");
}

static int format_code_context(const struct aura_pc_sample *s, unsigned index, int signal,
                               char text[AURA_PC_DETAIL], int *used) {
    if (s->code_size > sizeof(s->code) || s->refs_count > AURA_PC_REFS) return 0;
    char hex[129];
    for (unsigned b = 0; b < s->code_size; b++) snprintf(hex + b * 2, 3, "%02x", s->code[b]);
    hex[s->code_size * 2] = 0;
    int n = snprintf(text + *used, AURA_PC_DETAIL - (size_t)*used,
        "%s index=%u; flags=%u; code_start=0x%" PRIx64
        "; code_bytes=%u; code_hex=%s; stack_bytes=%u; refs=%u\n",
        signal ? "SIGNAL_CODE_CONTEXT_V1" : "CODE_CONTEXT_SAMPLE_V1", index, s->context_state,
        s->code_size ? s->code_start : 0, s->code_size, hex, s->stack_bytes, s->refs_count);
    if (n < 0 || (size_t)n >= AURA_PC_DETAIL - (size_t)*used) return 0;
    *used += n;
    n = snprintf(text + *used, AURA_PC_DETAIL - (size_t)*used,
        "FEX_GUEST_MAP_V2 sample=%u; context=%s; reason=%s; mapping=NEAREST_NOT_EXACT; raw_metadata=OMITTED\n",
        index, signal ? "SIGNAL_DELIVERY" : "SAMPLE", aura_fex_detail_name(s->guest_detail));
    if (n < 0 || (size_t)n >= AURA_PC_DETAIL - (size_t)*used) return 0;
    *used += n;
    if (s->guest_state == FEX_PC_MAPPED && s->guest_pc) {
        const char *module = s->guest_module < sizeof(ref_modules) / sizeof(*ref_modules) ?
            ref_modules[s->guest_module] : "UNKNOWN";
        n = snprintf(text + *used, AURA_PC_DETAIL - (size_t)*used,
            "FEX_GUEST_LOCATION_V1 sample=%u; context=%s; guest=0x%" PRIx64
            "; module=%s; mapping_offset=0x%" PRIx64 "; source=%s; precision=NEAREST_NOT_EXACT\n",
            index, signal ? "SIGNAL_DELIVERY" : "SAMPLE", s->guest_pc, module, s->guest_file_offset,
            s->guest_detail == FEX_DETAIL_MAPPED_ENTRY ? "CURRENT_ENTRY_METADATA" : "FRAME_METADATA");
        if (n < 0 || (size_t)n >= AURA_PC_DETAIL - (size_t)*used) return 0;
        *used += n;
    }
    if (!signal) {
        const struct aura_request_snapshot *q = &s->request;
        n = snprintf(text + *used, AURA_PC_DETAIL - (size_t)*used,
            "WINE_THREAD_REQUEST_V2 sample=%u; state=%s; phase=%s; request=%s; source=%s; detail=%s; "
            "observation=SAMPLED_NOT_CALL_HISTORY; completion=UNOBSERVED; payload=OMITTED\n",
            index, aura_request_state_name(q->state), aura_request_phase_name(q->phase),
            q->state == REQUEST_CAPTURED ? aura_request_name(q->request_id) : "UNKNOWN",
            aura_request_source_name(q->source), aura_request_detail_name(q->detail));
        if (n < 0 || (size_t)n >= AURA_PC_DETAIL - (size_t)*used) return 0;
        *used += n;
    }
    for (unsigned j = 0; j < s->refs_count; j++) {
        const struct aura_code_ref *ref = &s->refs[j];
        const char *module = ref->module < sizeof(ref_modules) / sizeof(*ref_modules) ? ref_modules[ref->module] : "UNKNOWN";
        n = snprintf(text + *used, AURA_PC_DETAIL - (size_t)*used,
            "%s sample=%u; source=%s; index=%u; address=0x%" PRIx64
            "; module=%s; map_start=0x%" PRIx64 "; file_offset=0x%" PRIx64 "\n",
            signal ? "SIGNAL_CODE_REF_V1" : "CODE_REF_V1", index,
            ref->source ? "STACK_BYTE_OFFSET" : "REGISTER", ref->index, ref->address,
            module, ref->map_start, ref->map_offset);
        if (n < 0 || (size_t)n >= AURA_PC_DETAIL - (size_t)*used) return 0;
        *used += n;
    }
    return 1;
}

void aura_pc_format_detail(const struct aura_pc_result *result, char text[AURA_PC_DETAIL]) {
    char summary[AURA_PC_TEXT]; aura_pc_format(result, summary);
    int used = snprintf(text, AURA_PC_DETAIL, "%s\nCODE_CONTEXT_V1 stack_limit=4096; refs_limit=16; "
        "scope=%s; refs=EXECUTABLE_CANDIDATES_NOT_BACKTRACE; host_nonexec_guest_pages=OMITTED\n", summary,
        result->worker ? "LINUX_THREAD" : "LINUX_LEADER");
    if (result->stop_signal) {
        char code[32];
        if (result->stop_info) snprintf(code, sizeof(code), "%d", result->stop_code);
        else snprintf(code, sizeof(code), "?");
        int n = snprintf(text + used, AURA_PC_DETAIL - (size_t)used,
            "GAME_NATIVE_SIGNAL_STOP_V1 signal=%u; code=%s; event=%u; kind=%s; forwarded=%s; pc_state=%s; fatality=UNCONFIRMED\n",
            result->stop_signal, code, result->stop_event, result->stop_event ? "PTRACE_EVENT" : "SIGNAL_DELIVERY",
            result->stop_forwarded == 1 ? "UNCHANGED" : result->stop_forwarded == 2 ? "NONE" : "UNCONFIRMED",
            result->stop_pc_state ? aura_pc_status_name(result->stop_pc_state - 1) : "NOT_REQUESTED");
        if (n < 0 || (size_t)n >= AURA_PC_DETAIL - (size_t)used) goto limit;
        used += n;
        if (!result->stop_event && result->stop_pc_state == PC_OK + 1 && result->count < AURA_PC_SAMPLES) {
            const struct aura_pc_sample *s = &result->samples[result->count];
            char context[336], guest[32]; format_context(s, context); format_guest(s, guest);
            n = snprintf(text + used, AURA_PC_DETAIL - (size_t)used,
                "GAME_NATIVE_SIGNAL_PC_V1 elapsed_ms=%u; pc=0x%" PRIx64 "; module=%s; map_start=0x%" PRIx64
                "; file_offset=0x%" PRIx64 "; executable=%s; %s; context=SIGNAL_DELIVERY; %s; guest_mapping=NEAREST_FEX; ready=UNCONFIRMED\n",
                s->elapsed_ms, s->pc, s->mapped ? s->module : "UNKNOWN", s->map_start, s->map_offset,
                s->mapped ? (s->executable ? "YES" : "NO") : "UNKNOWN", context, guest);
            if (n < 0 || (size_t)n >= AURA_PC_DETAIL - (size_t)used) goto limit;
            used += n;
            if (!format_code_context(s, result->count + 1, 1, text, &used)) goto limit;
        }
    }
    const struct aura_va_snapshot *va = &result->address_space;
    int n = snprintf(text + used, AURA_PC_DETAIL - (size_t)used,
        "KERNEL_ADDRESS_SPACE_V1 source=OBSERVER_SAME_KERNEL; page_size=%" PRIu64
        "; probes=ONE_PAGE_EACH; at_512GiB=%s/%u; at_768GiB=%s/%u; at_1TiB=%s/%u; width=NOT_INFERRED\n"
        "WINE_ADDRESS_SPACE_V1 state=%s; layout=NTDLL_EE8B621D\n"
        "GAME_ALLOCATOR_V1 state=%s; layout=SOUTHPARK_0E1C9006\n",
        va->page_size, aura_va_kernel_name(va->kernel_state[0]), va->kernel_errno[0],
        aura_va_kernel_name(va->kernel_state[1]), va->kernel_errno[1],
        aura_va_kernel_name(va->kernel_state[2]), va->kernel_errno[2],
        aura_va_state_name(va->wine_state), aura_va_state_name(va->game_state));
    if (n < 0 || (size_t)n >= AURA_PC_DETAIL - (size_t)used) goto limit;
    used += n;
    if (va->wine_state == VA_CAPTURED) {
        n = snprintf(text + used, AURA_PC_DETAIL - (size_t)used,
            "WINE_ADDRESS_LIMITS_V1 address=0x%" PRIx64 "; user=0x%" PRIx64
            "; working_set=0x%" PRIx64 "; host=0x%" PRIx64 "; single_alloc_cap=0x%" PRIx64
            "; cap_zero=UNLIMITED; validation=LAYOUT_FINGERPRINTS\n",
            va->wine[0], va->wine[1], va->wine[2], va->wine[3], va->wine[4]);
        if (n < 0 || (size_t)n >= AURA_PC_DETAIL - (size_t)used) goto limit;
        used += n;
    }
    if (va->game_state == VA_CAPTURED) {
        n = snprintf(text + used, AURA_PC_DETAIL - (size_t)used,
            "GAME_ALLOCATOR_VALUES_V1 arena=0x%" PRIx64 "; pool_start=0x%" PRIx64
            "; next_page=0x%" PRIx64 "; reserve_cursor=0x%" PRIx64 "; pool_end=0x%" PRIx64
            "; shared_error=%" PRIu64 "; request_size=0x100000000; type=MEM_RESERVE; prot=PAGE_READWRITE"
            "; request_source=VERIFIED_CODE; ntstatus=UNKNOWN; validation=LAYOUT_FINGERPRINTS\n",
            va->game[0], va->game[1], va->game[2], va->game[3], va->game[4], va->game[5]);
        if (n < 0 || (size_t)n >= AURA_PC_DETAIL - (size_t)used) goto limit;
        used += n;
    }
    if (result->count > AURA_PC_SAMPLES) return;
    for (unsigned i = 0; i < result->count; i++) {
        const struct aura_pc_sample *s = &result->samples[i];
        const struct aura_sync_snapshot *sync = &s->sync;
        n = snprintf(text + used, AURA_PC_DETAIL - (size_t)used,
            "WINE_SYNC_V1 sample=%u; candidate=HISTORICAL_05AD8C6C; lock_state=%s; wait_state=%s; "
            "current_lock=UNCONFIRMED; snapshot=DOUBLE_READ_NOT_ATOMIC\n", i + 1,
            aura_va_state_name(sync->lock_state), aura_va_state_name(sync->wait_state));
        if (n < 0 || (size_t)n >= AURA_PC_DETAIL - (size_t)used) goto limit;
        used += n;
        if (sync->lock_state == VA_CAPTURED) {
            n = snprintf(text + used, AURA_PC_DETAIL - (size_t)used,
                "WINE_CRITICAL_SECTION_V1 sample=%u; address=0x05ad8c6c; debug=0x%x; lock_count=%" PRId32
                "; recursion=%u; owner_wine_tid=0x%x; semaphore=0x%x; spin_count=%u; validation=EXE_LAYOUT_AND_STRUCTURE\n",
                i + 1, sync->lock[0], (int32_t)sync->lock[1], sync->lock[2], sync->lock[3], sync->lock[4], sync->lock[5]);
            if (n < 0 || (size_t)n >= AURA_PC_DETAIL - (size_t)used) goto limit;
            used += n;
        }
        if (sync->wait_state == VA_CAPTURED) {
            n = snprintf(text + used, AURA_PC_DETAIL - (size_t)used,
                "WINE_ALERT_WAIT_V1 sample=%u; function=NtWaitForAlertByThreadId; mode=%s; futex=0x%" PRIx64
                "; flag=%u; timeout_100ns=%" PRId64 "; validation=NTDLL_LAYOUT_AND_LR_AND_SYSCALL; wake_history=UNKNOWN\n",
                i + 1, sync->timed ? "TIMED" : "UNTIMED", sync->futex, sync->flag, sync->timeout);
            if (n < 0 || (size_t)n >= AURA_PC_DETAIL - (size_t)used) goto limit;
            used += n;
        }
        if (sync->queue_state != VA_NOT_REQUESTED && sync->queue_state != VA_NOT_APPLICABLE) {
            n = snprintf(text + used, AURA_PC_DETAIL - (size_t)used,
                "WINE_WAIT_QUEUE_SNAPSHOT_V1 sample=%u; state=%s; candidate=HISTORICAL_05AD8C7C; "
                "layout=PE_8E91FBF9_NATIVE_EE8B621D; stage=%s; pe_base=0x%x; base_source=WINE_LOADER; "
                "pe_hash=0x%" PRIx64 "; hash_kind=FNV1A64_NORMALIZED; "
                "max_nodes=8; snapshot=DOUBLE_READ_NOT_ATOMIC; wake_delivery=UNOBSERVED; cause=UNCONFIRMED\n",
                i + 1, aura_va_state_name(sync->queue_state), aura_sync_queue_stage_name(sync->queue_stage),
                sync->pe_base, sync->pe_fingerprint);
            if (n < 0 || (size_t)n >= AURA_PC_DETAIL - (size_t)used) goto limit;
            used += n;
            if (sync->queue_state == VA_CAPTURED) {
                if (sync->candidate_nodes > 8 || sync->queue_nodes > 8) return;
                char tids[96] = {0}; size_t written = 0;
                for (unsigned j = 0; j < sync->candidate_nodes; j++) {
                    int part = snprintf(tids + written, sizeof(tids) - written, "%s0x%x",
                                        j ? "," : "", sync->candidate_tids[j]);
                    if (part < 0 || (size_t)part >= sizeof(tids) - written) return;
                    written += (size_t)part;
                }
                n = snprintf(text + used, AURA_PC_DETAIL - (size_t)used,
                    "WINE_WAIT_QUEUE_VALUES_V1 sample=%u; waiter_wine_tid=0x%x; bucket=199; nodes=%u; "
                    "candidate_waiters=%u; selected_candidate_nodes=%u; selected_other_nodes=%u; candidate_wine_tids=[%s]\n",
                    i + 1, sync->waiter_wine_tid, sync->queue_nodes, sync->candidate_nodes,
                    sync->selected_candidate, sync->selected_other, tids);
                if (n < 0 || (size_t)n >= AURA_PC_DETAIL - (size_t)used) goto limit;
                used += n;
            }
        }
        if (sync->frame_state != VA_NOT_REQUESTED) {
            n = snprintf(text + used, AURA_PC_DETAIL - (size_t)used,
                "WINE_CRITICAL_WAIT_FRAME_V1 sample=%u; state=%s; source=PINNED_X86_TIMEOUT_FRAME; "
                "snapshot=DOUBLE_READ_NOT_ATOMIC; wake_delivery=UNOBSERVED; cause=UNCONFIRMED\n",
                i + 1, aura_va_state_name(sync->frame_state));
            if (n < 0 || (size_t)n >= AURA_PC_DETAIL - (size_t)used) goto limit;
            used += n;
            if (sync->frame_state == VA_CAPTURED) {
                n = snprintf(text + used, AURA_PC_DETAIL - (size_t)used,
                    "WINE_CRITICAL_WAIT_FRAME_VALUES_V1 sample=%u; critical=0x%x; candidate_relation=%s; "
                    "node_address_state=%s; node_wine_tid=0x%x; node_membership=UNCONFIRMED; "
                    "validation=CODE_CALLSITE_ARGUMENTS_AND_TID\n",
                    i + 1, sync->frame_critical,
                    sync->frame_critical == 0x05ad8c6c ? "MATCH" : "OTHER",
                    sync->frame_node_address ? "RETAINED" : "CLEARED", sync->frame_node_tid);
                if (n < 0 || (size_t)n >= AURA_PC_DETAIL - (size_t)used) goto limit;
                used += n;
            }
        }
        if (!format_code_context(s, i + 1, 0, text, &used)) goto limit;
    }
    return;
limit:
    snprintf(text, AURA_PC_DETAIL, "state=OUTPUT_LIMIT; samples=0");
}
