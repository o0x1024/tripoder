#ifndef TP_SIGNAL_USER_DELIVERY_H
#define TP_SIGNAL_USER_DELIVERY_H
#include <stdbool.h>
#include <stdint.h>
enum tp_signal_delivery_status {
    TP_SIGNAL_DELIVERY_OK,TP_SIGNAL_DELIVERY_STATE,TP_SIGNAL_DELIVERY_IMAGE,
    TP_SIGNAL_DELIVERY_PREIMAGE,TP_SIGNAL_DELIVERY_SLOT,TP_SIGNAL_DELIVERY_PARK,
    TP_SIGNAL_DELIVERY_IN_FLIGHT,TP_SIGNAL_DELIVERY_MASK,TP_SIGNAL_DELIVERY_PUBLICATION
};
/* Preguest, after ART has initialized real signal functions; before namespace
 * TSYNC. Reserves only SIGSYS in the pinned ART user-handler delivery role. */
enum tp_signal_delivery_status tp_signal_user_delivery_reserve(uintptr_t base,uintptr_t fixed_libc_base,uintptr_t real_mask);
void tp_signal_user_delivery_state(bool *ready,unsigned *status,uint64_t *roles,uint64_t *other);
#endif
