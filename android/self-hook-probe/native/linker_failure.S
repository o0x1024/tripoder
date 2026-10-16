/* Valid ELF with an unresolved function relocation: Read/Load succeed and
 * linking fails. It has no constructors/destructors or imported dependencies. */
.text
.p2align 2
.global tp_linker_failure
.type tp_linker_failure,%function
tp_linker_failure:
    bti c
    b tp_owned_missing_symbol
.size tp_linker_failure,.-tp_linker_failure
.section .note.GNU-stack,"",%progbits
