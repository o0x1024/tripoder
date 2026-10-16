/* Prelinked owned protocol fixture; no executable patching or ART access.
 * Routed targets retain PACIASP and have a B in their second instruction.
 * Relays use PIC ADRP+ADD rather than a dynamic absolute literal; both preserve
 * SP and tail-BR through x16. Planner literal encoding has a separate oracle. */
.text
.macro PAC_TARGET name, kind, routed, relay
.p2align 4
.global \name
.hidden \name
.type \name,%function
\name:
.cfi_startproc
    paciasp
.cfi_negate_ra_state
.if \routed
    b \relay
.else
.if \kind == 0
    stp x29,x30,[sp,#-32]!
.else
    sub sp,sp,#32
.endif
.endif
.Lbody_\name:
.cfi_def_cfa_offset 32
.if \kind == 0
.cfi_offset x29,-32
.cfi_offset x30,-24
.else
    stp x29,x30,[sp,#16]
.cfi_offset x29,-16
.cfi_offset x30,-8
.endif
    mov x29,sp
    add x0,x0,x1
    add x0,x0,x2
    add x0,x0,x3
    add x0,x0,x4
    add x0,x0,x5
    add x0,x0,x6
    add x0,x0,x7
    ldr x9,[sp,#32]
    add x0,x0,x9
    fmov x9,d0
    eor x0,x0,x9
    fmov x9,d1
    eor x0,x0,x9
.if \kind == 0
    ldp x29,x30,[sp],#32
.else
    ldp x29,x30,[sp,#16]
.cfi_restore x29
.cfi_restore x30
    add sp,sp,#32
.endif
.cfi_restore x29
.cfi_restore x30
.cfi_def_cfa_offset 0
    autiasp
.cfi_negate_ra_state
    ret
.cfi_endproc
.size \name,.-\name
.endm

PAC_TARGET tp_pac_fixture_baseline_stp,0,0,unused
PAC_TARGET tp_pac_fixture_baseline_sub,1,0,unused
PAC_TARGET tp_pac_fixture_routed_stp,0,1,tp_pac_fixture_relay_stp
PAC_TARGET tp_pac_fixture_routed_sub,1,1,tp_pac_fixture_relay_sub

.macro PAC_ORIGINAL name, kind, target
.p2align 4
.global \name
.hidden \name
.type \name,%function
\name:
.cfi_startproc
    bti c
    paciasp
.cfi_negate_ra_state
.if \kind == 0
    stp x29,x30,[sp,#-32]!
.cfi_def_cfa_offset 32
.cfi_offset x29,-32
.cfi_offset x30,-24
.else
    sub sp,sp,#32
.cfi_def_cfa_offset 32
.endif
    b .Lbody_\target
.cfi_endproc
.size \name,.-\name
.endm
PAC_ORIGINAL tp_pac_fixture_original_stp,0,tp_pac_fixture_routed_stp
PAC_ORIGINAL tp_pac_fixture_original_sub,1,tp_pac_fixture_routed_sub

.macro PAC_RELAY name, replacement
.p2align 4
.global \name
.hidden \name
.type \name,%function
\name:
.cfi_startproc
/* LR is already signed by the original target at entry. */
.cfi_negate_ra_state
    bti c
    autiasp
.cfi_negate_ra_state
    adrp x16,\replacement
    add x16,x16,:lo12:\replacement
    br x16
.cfi_endproc
.size \name,.-\name
.endm
PAC_RELAY tp_pac_fixture_relay_stp,tp_pac_fixture_replace_stp
PAC_RELAY tp_pac_fixture_relay_sub,tp_pac_fixture_replace_sub

.p2align 4
.global tp_pac_fixture_observe_lr
.hidden tp_pac_fixture_observe_lr
.type tp_pac_fixture_observe_lr,%function
tp_pac_fixture_observe_lr:
.cfi_startproc
    bti c
    str x30,[x0]
    mov x9,sp
    str x9,[x0,#24]
    paciasp
.cfi_negate_ra_state
    str x30,[x0,#8]
    autiasp
.cfi_negate_ra_state
    str x30,[x0,#16]
    ret
.cfi_endproc
.size tp_pac_fixture_observe_lr,.-tp_pac_fixture_observe_lr

.section .note.gnu.property,"a",%note
.p2align 3
.long 4
.long 16
.long 5
.asciz "GNU"
.long 0xc0000000
.long 4
.long 3
.long 0
.section .note.GNU-stack,"",%progbits
