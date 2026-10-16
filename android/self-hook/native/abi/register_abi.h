#ifndef TP_REGISTER_ABI_H
#define TP_REGISTER_ABI_H
#include "scalar_abi.h"
/* Trusted-core register transport. JNI has two hidden GPR parameters in
 * addition to the Java SDK's 32 parameters, so it may spill twenty-six words.
 * JNI handles never enter the public scalar/script value interface. */
#define TP_REGISTER_STACK_BYTES 208u
struct tp_register_arguments {uint64_t gpr[8],fpr[8];const uint64_t *stack;unsigned stack_size;};
bool tp_register_call(void (*target)(void),const struct tp_register_arguments *,struct tp_scalar_return *);
void tp_a64_call_registers(void (*target)(void),const void *registers,struct tp_scalar_return *,const uint64_t *stack,unsigned stack_size);
#endif
