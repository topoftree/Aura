/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT
 * Read-only observations at existing manual-capture stops, not syscall tracing.
 * No extra stops, hooks, requests, payload reads or target calls. The pinned
 * reply union is never decoded as an outgoing request. WOW64's original 32-bit
 * header remains separate until the pinned bridge returns; its uninitialised
 * native name field is never read. Only fixed metadata and enum names leave us.
 */
#include "wine_request_probe.h"
#include <string.h>
#include "wine_request_profile.h"

static uint32_t wrp32(const unsigned char *p) {
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}
static uint64_t wrp64(const unsigned char *p) { return wrp32(p) | (uint64_t)wrp32(p + 4) << 32; }

const char *aura_request_state_name(unsigned state) {
    static const char *const names[] = { "NOT_APPLICABLE", "CAPTURED", "UNREADABLE",
        "CODE_MISMATCH", "REQUEST_LAYOUT", "UNKNOWN_REQUEST", "CHANGED", "UNKNOWN_CALLER" };
    return state < sizeof(names) / sizeof(*names) ? names[state] : "UNKNOWN";
}
const char *aura_request_phase_name(unsigned phase) {
    static const char *const names[] = { "NONE", "SEND_HEADER", "SEND_PARTIAL", "SEND_VECTOR",
        "READ_REPLY_HEADER", "READ_REPLY_DATA", "YIELD_USAGE_BEFORE", "YIELD_SCHEDULER", "YIELD_USAGE_AFTER" };
    return phase < sizeof(names) / sizeof(*names) ? names[phase] : "UNKNOWN";
}
const char *aura_request_name(unsigned id) {
    return id < AURA_REQUEST_COUNT ? request_names[id] : "UNKNOWN";
}
const char *aura_request_source_name(unsigned source) {
    static const char *const names[] = { "NONE", "SEND_HEADER", "WOW64_ORIGINAL_HEADER", "NATIVE_NAME" };
    return source < sizeof(names) / sizeof(*names) ? names[source] : "UNKNOWN";
}
const char *aura_request_detail_name(unsigned detail) {
    static const char *const names[] = { "NONE", "HEADER_ID_OUT_OF_RANGE", "SEND_SIZE_MISMATCH",
        "CALLER_FRAME_MISMATCH", "CALLER_UNLISTED", "WOW64_CODE_MISMATCH", "WOW64_POINTER_RANGE",
        "WOW64_COUNT_MISMATCH", "NAME_NULL", "NAME_OUTSIDE_NATIVE", "NAME_UNLISTED", "NAME_TEXT_MISMATCH" };
    return detail < sizeof(names) / sizeof(*names) ? names[detail] : "UNKNOWN";
}

#define WRP_FAIL(value) do { out->state = REQUEST_##value; return; } while (0)
#define WRP_DETAIL(value, why) do { out->detail = REQUEST_DETAIL_##why; WRP_FAIL(value); } while (0)
#define WRP_CAPTURE(id, origin) do { out->request_id = (id); out->source = REQUEST_SOURCE_##origin; \
    out->state = REQUEST_CAPTURED; return; } while (0)
void aura_wine_request_probe(const struct aura_request_view *v, aura_fex_read read_memory, void *opaque,
                              struct aura_request_snapshot *out) {
    memset(out, 0, sizeof(*out)); out->request_id = UINT32_MAX;
    if (!v->base || v->base > UINT64_C(0x800000000000) - 0x200000 || v->lr < v->base) return;
    const struct request_callsite *site = NULL;
    for (unsigned i = 0; i < sizeof(request_callsites) / sizeof(*request_callsites); i++)
        if (v->lr - v->base == request_callsites[i].lr) { site = &request_callsites[i]; break; }
    if (!site) return;
    unsigned char code[48], wrapper[20], name_slot[8], again[64], name[64], header[12];
    if (v->syscall_number != site->syscall_number || (v->pc & 3) ||
        v->pc_begin >= v->pc_end || v->pc < v->pc_begin || v->pc - v->pc_begin < 12 ||
        v->pc >= v->pc_end || v->pc_end - v->pc < 8) WRP_FAIL(CODE_MISMATCH);
    if (!read_memory(opaque, v->lr - 16, code, 16) ||
        !read_memory(opaque, v->pc - 12, wrapper, sizeof(wrapper))) WRP_FAIL(UNREADABLE);
    if (memcmp(code, site->code, 16)) WRP_FAIL(CODE_MISMATCH);
    /* Exact Bionic mov x8,#nr; svc #0 at the sampled PC or just before it.
     * This also proves x20 is still callee-saved, not repurposed in a libc helper. */
    uint32_t mov = UINT32_C(0xd2800008) | site->syscall_number << 5;
    if (!((wrp32(wrapper) == UINT32_C(0xd503245f) && wrp32(wrapper + 4) == mov &&
           wrp32(wrapper + 8) == UINT32_C(0xd4000001)) ||
          (wrp32(wrapper + 4) == UINT32_C(0xd503245f) && wrp32(wrapper + 8) == mov &&
           wrp32(wrapper + 12) == UINT32_C(0xd4000001))))
        WRP_FAIL(CODE_MISMATCH);
    if (!read_memory(opaque, v->base + 0xe523c, code, sizeof(code))) WRP_FAIL(UNREADABLE);
    if (memcmp(code, request_function_code, sizeof(code))) WRP_FAIL(CODE_MISMATCH);
    if (!read_memory(opaque, v->base + 0xe5284, code, 8)) WRP_FAIL(UNREADABLE);
    if (memcmp(code, request_name_load, 8)) WRP_FAIL(CODE_MISMATCH);
    out->phase = site->phase;
    if (site->phase >= REQUEST_YIELD_USAGE_BEFORE) { out->state = REQUEST_CAPTURED; return; }
    /* req is a caller stack object. Bound it to 64 KiB above this actual SP;
     * the production reader additionally requires each full read in a readable
     * VMA. RWX Wine stacks are valid. Never dereference data/reply pointers. */
    if (!v->sp || (v->sp & 15) || (v->request & 7) || v->request < v->sp ||
        v->request - v->sp > 65536 - 168 || v->request > UINT64_C(0x800000000000) - 168)
        WRP_FAIL(LAYOUT);

    /* At these exact write/writev sites the union is still the outgoing header,
     * including partial sends. A name string is not needed and may not have been
     * initialised by the WOW64 bridge. Read no variable-sized body or I/O vector. */
    if (site->phase <= REQUEST_SEND_VECTOR) {
        if (!read_memory(opaque, v->request, header, sizeof(header))) WRP_FAIL(UNREADABLE);
        unsigned id = wrp32(header);
        if (id >= AURA_REQUEST_COUNT) WRP_DETAIL(UNKNOWN_NAME, HEADER_ID);
        if ((site->phase == REQUEST_SEND_VECTOR) != (wrp32(header + 4) != 0)) WRP_DETAIL(LAYOUT, SEND_SIZE);
        if (!read_memory(opaque, v->request, again, sizeof(header))) WRP_FAIL(UNREADABLE);
        if (memcmp(header, again, sizeof(header))) WRP_FAIL(CHANGED);
        WRP_CAPTURE(id, SEND_HEADER);
    }

    /* The leaf Bionic wrapper above preserves the server frame: x29=SP+0x60.
     * These are the two fixed saved FP/LR slots, not a stack scan/backtrace. */
    if (v->fp != v->sp + 0x60) WRP_DETAIL(LAYOUT, CALLER_FRAME);
    unsigned char frame[16];
    if (!read_memory(opaque, v->fp, frame, sizeof(frame))) WRP_FAIL(UNREADABLE);
    uint64_t caller_lr = wrp64(frame + 8);
    const struct request_caller *caller = NULL;
    if (caller_lr >= v->base) {
        for (unsigned i = 0; i < sizeof(request_callers) / sizeof(*request_callers); i++)
            if (caller_lr - v->base == request_callers[i].lr) { caller = &request_callers[i]; break; }
    }
    if (!caller) WRP_DETAIL(UNKNOWN_CALLER, CALLER_UNLISTED);

    if (caller->lr == REQUEST_WOW64_RETURN) {
        /* The bridge's native copy starts at its SP, immediately above the
         * server frame. The original pointer lives in saved x19 at SP+0xb8.
         * Its 32-bit union is copied back only after this whole server call. */
        if (v->request != v->sp + 0xc0 || wrp64(frame) != v->request + 0xb0)
            WRP_DETAIL(LAYOUT, CALLER_FRAME);
        unsigned char bridge[sizeof(request_wow64_code)], saved[8], native_count[8], original_count[8];
        if (!read_memory(opaque, v->base + REQUEST_WOW64_START, bridge, sizeof(bridge))) WRP_FAIL(UNREADABLE);
        if (memcmp(bridge, request_wow64_code, sizeof(bridge))) WRP_DETAIL(CODE_MISMATCH, WOW64_CODE);
        if (!read_memory(opaque, v->sp + 0xb8, saved, sizeof(saved))) WRP_FAIL(UNREADABLE);
        uint64_t original = wrp64(saved);
        /* Independent 32-bit guest stack; do not assume it lies near native SP.
         * Production read_memory checks the entire readable VMA on every read. */
        if (original < 0x10000 || original > UINT32_MAX - 71 || (original & 3) ||
            (original < v->request + 168 && original + 72 > v->sp))
            WRP_DETAIL(LAYOUT, WOW64_POINTER);
        /* Eight bytes also support bounded PEEKDATA fallback. Only the count's
         * first four bytes are interpreted; adjacent padding/pointer is ignored,
         * never followed or exported, and neither read touches the reply union. */
        if (!read_memory(opaque, v->request + 64, native_count, 8) ||
            !read_memory(opaque, original + 64, original_count, 8)) WRP_FAIL(UNREADABLE);
        if (wrp32(native_count) > 5 || memcmp(native_count, original_count, 4))
            WRP_DETAIL(LAYOUT, WOW64_COUNT);
        if (!read_memory(opaque, original, header, sizeof(header))) WRP_FAIL(UNREADABLE);
        unsigned id = wrp32(header);
        if (id >= AURA_REQUEST_COUNT) WRP_DETAIL(UNKNOWN_NAME, HEADER_ID);
        if (!read_memory(opaque, original, again, sizeof(header))) WRP_FAIL(UNREADABLE);
        if (memcmp(header, again, sizeof(header))) WRP_FAIL(CHANGED);
        if (!read_memory(opaque, v->request + 64, again, 8)) WRP_FAIL(UNREADABLE);
        if (memcmp(native_count, again, 4)) WRP_FAIL(CHANGED);
        if (!read_memory(opaque, original + 64, again, 8)) WRP_FAIL(UNREADABLE);
        if (memcmp(original_count, again, 4)) WRP_FAIL(CHANGED);
        if (!read_memory(opaque, v->sp + 0xb8, again, sizeof(saved))) WRP_FAIL(UNREADABLE);
        if (memcmp(saved, again, sizeof(saved))) WRP_FAIL(CHANGED);
        if (!read_memory(opaque, v->fp, again, sizeof(frame))) WRP_FAIL(UNREADABLE);
        if (memcmp(frame, again, sizeof(frame))) WRP_FAIL(CHANGED);
        WRP_CAPTURE(id, WOW64_HEADER);
    }

    /* Native callers still use a pinned immutable name outside the overwritten
     * reply union. Require the actual caller signature as well as name identity. */
    if (!read_memory(opaque, caller_lr - 16, code, 16)) WRP_FAIL(UNREADABLE);
    if (memcmp(code, caller->code, 16)) WRP_FAIL(CODE_MISMATCH);
    if (!read_memory(opaque, v->request + 160, name_slot, 8)) WRP_FAIL(UNREADABLE);
    uint64_t pointer = wrp64(name_slot);
    if (!pointer) WRP_DETAIL(UNKNOWN_NAME, NAME_NULL);
    if (pointer < v->base || pointer - v->base >= 0x200000) WRP_DETAIL(UNKNOWN_NAME, NAME_OUTSIDE);
    unsigned id = UINT32_MAX;
    for (unsigned i = 0; i < sizeof(request_name_sites) / sizeof(*request_name_sites); i++) {
        if (pointer - v->base == request_name_sites[i].offset) {
            id = request_name_sites[i].id; break;
        }
    }
    if (id == UINT32_MAX) WRP_DETAIL(UNKNOWN_NAME, NAME_UNLISTED);
    if (!read_memory(opaque, pointer, name, sizeof(name))) WRP_FAIL(UNREADABLE);
    size_t name_bytes = strlen(request_names[id]) + 1;
    if (memcmp(name, request_names[id], name_bytes)) WRP_DETAIL(UNKNOWN_NAME, NAME_TEXT);
    if (!read_memory(opaque, v->request + 160, again, 8)) WRP_FAIL(UNREADABLE);
    if (memcmp(name_slot, again, 8)) WRP_FAIL(CHANGED);
    if (!read_memory(opaque, pointer, again, sizeof(again))) WRP_FAIL(UNREADABLE);
    if (memcmp(name, again, name_bytes)) WRP_FAIL(CHANGED);
    if (!read_memory(opaque, v->fp, again, sizeof(frame))) WRP_FAIL(UNREADABLE);
    if (memcmp(frame, again, sizeof(frame))) WRP_FAIL(CHANGED);
    WRP_CAPTURE(id, NATIVE_NAME);
}
