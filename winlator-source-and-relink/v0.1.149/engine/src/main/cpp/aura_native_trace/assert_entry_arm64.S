/* Capture the registers at __assert2 entry, before a C prologue or logging
 * can overwrite the caller's volatile coordinates. x16/x17 may already have
 * been used by the PLT. x0..x3 contain the assertion's arguments, not fields.
 * No stack contents beyond these registers are copied. The C target never
 * returns: it delegates to Bionic's original assertion handler. */
.text
.p2align 2
.global __assert2
.type __assert2, %function
__assert2:
    .cfi_startproc
    hint #34 /* bti c */
    sub sp, sp, #256
    .cfi_def_cfa_offset 256
    stp x0, x1, [sp, #0]
    stp x2, x3, [sp, #16]
    stp x4, x5, [sp, #32]
    stp x6, x7, [sp, #48]
    stp x8, x9, [sp, #64]
    stp x10, x11, [sp, #80]
    stp x12, x13, [sp, #96]
    stp x14, x15, [sp, #112]
    stp x16, x17, [sp, #128]
    stp x18, x19, [sp, #144]
    stp x20, x21, [sp, #160]
    stp x22, x23, [sp, #176]
    stp x24, x25, [sp, #192]
    stp x26, x27, [sp, #208]
    stp x28, x29, [sp, #224]
    str x30, [sp, #240]
    .cfi_offset 29, -24
    .cfi_offset 30, -16
    add x9, sp, #256
    str x9, [sp, #248]
    mov x0, sp
    bl aura_trace_assert_entry
    brk #0
    .cfi_endproc
.size __assert2, .-__assert2
.section .note.GNU-stack,"",%progbits
