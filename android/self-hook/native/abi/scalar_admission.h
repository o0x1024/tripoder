#ifndef TP_SCALAR_ADMISSION_H
#define TP_SCALAR_ADMISSION_H
/* Shared with the assembly and module_lifetime.c: generation | live | refs. */
#define TP_MODULE_LIVE_BIT 0x80000000
#define TP_MODULE_REFERENCE_MASK 0x7fffffff
#ifndef __ASSEMBLER__
#include <stdatomic.h>
#include <stdint.h>
#include <stddef.h>
struct tp_scalar_admission {
    _Atomic uint64_t *state;
    uint64_t identity;
    _Atomic unsigned *active;
    _Atomic unsigned test_gate;
    unsigned reserved;
};
_Static_assert(sizeof(struct tp_scalar_admission)==32&&offsetof(struct tp_scalar_admission,active)==16
    &&offsetof(struct tp_scalar_admission,test_gate)==24,"A64 admission layout");
#endif
#endif
