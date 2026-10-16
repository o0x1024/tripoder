#include "art_interpreter_policy.h"
#include "art_interpreter_contract.h"

const char tp_art_interpreter_client[]="tripoder-managed-process-interpreter";
bool tp_art_interpreter_bind(const struct tp_art_deopt_access *art,struct tp_art_interpreter_policy *policy){
    if(!art||!policy||policy->bound||!art->instrumentation)return false;
#define CHECK(name,NAME) if(!tp_art_validate_code(&art->managed.access,TP_GLOBAL_##NAME##_RVA,tp_global_##name##_bytes,sizeof(tp_global_##name##_bytes)))return false
    CHECK(level,LEVEL);CHECK(deopt_all,DEOPT_ALL);CHECK(configure,CONFIGURE);
#undef CHECK
    policy->instrumentation=art->instrumentation;
    policy->level=(int (*)(void *))(art->managed.access.base+TP_GLOBAL_LEVEL_RVA);
    policy->deopt_all=(void (*)(void *,const char *))(art->managed.access.base+TP_GLOBAL_DEOPT_ALL_RVA);
    atomic_init(&policy->claimed,false);atomic_init(&policy->observed_level,-1);atomic_init(&policy->claims,0);
    policy->bound=true;return true;
}
bool tp_art_interpreter_claim_locked(struct tp_art_interpreter_policy *policy){
    if(!policy||!policy->bound||!policy->instrumentation||!policy->level||!policy->deopt_all)return false;
    int before=policy->level(policy->instrumentation);
    atomic_store_explicit(&policy->observed_level,before,memory_order_relaxed);
    if(atomic_load_explicit(&policy->claimed,memory_order_acquire))return before==2;
    // A foreign tracing/deoptimization owner is not our lease. Never adopt it.
    if(before!=0)return false;
    policy->deopt_all(policy->instrumentation,tp_art_interpreter_client);
    int after=policy->level(policy->instrumentation);
    atomic_store_explicit(&policy->observed_level,after,memory_order_relaxed);
    if(after!=2)return false;
    atomic_store_explicit(&policy->claims,1,memory_order_relaxed);
    atomic_store_explicit(&policy->claimed,true,memory_order_release);return true;
}
