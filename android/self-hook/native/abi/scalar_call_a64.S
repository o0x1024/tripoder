/* AAPCS64 scalar call adapter. No entry patching, runtime code generation or target discovery. */
.text
.p2align 2
.global tp_a64_call_registers
.hidden tp_a64_call_registers
.type tp_a64_call_registers,%function
tp_a64_call_registers:
    .cfi_startproc
    hint #34
    stp x29,x30,[sp,#-48]!
    .cfi_def_cfa_offset 48
    .cfi_offset x29,-48
    .cfi_offset x30,-40
    mov x29,sp
    .cfi_def_cfa x29,48
    stp x19,x20,[sp,#16]
    stp x21,x22,[sp,#32]
    .cfi_offset x19,-32
    .cfi_offset x20,-24
    .cfi_offset x21,-16
    .cfi_offset x22,-8
    mov x20,x0
    mov x19,x1
    mov x21,x2
    mov w22,w4
    sub sp,sp,x22
    cbz x22,2f
    mov x9,x3
    mov x10,sp
    mov x11,x22
1:
    ldr x12,[x9],#8
    str x12,[x10],#8
    subs x11,x11,#8
    b.ne 1b
2:
    ldp d0,d1,[x19,#64]
    ldp d2,d3,[x19,#80]
    ldp d4,d5,[x19,#96]
    ldp d6,d7,[x19,#112]
    ldp x0,x1,[x19,#0]
    ldp x2,x3,[x19,#16]
    ldp x4,x5,[x19,#32]
    ldp x6,x7,[x19,#48]
    blr x20
    str x0,[x21,#0]
    str d0,[x21,#8]
    mov sp,x29
    ldp x19,x20,[sp,#16]
    ldp x21,x22,[sp,#32]
    .cfi_restore x19
    .cfi_restore x20
    .cfi_restore x21
    .cfi_restore x22
    ldp x29,x30,[sp],#48
    .cfi_def_cfa sp,0
    .cfi_restore x29
    .cfi_restore x30
    ret
    .cfi_endproc
.size tp_a64_call_registers,.-tp_a64_call_registers

.section .note.gnu.property,"a"
.p2align 3
.long 4,16,5
.asciz "GNU"
.long 0xc0000000,4,1,0
.section .note.GNU-stack,"",%progbits
