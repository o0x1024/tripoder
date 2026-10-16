#ifndef TP_NEAR_NOTIFIER_H
#define TP_NEAR_NOTIFIER_H
#include <stdbool.h>
#include <stdint.h>
struct tp_near_notifier {
    uintptr_t base,entry;
    unsigned seals,fde_pcs;
    int fd,error;
    void *handle;
    const char *stage;
};
/* Dedicated audited template, retained after any successful ELF load. */
bool tp_near_notifier_prepare(uintptr_t origin,void (*callback)(void),struct tp_near_notifier *);
#endif
