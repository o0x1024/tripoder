#ifndef TRIPODER_NEAR_ADDRESS_H
#define TRIPODER_NEAR_ADDRESS_H
#include <stddef.h>
#include <stdint.h>
/* Inclusive mapping-start bounds; bounded MAP_FIXED_NOREPLACE candidates.
 * Only EEXIST advances to another candidate. Never call while parked. */
void *tp_near_address_reserve_range(uintptr_t low,uintptr_t high,uintptr_t preferred,size_t bytes,int protection);
/* Platform callers explicitly use the 127 MiB branch window. */
void *tp_near_address_reserve(uintptr_t target,size_t bytes,int protection);
#endif
