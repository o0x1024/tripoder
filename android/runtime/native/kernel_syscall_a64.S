.text
.p2align 4
.global tp_kernel_syscall
.hidden tp_kernel_syscall
.type tp_kernel_syscall,%function
tp_kernel_syscall:
    .cfi_startproc
    bti c
    mov x8,x0
    mov x0,x1
    mov x1,x2
    mov x2,x3
    mov x3,x4
    mov x4,x5
    mov x5,x6
    svc #0
.global tp_kernel_syscall_resume
.hidden tp_kernel_syscall_resume
tp_kernel_syscall_resume:
    cmn x0,#4095
    b.hs 1f
    ret
1:
    stp x19,x30,[sp,#-16]!
    .cfi_def_cfa_offset 16
    .cfi_offset x19,-16
    .cfi_offset x30,-8
    neg x19,x0
    bl __errno
    str w19,[x0]
    mov x0,#-1
    ldp x19,x30,[sp],#16
    .cfi_def_cfa_offset 0
    .cfi_restore x19
    .cfi_restore x30
    ret
    .cfi_endproc
.size tp_kernel_syscall,.-tp_kernel_syscall

/* A native caller may use the minimum pthread/signal stack. Path/proc policy
 * runs on an owned temporary RW stack, without malloc, TLS, or caller-stack growth. */
.p2align 4
.global tp_native_stack_call
.hidden tp_native_stack_call
.type tp_native_stack_call,%function
tp_native_stack_call:
    .cfi_startproc
    bti c
    stp x29,x30,[sp,#-80]!
    .cfi_def_cfa_offset 80
    .cfi_offset x29,-80
    .cfi_offset x30,-72
    stp x19,x20,[sp,#16]
    stp x21,x22,[sp,#32]
    stp x23,x24,[sp,#48]
    stp x25,x26,[sp,#64]
    .cfi_offset x19,-64
    .cfi_offset x20,-56
    .cfi_offset x21,-48
    .cfi_offset x22,-40
    .cfi_offset x23,-32
    .cfi_offset x24,-24
    .cfi_offset x25,-16
    .cfi_offset x26,-8
    mov x29,sp
    mov x23,sp
    mov x19,x0
    mov x20,x1
    mov x21,x2
    mov x22,x3
    bl tp_stack_acquire
    cbz x0,2f
    mov x24,x0
    add sp,x24,#64,lsl #12
    .cfi_def_cfa x23,80
    mov x0,x20
    mov x1,x21
    mov x2,x22
    blr x19
    mov x25,x0
    mov sp,x23
    .cfi_def_cfa sp,80
    mov x0,x24
    bl tp_stack_release
    mov x0,x25
    b 3f
2:
    mov x0,#-1
3:
    ldp x25,x26,[sp,#64]
    ldp x23,x24,[sp,#48]
    ldp x21,x22,[sp,#32]
    ldp x19,x20,[sp,#16]
    ldp x29,x30,[sp],#80
    .cfi_def_cfa_offset 0
    .cfi_restore x19
    .cfi_restore x20
    .cfi_restore x21
    .cfi_restore x22
    .cfi_restore x23
    .cfi_restore x24
    .cfi_restore x25
    .cfi_restore x26
    .cfi_restore x29
    .cfi_restore x30
    ret
    .cfi_endproc
.size tp_native_stack_call,.-tp_native_stack_call
.section .note.GNU-stack,"",%progbits
