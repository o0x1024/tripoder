#include "../../include/tripoder_hook_limits.h"
/* Immutable near ELF template. Only file bytes are patched before sealing/loading. */
.text
.p2align 2
.global tp_art_entry_leaf
.hidden tp_art_entry_leaf
.type tp_art_entry_leaf,%function
tp_art_entry_leaf:
    /* Original signed LR and signing PC remain intact. No BL, AUT or PAC. */
    bti j
    adrp x10,tp_art_leaf_registry
    ldr x10,[x10,:lo12:tp_art_leaf_registry]
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
    stp x20,x19,[sp,#16]
.Lleaf_post:
.global tp_art_leaf_resume
.hidden tp_art_leaf_resume
tp_art_leaf_resume:
    b tp_art_leaf_resume
.Lreject:
    brk #0x534
    b .Lreject
.Lleaf_end:
.size tp_art_entry_leaf,.-tp_art_entry_leaf

.section .rodata,"a",%progbits
.p2align 3
.global tp_art_leaf_registry
.hidden tp_art_leaf_registry
tp_art_leaf_registry:
.quad 0
/* The original PACIASP and frame allocation execute at their original PCs.
 * Every leaf PC has the same SP+32 CFA and signed original return address.
 * x19/x20 remain unchanged even after replaying their save, so same_value is
 * valid throughout. The original FDE resumes with stack-save rules at +12. */
.section .eh_frame,"a",%progbits
.p2align 3
.Lcie_frame:
    .long .Lcie_end-.Lcie_body
.Lcie_body:
    .long 0
    .byte 1
    .asciz "zR"
    .uleb128 1
    .sleb128 -8
    .byte 30
    .uleb128 1
    .byte 0x1b
    .byte 0x0c,31,32
    .byte 0x9d,4
    .byte 0x9e,3
    .byte 0x08,19,0x08,20
    .byte 0x2d
    .p2align 2
.Lcie_end:
.Lfde:
    .long .Lfde_end-.Lfde_body
.Lfde_body:
.Lfde_cie:
    .long .Lfde_cie-.Lcie_frame
    .long tp_art_entry_leaf-.
    .long .Lleaf_end-tp_art_entry_leaf
    .uleb128 0
    .p2align 2
.Lfde_end:
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
