#include "../../include/tripoder_hook_limits.h"
#include "scalar_admission.h"
/* ABI ingress: x16 is the AAPCS64 intra-procedure scratch register. The linker
 * veneer may also use it. No argument register or incoming stack slot is lost. */
.text
.p2align 4
.global tp_scalar_inbound_entries
.hidden tp_scalar_inbound_entries
.type tp_scalar_inbound_entries,%function
tp_scalar_inbound_entries:
    .cfi_startproc
    .set slot,0
    .rept TP_NATIVE_ENTRY_LIMIT
    bti jc
    mov w16,#slot
    b .Lscalar_inbound_common
    nop
    .set slot,slot+1
    .endr
    .cfi_endproc
.size tp_scalar_inbound_entries,.-tp_scalar_inbound_entries
.global tp_scalar_inbound_entries_end
.hidden tp_scalar_inbound_entries_end
tp_scalar_inbound_entries_end:
.p2align 2
.global tp_scalar_inbound_common
.hidden tp_scalar_inbound_common
.type tp_scalar_inbound_common,%function
tp_scalar_inbound_common:
.Lscalar_inbound_common:
    .cfi_startproc
    bti j
    paciasp
    .cfi_negate_ra_state
    stp x29,x30,[sp,#-240]!
    .cfi_def_cfa_offset 240
    .cfi_offset x29,-240
    .cfi_offset x30,-232
    mov x29,sp
    stp x0,x1,[sp,#16]
    stp x2,x3,[sp,#32]
    stp x4,x5,[sp,#48]
    stp x6,x7,[sp,#64]
    stp d0,d1,[sp,#80]
    stp d2,d3,[sp,#96]
    stp d4,d5,[sp,#112]
    stp d6,d7,[sp,#128]
    str w16,[sp,#232]
    mov w0,w16
    bl .Lscalar_admit
.global tp_scalar_inbound_admitted
.hidden tp_scalar_inbound_admitted
tp_scalar_inbound_admitted:
    ldr w0,[sp,#232]
    add x1,sp,#16
    add x2,sp,#240
    add x3,sp,#216
    bl tp_scalar_inbound_dispatch
    ldr w0,[sp,#232]
    bl .Lscalar_leave
    ldr x0,[sp,#216]
    ldr d0,[sp,#224]
    ldp x29,x30,[sp],#240
    .cfi_restore x29
    .cfi_restore x30
    .cfi_def_cfa_offset 0
    autiasp
    .cfi_negate_ra_state
    ret
    .cfi_endproc
.size tp_scalar_inbound_common,.-tp_scalar_inbound_common
/* No TLS, errno helper, allocator or C call before acquiring the module lease.
 * A parked PC in the relay, slots, pre-admitted prologue or this leaf makes
 * retirement BUSY without changing any entry. All later PCs own a lease. */
.p2align 2
.global tp_scalar_inbound_admit
.hidden tp_scalar_inbound_admit
.type tp_scalar_inbound_admit,%function
tp_scalar_inbound_admit:
.Lscalar_admit:
    .cfi_startproc
    adrp x1,tp_scalar_admissions
    add x1,x1,:lo12:tp_scalar_admissions
    add x1,x1,x0,lsl #5
    ldr x2,[x1]
    cbz x2,.Ladmit_done
#ifdef TP_SELF_HOOK_TESTING
    add x7,x1,#24
    ldar w6,[x7]
    cbz w6,.Lgate_done
    mov w6,#2
    stlr w6,[x7]
.Lgate_wait:
    yield
    ldar w6,[x7]
    cbnz w6,.Lgate_wait
.Lgate_done:
#endif
    ldr x3,[x1,#8]
.Lacquire:
    ldaxr x4,[x2]
    and x5,x4,#0xffffffff80000000
    cmp x5,x3
    b.ne .Ladmit_invalid
    and x5,x4,#TP_MODULE_REFERENCE_MASK
    mov x6,#TP_MODULE_REFERENCE_MASK
    cmp x5,x6
    b.eq .Ladmit_invalid
    add x5,x4,#1
    stlxr w6,x5,[x2]
    cbnz w6,.Lacquire
    ldr x2,[x1,#16]
.Lactive_add:
    ldaxr w4,[x2]
    add w5,w4,#1
    cbz w5,.Ladmit_invalid
    stlxr w6,w5,[x2]
    cbnz w6,.Lactive_add
.Ladmit_done:
    ret
.Ladmit_invalid:
    clrex
    mov x0,#226
    mov x8,#94
    svc #0
    brk #0
    .cfi_endproc
.size tp_scalar_inbound_admit,.-tp_scalar_inbound_admit
.global tp_scalar_inbound_admit_end
.hidden tp_scalar_inbound_admit_end
tp_scalar_inbound_admit_end:
.p2align 2
.type tp_scalar_inbound_leave,%function
tp_scalar_inbound_leave:
.Lscalar_leave:
    .cfi_startproc
    adrp x1,tp_scalar_admissions
    add x1,x1,:lo12:tp_scalar_admissions
    add x1,x1,x0,lsl #5
    ldr x2,[x1]
    cbz x2,.Lleave_done
    ldr x7,[x1,#16]
.Lactive_sub:
    ldaxr w4,[x7]
    cbz w4,.Lleave_invalid
    sub w5,w4,#1
    stlxr w6,w5,[x7]
    cbnz w6,.Lactive_sub
    ldr x3,[x1,#8]
.Lrelease:
    ldaxr x4,[x2]
    and x5,x4,#0xffffffff80000000
    cmp x5,x3
    b.ne .Lleave_invalid
    and x5,x4,#TP_MODULE_REFERENCE_MASK
    cbz x5,.Lleave_invalid
    sub x5,x4,#1
    stlxr w6,x5,[x2]
    cbnz w6,.Lrelease
.Lleave_done:
    ret
.Lleave_invalid:
    clrex
    mov x0,#227
    mov x8,#94
    svc #0
    brk #0
    .cfi_endproc
.size tp_scalar_inbound_leave,.-tp_scalar_inbound_leave
.section .note.gnu.property,"a",%note
.p2align 3
.long 4,16,5
.asciz "GNU"
.long 0xc0000000,4,3,0
.section .note.GNU-stack,"",%progbits
