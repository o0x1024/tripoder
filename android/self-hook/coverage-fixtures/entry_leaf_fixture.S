/* Prelinked owned fixture only. No live ART page is modified by this file. */
#include "../include/tripoder_hook_limits.h"
.text
.p2align 2
.global tp_entry_fixture_routed
.hidden tp_entry_fixture_routed
.type tp_entry_fixture_routed,%function
tp_entry_fixture_routed:
    .cfi_startproc
    paciasp
    .cfi_negate_ra_state
    b tp_entry_fixture_leaf
.Lbody:
    .cfi_def_cfa_offset 32
    .cfi_offset 29,-32
    .cfi_offset 30,-24
    mov x29,sp
    bl tp_entry_fixture_commit
    ldp x29,x30,[sp],#32
    .cfi_def_cfa_offset 0
    .cfi_restore 29
    .cfi_restore 30
    autiasp
    .cfi_negate_ra_state
    ret
    .cfi_endproc
.size tp_entry_fixture_routed,.-tp_entry_fixture_routed

/* Dedicated owned file page; same fixed prologue as the real writer. */
.p2align 12
.global tp_entry_fixture_plain
.hidden tp_entry_fixture_plain
.type tp_entry_fixture_plain,%function
tp_entry_fixture_plain:
    .cfi_startproc
    paciasp
    .cfi_negate_ra_state
    stp x29,x30,[sp,#-32]!
    .cfi_def_cfa_offset 32
    .cfi_offset 29,-32
    .cfi_offset 30,-24
    stp x20,x19,[sp,#16]
    .cfi_offset 20,-16
    .cfi_offset 19,-8
    mov x29,sp
    bl tp_entry_fixture_commit
    ldp x20,x19,[sp,#16]
    .cfi_restore 20
    .cfi_restore 19
    ldp x29,x30,[sp],#32
    .cfi_def_cfa_offset 0
    .cfi_restore 29
    .cfi_restore 30
    autiasp
    .cfi_negate_ra_state
    ret
    .cfi_endproc
.size tp_entry_fixture_plain,.-tp_entry_fixture_plain
.fill (4096-(.-tp_entry_fixture_plain))/4,4,0xd503201f

.p2align 2
.global tp_entry_fixture_leaf
.hidden tp_entry_fixture_leaf
.type tp_entry_fixture_leaf,%function
tp_entry_fixture_leaf:
    /* Original signed LR and signing PC remain intact. No BL, AUT or PAC. */
    adrp x10,tp_art_entry_owners
    add x10,x10,:lo12:tp_art_entry_owners
    mov w9,#TP_MANAGED_ENTRY_LIMIT
.Lscan:
    ldar x11,[x10]
    cbz x11,.Lnext
    ldr x12,[x11]
    cmp x12,x0
    b.ne .Lnext
    ldr x13,[x11,#24]
    ldar x14,[x13]
    cbz x14,.Lreject
    tst x14,#3
    b.ne .Lreject
    mov x1,x14
    b .Lreplay
.Lnext:
    add x10,x10,#8
    subs w9,w9,#1
    b.ne .Lscan
.Lreplay:
    stp x29,x30,[sp,#-32]!
.Lleaf_post:
    b .Lbody
.Lreject:
    brk #0x534
    b .Lreject
.Lleaf_end:
.size tp_entry_fixture_leaf,.-tp_entry_fixture_leaf

.p2align 2
.global tp_entry_sample_call
.hidden tp_entry_sample_call
.type tp_entry_sample_call,%function
tp_entry_sample_call:
    .cfi_startproc
    paciasp
    .cfi_negate_ra_state
    stp x29,x30,[sp,#-16]!
    .cfi_def_cfa_offset 16
    .cfi_offset 29,-16
    .cfi_offset 30,-8
    mov x29,sp
    bl tp_entry_fixture_routed
.global tp_entry_sample_return
.hidden tp_entry_sample_return
tp_entry_sample_return:
    ldp x29,x30,[sp],#16
    .cfi_def_cfa_offset 0
    .cfi_restore 29
    .cfi_restore 30
    autiasp
    .cfi_negate_ra_state
    ret
    .cfi_endproc
.size tp_entry_sample_call,.-tp_entry_sample_call

.p2align 2
.global tp_entry_plain_sample_call
.hidden tp_entry_plain_sample_call
.type tp_entry_plain_sample_call,%function
tp_entry_plain_sample_call:
    .cfi_startproc
    paciasp
    .cfi_negate_ra_state
    stp x29,x30,[sp,#-16]!
    .cfi_def_cfa_offset 16
    .cfi_offset 29,-16
    .cfi_offset 30,-8
    mov x29,sp
    bl tp_entry_fixture_plain
.global tp_entry_plain_sample_return
.hidden tp_entry_plain_sample_return
tp_entry_plain_sample_return:
    ldp x29,x30,[sp],#16
    .cfi_def_cfa_offset 0
    .cfi_restore 29
    .cfi_restore 30
    autiasp
    .cfi_negate_ra_state
    ret
    .cfi_endproc
.size tp_entry_plain_sample_call,.-tp_entry_plain_sample_call

.arch_extension pauth
.p2align 2
.global tp_entry_sign_probe
.hidden tp_entry_sign_probe
.type tp_entry_sign_probe,%function
tp_entry_sign_probe:
    .cfi_startproc
    bti c
    pacia x0,x1
    ret
    .cfi_endproc
.size tp_entry_sign_probe,.-tp_entry_sign_probe

/* Constant-state FDEs: exact signal PCs must not depend on an FDE's
 * same-address row transition. Rules belong to the CIE initial state. */
.section .eh_frame,"a",%progbits
.p2align 3
.Lcie_signed:
    .long .Lcie_signed_end-.Lcie_signed_body
.Lcie_signed_body:
    .long 0
    .byte 1
    .asciz "zR"
    .uleb128 1
    .sleb128 -8
    .byte 30
    .uleb128 1
    .byte 0x1b
    .byte 0x0c,31,0       /* def_cfa SP,0 */
    .byte 0x08,29,0x08,30 /* same_value FP/LR */
    .byte 0x2d            /* AARCH64 negate_ra_state: signed */
    .p2align 2
.Lcie_signed_end:
.Lfde_pre:
    .long .Lfde_pre_end-.Lfde_pre_body
.Lfde_pre_body:
.Lfde_pre_cie:
    .long .Lfde_pre_cie-.Lcie_signed
    .long tp_entry_fixture_leaf-.
    .long .Lleaf_post-tp_entry_fixture_leaf
    .uleb128 0
    .p2align 2
.Lfde_pre_end:
.Lfde_reject:
    .long .Lfde_reject_end-.Lfde_reject_body
.Lfde_reject_body:
.Lfde_reject_cie:
    .long .Lfde_reject_cie-.Lcie_signed
    .long .Lreject-.
    .long .Lleaf_end-.Lreject
    .uleb128 0
    .p2align 2
.Lfde_reject_end:
.Lcie_frame:
    .long .Lcie_frame_end-.Lcie_frame_body
.Lcie_frame_body:
    .long 0
    .byte 1
    .asciz "zR"
    .uleb128 1
    .sleb128 -8
    .byte 30
    .uleb128 1
    .byte 0x1b
    .byte 0x0c,31,32      /* def_cfa SP,32 */
    .byte 0x9d,4         /* FP at CFA-32 */
    .byte 0x9e,3         /* LR at CFA-24 */
    .byte 0x2d
    .p2align 2
.Lcie_frame_end:
.Lfde_post:
    .long .Lfde_post_end-.Lfde_post_body
.Lfde_post_body:
.Lfde_post_cie:
    .long .Lfde_post_cie-.Lcie_frame
    .long .Lleaf_post-.
    .long .Lreject-.Lleaf_post
    .uleb128 0
    .p2align 2
.Lfde_post_end:
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
