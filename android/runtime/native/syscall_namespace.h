#ifndef TRIPODER_SYSCALL_NAMESPACE_H
#define TRIPODER_SYSCALL_NAMESPACE_H
#include <stdint.h>
#include <signal.h>
#include <stdbool.h>
int tp_syscall_namespace_install(void);
int tp_syscall_namespace_install_with_signal_chain(int (*claim)(int,bool (*)(int,siginfo_t *,void *)));
void tp_syscall_namespace_counts(uint64_t counts[4]);
void tp_syscall_namespace_dump(int fd);
void tp_syscall_namespace_enable_audit(void);
#endif
