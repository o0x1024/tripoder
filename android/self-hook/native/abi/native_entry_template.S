/* File-only patches fill one decoded prefix and its exact unwind effects.
 * All code and FDE bytes are sealed before dlopen. No executable mutation. */
.text
.p2align 4
.global tp_native_relay
.hidden tp_native_relay
.type tp_native_relay,%function
tp_native_relay:
    bti jc
.global tp_native_auth
.hidden tp_native_auth
.type tp_native_auth,%object
tp_native_auth:
    nop
.size tp_native_auth,4
    ldr x16,tp_native_callback
    br x16
.Lrelay_end:
.size tp_native_relay,.-tp_native_relay
.p2align 2
.global tp_native_original
.hidden tp_native_original
.type tp_native_original,%function
tp_native_original:
    bti c
.global tp_native_prefix_instruction
.hidden tp_native_prefix_instruction
.type tp_native_prefix_instruction,%object
tp_native_prefix_instruction:
    nop
.size tp_native_prefix_instruction,4
.global tp_native_displaced
.hidden tp_native_displaced
.type tp_native_displaced,%object
tp_native_displaced:
    nop
.size tp_native_displaced,4
.global tp_native_back
.hidden tp_native_back
.type tp_native_back,%object
tp_native_back:
    b tp_native_back
.size tp_native_back,4
.Loriginal_end:
.size tp_native_original,.-tp_native_original
.section .rodata,"a",%progbits
.p2align 3
.global tp_native_callback
.hidden tp_native_callback
.type tp_native_callback,%object
tp_native_callback:
    .quad 0
.size tp_native_callback,8
.section .eh_frame,"a",%progbits
.p2align 3
.Lcie:
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
    .byte 0x0c,31,0
    .byte 0x08,30
    .p2align 2
.Lcie_end:
.Lfde_relay:
    .long .Lfde_relay_end-.Lfde_relay_body
.Lfde_relay_body:
.Lfde_relay_cie:
    .long .Lfde_relay_cie-.Lcie
    .long tp_native_relay-.
    .long .Lrelay_end-tp_native_relay
    .uleb128 0
.global tp_native_relay_ra_start
.hidden tp_native_relay_ra_start
.type tp_native_relay_ra_start,%object
tp_native_relay_ra_start:
    .byte 0
.size tp_native_relay_ra_start,1
    .byte 0x48
.global tp_native_relay_ra_end
.hidden tp_native_relay_ra_end
.type tp_native_relay_ra_end,%object
tp_native_relay_ra_end:
    .byte 0
.size tp_native_relay_ra_end,1
    .p2align 2
.Lfde_relay_end:
.Lfde_original:
    .long .Lfde_original_end-.Lfde_original_body
.Lfde_original_body:
.Lfde_original_cie:
    .long .Lfde_original_cie-.Lcie
    .long tp_native_original-.
    .long .Loriginal_end-tp_native_original
    .uleb128 0
    .byte 0x48
.global tp_native_early_ra_state
.hidden tp_native_early_ra_state
.type tp_native_early_ra_state,%object
tp_native_early_ra_state:
    .byte 0
.size tp_native_early_ra_state,1
    .byte 0x44
.global tp_native_ra_state
.hidden tp_native_ra_state
.type tp_native_ra_state,%object
tp_native_ra_state:
    .byte 0
.size tp_native_ra_state,1
    .byte 0x0e
.global tp_native_frame
.hidden tp_native_frame
.type tp_native_frame,%object
tp_native_frame:
    .byte 0x80,0
.size tp_native_frame,2
.global tp_native_saved1
.hidden tp_native_saved1
.type tp_native_saved1,%object
tp_native_saved1:
    .byte 0x08,30,0
.size tp_native_saved1,3
.global tp_native_saved2
.hidden tp_native_saved2
.type tp_native_saved2,%object
tp_native_saved2:
    .byte 0x08,30,0
.size tp_native_saved2,3
    .p2align 2
.Lfde_original_end:
.section .note.gnu.property,"a",%note
.p2align 3
.long 4,16,5
.asciz "GNU"
.long 0xc0000000,4,3,0
.section .note.GNU-stack,"",%progbits
