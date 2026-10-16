/* Owned capture target only. The fixture supplies a serially guarded state and valid errno cell. */
.text
.p2align 2
.global tp_scalar_capture_entry
.hidden tp_scalar_capture_entry
.type tp_scalar_capture_entry,%function
tp_scalar_capture_entry:
    .cfi_startproc
    hint #34
    adrp x9,tp_scalar_capture_state
    add x9,x9,:lo12:tp_scalar_capture_state
    stp x0,x1,[x9,#0]
    stp x2,x3,[x9,#16]
    stp x4,x5,[x9,#32]
    stp x6,x7,[x9,#48]
    stp d0,d1,[x9,#64]
    stp d2,d3,[x9,#80]
    stp d4,d5,[x9,#96]
    stp d6,d7,[x9,#112]
    mov x10,sp
    str x10,[x9,#200]
    ldr w11,[x9,#192]
    cbz x11,2f
    add x12,x9,#128
1:
    ldr x13,[x10],#8
    str x13,[x12],#8
    subs x11,x11,#8
    b.ne 1b
2:
    ldr x10,[x9,#224]
    ldr w11,[x10]
    str w11,[x9,#232]
    ldr w11,[x9,#236]
    str w11,[x10]
    ldr x0,[x9,#208]
    ldr d0,[x9,#216]
    ret
    .cfi_endproc
.size tp_scalar_capture_entry,.-tp_scalar_capture_entry

.section .note.gnu.property,"a"
.p2align 3
.long 4,16,5
.asciz "GNU"
.long 0xc0000000,4,1,0
.section .note.GNU-stack,"",%progbits
