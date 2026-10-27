#ifndef TRIPODER_KERNEL_SYSCALL_H
#define TRIPODER_KERNEL_SYSCALL_H
long tp_kernel_syscall(long number,...);
extern char tp_kernel_syscall_resume[];
long tp_native_stack_call(void *function,long a0,long a1,long a2);
#endif
