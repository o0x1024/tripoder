#ifndef TRIPODER_SIGNAL_CHAIN_H
#define TRIPODER_SIGNAL_CHAIN_H
#include <stdbool.h>
#include <stdint.h>
#include <signal.h>
bool tp_signal_chain_prepare(void);
bool tp_signal_chain_module_added(uintptr_t base);
int tp_signal_chain_claim(int signal,bool (*handler)(int,siginfo_t *,void *));
uint64_t tp_signal_chain_claims(void);
void tp_signal_chain_state(bool *ready,uint64_t *patched,uint64_t *checked);
#endif
