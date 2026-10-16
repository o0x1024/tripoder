#include "module_lifetime.h"
#include "../abi/scalar_admission.h"
#define LIVE ((uint64_t)TP_MODULE_LIVE_BIT)
#define REFERENCES ((uint64_t)TP_MODULE_REFERENCE_MASK)
#define GENERATION UINT64_C(0xffffffff00000000)
_Static_assert(ATOMIC_LLONG_LOCK_FREE==2&&ATOMIC_POINTER_LOCK_FREE==2,"notifier requires inline lock-free atomics");
/* Only the serialized notifier writes presence/base/map. Ordinary threads may
 * acquire/release references. The same CAS decides removal versus acquisition;
 * a check followed by an unrelated increment would allow use-after-unmap. */
enum tp_module_change tp_module_reconcile(struct tp_module_slot *slot,struct tp_module_seen seen){
    if(!slot||(!seen.map!=!seen.base))return TP_MODULE_CHANGE_INVALID;
    uint64_t state=atomic_load_explicit(&slot->state,memory_order_acquire);
    if(state&LIVE){
        if(seen.map==atomic_load_explicit(&slot->map,memory_order_relaxed)
            &&seen.base==atomic_load_explicit(&slot->base,memory_order_relaxed))return TP_MODULE_CHANGE_OK;
        /* Replacing a present node without an intervening removal is not a
         * legitimate generation transition for this fixed notifier contract. */
        if(seen.map)return TP_MODULE_CHANGE_INVALID;
        for(;;){
            if(state&REFERENCES)return TP_MODULE_CHANGE_IN_USE;
            uint64_t invalid=state&GENERATION;
            if(atomic_compare_exchange_weak_explicit(&slot->state,&state,invalid,memory_order_acq_rel,memory_order_acquire))break;
        }
        return TP_MODULE_CHANGE_OK;
    }
    if(state&REFERENCES)return TP_MODULE_CHANGE_INVALID;
    if(!seen.map)return TP_MODULE_CHANGE_OK;
    uint32_t generation=(uint32_t)(state>>32);
    if(generation==UINT32_MAX)return TP_MODULE_CHANGE_EXHAUSTED;
    atomic_store_explicit(&slot->map,seen.map,memory_order_relaxed);
    atomic_store_explicit(&slot->base,seen.base,memory_order_relaxed);
    atomic_store_explicit(&slot->state,((uint64_t)(generation+1)<<32)|LIVE,memory_order_release);
    return TP_MODULE_CHANGE_OK;
}
bool tp_module_snapshot(const struct tp_module_slot *slot,struct tp_module_snapshot *out){
    if(!slot||!out)return false;
    /* Bounded observation; churn means retry at the control boundary, never
     * report an identity assembled from two different generations. */
    for(unsigned attempt=0;attempt<32;attempt++){
        uint64_t before=atomic_load_explicit(&slot->state,memory_order_acquire);
        uintptr_t base=atomic_load_explicit(&slot->base,memory_order_relaxed);
        uintptr_t map=atomic_load_explicit(&slot->map,memory_order_relaxed);
        uint64_t after=atomic_load_explicit(&slot->state,memory_order_acquire);
        if((before&~REFERENCES)==(after&~REFERENCES)){*out=(struct tp_module_snapshot){.generation=(uint32_t)(after>>32),
            .references=(uint32_t)(after&REFERENCES),.base=(after&LIVE)?base:0,.map=(after&LIVE)?map:0,.live=(after&LIVE)!=0};return true;}
    }
    return false;
}
bool tp_module_acquire(struct tp_module_slot *slot,uint32_t generation){
    if(!slot||!generation)return false;
    uint64_t state=atomic_load_explicit(&slot->state,memory_order_acquire);
    for(;;){
        if(!(state&LIVE)||(state>>32)!=generation||(state&REFERENCES)==REFERENCES)return false;
        if(atomic_compare_exchange_weak_explicit(&slot->state,&state,state+1,memory_order_acq_rel,memory_order_acquire))return true;
    }
}
bool tp_module_release(struct tp_module_slot *slot,uint32_t generation){
    if(!slot||!generation)return false;
    uint64_t state=atomic_load_explicit(&slot->state,memory_order_acquire);
    for(;;){
        if(!(state&LIVE)||(state>>32)!=generation||!(state&REFERENCES))return false;
        if(atomic_compare_exchange_weak_explicit(&slot->state,&state,state-1,memory_order_acq_rel,memory_order_acquire))return true;
    }
}
