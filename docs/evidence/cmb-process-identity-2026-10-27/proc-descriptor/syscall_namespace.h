#ifndef TRIPODER_SYSCALL_NAMESPACE_H
#define TRIPODER_SYSCALL_NAMESPACE_H
#include <stdint.h>
#include <signal.h>
#include <stdbool.h>
#define TP_NAMESPACE_TRAP_DATA 0x5450
int tp_syscall_namespace_install(void);
void tp_syscall_namespace_trace_debugger(bool enabled);
int tp_syscall_namespace_install_with_signal_chain(int (*claim)(int,bool (*)(int,siginfo_t *,void *)),int (*reserve_delivery)(void),int (*user_action)(int,const struct sigaction *,struct sigaction *));
void tp_syscall_namespace_counts(uint64_t counts[4]);
void tp_syscall_namespace_set_policy(bool block_execve, bool block_kill, bool network_sinkhole);
void tp_syscall_namespace_get_policy(bool *block_execve, bool *block_kill, bool *network_sinkhole, uint64_t policy_counts[6]);
void tp_syscall_namespace_dump(int fd);
void tp_syscall_namespace_enable_audit(void);
void tp_syscall_namespace_set_audit_fd(int fd);
int tp_syscall_namespace_get_audit_fd(void);
#endif
