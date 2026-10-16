#include "register_abi.h"
#include <stddef.h>
_Static_assert(offsetof(struct tp_register_arguments,gpr)==0&&offsetof(struct tp_register_arguments,fpr)==64,"register bank ABI");
void tp_scalar_call_frame(void (*target)(void),const struct tp_scalar_frame *frame,struct tp_scalar_return *out){
    tp_a64_call_registers(target,frame,out,frame->stack,frame->stack_size);
}
bool tp_register_call(void (*target)(void),const struct tp_register_arguments *args,struct tp_scalar_return *out){
    if(!target||((uintptr_t)target&3u)||((uintptr_t)target>>48)||!args||!out
        ||args->stack_size>TP_REGISTER_STACK_BYTES||(args->stack_size&15u)||(args->stack_size&&!args->stack))return false;
    tp_a64_call_registers(target,args,out,args->stack,args->stack_size);return true;
}
