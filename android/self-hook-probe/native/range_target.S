.text
.p2align 2
.macro FUNC name
.global \name
.type \name,%function
\name:
.endm
FUNC tp_range_tbz
    tbz w0,#0,1f
    add w0,w0,#10
    ret
1:  add w0,w0,#20
    ret
.size tp_range_tbz,.-tp_range_tbz
FUNC tp_range_tbnz
    tbnz w0,#0,1f
    add w0,w0,#30
    ret
1:  add w0,w0,#40
    ret
.size tp_range_tbnz,.-tp_range_tbnz
FUNC tp_range_cbz
    cbz w0,1f
    add w0,w0,#50
    ret
1:  mov w0,#60
    ret
.size tp_range_cbz,.-tp_range_cbz
FUNC tp_range_b
    b 1f
    brk #0
1:  add w0,w0,#70
    ret
.size tp_range_b,.-tp_range_b
FUNC tp_range_literal
    ldr w0,1f
    ret
1:  .word 81
.size tp_range_literal,.-tp_range_literal
FUNC tp_range_adr
    adr x0,.Ldatum
    ret
.size tp_range_adr,.-tp_range_adr
FUNC tp_range_adrp
    adrp x0,.Ldatum
    add x0,x0,:lo12:.Ldatum
    ret
.size tp_range_adrp,.-tp_range_adrp
.section .rodata,"a",%progbits
.p2align 3
.Ldatum: .quad 0x123456789abcdef0
.section .note.GNU-stack,"",%progbits
