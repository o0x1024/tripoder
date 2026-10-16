#include "scalar_abi.h"
#include <errno.h>
#include <string.h>

_Static_assert(sizeof(uintptr_t)==8,"64-bit host required");
_Static_assert(offsetof(struct tp_scalar_frame,gpr)==0,"GPR offset");
_Static_assert(offsetof(struct tp_scalar_frame,fpr)==64,"FPR offset");
_Static_assert(offsetof(struct tp_scalar_frame,stack)==128,"stack offset");
_Static_assert(offsetof(struct tp_scalar_frame,stack_size)==192,"stack size offset");
_Static_assert(sizeof(struct tp_scalar_frame)==200,"frame size");
_Static_assert(offsetof(struct tp_scalar_return,fpr)==8,"return FPR offset");

static bool scalar(enum tp_scalar_type type){return type>=TP_SCALAR_I32&&type<=TP_SCALAR_F64;}
static bool floating(enum tp_scalar_type type){return type==TP_SCALAR_F32||type==TP_SCALAR_F64;}
static uint8_t width(enum tp_scalar_type type){return type==TP_SCALAR_I32||type==TP_SCALAR_U32||type==TP_SCALAR_F32?4:8;}
static bool canonical(const struct tp_scalar_value *value){return scalar(value->type)&&(width(value->type)==8||(value->bits>>32)==0);}

enum tp_scalar_error tp_scalar_plan(const struct tp_scalar_signature *signature,struct tp_scalar_layout *output){
    if(!signature||!output)return TP_SCALAR_NULL;
    if(signature->flags||(!scalar(signature->result)&&signature->result!=TP_SCALAR_VOID))return TP_SCALAR_SIGNATURE;
    if(signature->count>TP_SCALAR_MAX_ARGS)return TP_SCALAR_BUDGET;
    struct tp_scalar_layout candidate={0};candidate.count=signature->count;uint8_t gpr=0,fpr=0,stack=0;
    for(uint32_t i=0;i<signature->count;i++){
        enum tp_scalar_type type=signature->arguments[i];if(!scalar(type))return TP_SCALAR_SIGNATURE;
        struct tp_scalar_location *location=&candidate.arguments[i];location->width=width(type);
        uint8_t *next=floating(type)?&fpr:&gpr;
        if(*next<8){location->bank=floating(type)?TP_SCALAR_FPR:TP_SCALAR_GPR;location->index=(*next)++;}
        else{if(stack==TP_SCALAR_STACK_SLOTS)return TP_SCALAR_BUDGET;location->bank=TP_SCALAR_STACK;location->index=stack++;}
    }
    candidate.stack_size=((uint32_t)stack*8+15)&~15u;
    candidate.result.bank=floating(signature->result)?TP_SCALAR_FPR:TP_SCALAR_GPR;
    candidate.result.width=signature->result==TP_SCALAR_VOID?0:width(signature->result);
    *output=candidate;return TP_SCALAR_OK;
}
enum tp_scalar_error tp_scalar_pack(const struct tp_scalar_signature *signature,const struct tp_scalar_value *values,struct tp_scalar_frame *output){
    if(!signature||!output||(signature->count&&!values))return TP_SCALAR_NULL;
    struct tp_scalar_layout layout;enum tp_scalar_error error=tp_scalar_plan(signature,&layout);if(error)return error;
    struct tp_scalar_frame candidate={0};candidate.stack_size=layout.stack_size;
    for(uint32_t i=0;i<signature->count;i++){
        if(values[i].type!=signature->arguments[i]||!canonical(&values[i]))return TP_SCALAR_VALUE;
        struct tp_scalar_location location=layout.arguments[i];uint64_t *bank=location.bank==TP_SCALAR_GPR?candidate.gpr:location.bank==TP_SCALAR_FPR?candidate.fpr:candidate.stack;
        bank[location.index]=values[i].bits;
    }
    *output=candidate;return TP_SCALAR_OK;
}
enum tp_scalar_error tp_scalar_capture(const struct tp_scalar_signature *signature,const struct tp_scalar_frame *frame,struct tp_scalar_value *output){
    if(!signature||!frame||(signature->count&&!output))return TP_SCALAR_NULL;
    struct tp_scalar_layout layout;enum tp_scalar_error error=tp_scalar_plan(signature,&layout);if(error)return error;
    if(frame->reserved||frame->stack_size!=layout.stack_size)return TP_SCALAR_VALUE;
    struct tp_scalar_value candidate[TP_SCALAR_MAX_ARGS]={0};
    for(uint32_t i=0;i<signature->count;i++){
        struct tp_scalar_location location=layout.arguments[i];const uint64_t *bank=location.bank==TP_SCALAR_GPR?frame->gpr:location.bank==TP_SCALAR_FPR?frame->fpr:frame->stack;
        candidate[i].type=signature->arguments[i];candidate[i].bits=bank[location.index];
        /* AAPCS64 leaves upper bits/padding unspecified for 32-bit incoming values. */
        if(location.width==4)candidate[i].bits&=UINT32_MAX;
    }
    if(signature->count)memcpy(output,candidate,signature->count*sizeof(*output));return TP_SCALAR_OK;
}
enum tp_scalar_error tp_scalar_result(const struct tp_scalar_signature *signature,const struct tp_scalar_return *raw,struct tp_scalar_value *output){
    if(!signature||!raw||!output)return TP_SCALAR_NULL;
    struct tp_scalar_layout layout;enum tp_scalar_error error=tp_scalar_plan(signature,&layout);if(error)return error;
    struct tp_scalar_value candidate={.type=signature->result,.bits=0};
    if(signature->result!=TP_SCALAR_VOID){candidate.bits=layout.result.bank==TP_SCALAR_GPR?raw->gpr:raw->fpr;if(layout.result.width==4)candidate.bits&=UINT32_MAX;}
    *output=candidate;return TP_SCALAR_OK;
}
enum tp_scalar_error tp_scalar_call(const struct tp_scalar_signature *signature,void (*target)(void),const struct tp_scalar_value *values,int incoming_errno,struct tp_scalar_result *output){
    if(!output||!signature)return TP_SCALAR_NULL;
    if(!target||((uintptr_t)target&3u)||((uintptr_t)target>>48))return TP_SCALAR_TARGET;
    struct tp_scalar_signature snapshot=*signature;
    struct tp_scalar_frame frame;enum tp_scalar_error error=tp_scalar_pack(&snapshot,values,&frame);if(error)return error;
    /* Resolve TLS before the call. No libc/JNI/allocator operation is permitted between return and capture. */
    int *cell=&errno;struct tp_scalar_return raw={0};*cell=incoming_errno;
    tp_scalar_call_frame(target,&frame,&raw);int returned_errno=*cell;
    struct tp_scalar_result candidate={0};error=tp_scalar_result(&snapshot,&raw,&candidate.value);
    candidate.error_number=returned_errno;*cell=returned_errno;
    if(error)return error;*output=candidate;return TP_SCALAR_OK;
}
