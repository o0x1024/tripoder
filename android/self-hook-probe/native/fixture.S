/* Audited fixture: each symbol owns a complete 4096-byte RX page.
 * No constructors, TLS, JNI, signal callbacks or other callers in this ELF.
 * Explicit BTI landing instruction, but no GNU guarded-page property yet. */
.section .text.fixture,"ax",%progbits
.balign 4096
.global tp_fixture_target
.type tp_fixture_target,%function
tp_fixture_target:
    bti c
    add x0, x0, #1
    ret
.size tp_fixture_target, .-tp_fixture_target
.rept 1021
    nop
.endr
.global tp_fixture_trampoline
.type tp_fixture_trampoline,%function
tp_fixture_trampoline:
.rept 1024
    nop
.endr
.size tp_fixture_trampoline, .-tp_fixture_trampoline
.global tp_fixture_replacement
.type tp_fixture_replacement,%function
tp_fixture_replacement:
    bti c
    adrp x16, tp_fixture_callback
    ldr x16, [x16, :lo12:tp_fixture_callback]
    br x16
.size tp_fixture_replacement, .-tp_fixture_replacement
.rept 1020
    nop
.endr
.section .data.fixture,"aw",%progbits
.balign 4096
.global tp_fixture_callback
.protected tp_fixture_callback
.type tp_fixture_callback,%object
tp_fixture_callback:
    .quad 0
    .zero 4088
.size tp_fixture_callback, .-tp_fixture_callback
.section .note.GNU-stack,"",%progbits
