#ifndef TP_ART_INTERPRETER_POLICY_H
#define TP_ART_INTERPRETER_POLICY_H
#include "art_access.h"
#include <stdatomic.h>

/* One process-lifetime ART client. Binding never changes execution policy.
 * Claim/validation requires JIT suspension and exclusive mutator ownership.
 * There is intentionally no release: retired relays/backups remain reachable. */
struct tp_art_interpreter_policy {
    void *instrumentation;
    int (*level)(void *);
    void (*deopt_all)(void *,const char *);
    bool bound;
    _Atomic bool claimed;
    _Atomic int observed_level;
    _Atomic unsigned claims;
};
extern const char tp_art_interpreter_client[];
bool tp_art_interpreter_bind(const struct tp_art_deopt_access *,struct tp_art_interpreter_policy *);
bool tp_art_interpreter_claim_locked(struct tp_art_interpreter_policy *);
#endif
