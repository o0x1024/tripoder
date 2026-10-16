#ifndef TP_NATIVE_BRIDGE_H
#define TP_NATIVE_BRIDGE_H
#include "native_prefix.h"
struct tp_native_bridge {
    uintptr_t base,relay,original;
    struct tp_native_prefix plan;
    unsigned seals,fde_pcs;
    int fd,error;
    void *handle;
    const char *stage;
};
bool tp_native_bridge_prepare(const uint32_t *input,size_t available,uintptr_t entry,void (*callback)(void),struct tp_native_bridge *);
#endif
