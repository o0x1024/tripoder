#include <stdbool.h>
#include "native_prefix.h"
#include <string.h>
static int signed_field(unsigned value,unsigned bits){unsigned sign=1u<<(bits-1);return (int)(value^sign)-(int)sign;}
static enum tp_a64_status effects(uint32_t word,struct tp_native_prefix *plan){
    if(word==0xd503233fu){plan->signed_ra=true;return TP_A64_OK;}
    /* STP 64-bit GPR, negative pre-index on SP. The write-back register
     * cannot alias either stored register. Only ABI callee-saved registers
     * are interpreted as unwind roots. Argument spills still relocate. */
    if((word&0xffc003e0u)==0xa98003e0u){
        int offset=signed_field((word>>15)&127u,7)*8;
        if(offset>=0||(-offset&15))return TP_A64_UNSUPPORTED;
        unsigned first=word&31u,second=(word>>10)&31u;if(first==second&&first!=31)return TP_A64_UNSUPPORTED;
        plan->frame=(unsigned)-offset;
        if(first>=19&&first<=30)plan->saved[0]=(struct tp_native_saved){(uint8_t)first,(uint8_t)(plan->frame/8),true};
        if(second>=19&&second<=30)plan->saved[1]=(struct tp_native_saved){(uint8_t)second,(uint8_t)(plan->frame/8-1),true};
        return TP_A64_OK;
    }
    /* STR Dn, negative pre-index SP. Preserve the low 64-bit ABI root of
     * v8..v15. High SIMD halves are caller-saved in AAPCS64. */
    if((word&0xffe00fe0u)==0xfc000fe0u||(word&0xffe00fe0u)==0xf8000fe0u){
        int offset=signed_field((word>>12)&511u,9);
        if(offset>=0||(-offset&15))return TP_A64_UNSUPPORTED;
        plan->frame=(unsigned)-offset;unsigned reg=word&31u;
        bool simd=(word&0x04000000u)!=0;
        if(simd&&reg>=8&&reg<=15)plan->saved[0]=(struct tp_native_saved){(uint8_t)(64+reg),(uint8_t)(plan->frame/8),true};
        if(!simd&&reg>=19&&reg<=30)plan->saved[0]=(struct tp_native_saved){(uint8_t)reg,(uint8_t)(plan->frame/8),true};
        return TP_A64_OK;
    }
    if((word&0xffc003ffu)==0xd10003ffu){
        unsigned frame=(word>>10)&4095u;if(!frame||(frame&15u))return TP_A64_UNSUPPORTED;
        plan->frame=frame;return TP_A64_OK;
    }
    /* The ordinary relocation contract covers only instructions without
     * implicit stack effects. Reject ADD/SUB writes to SP except the proven
     * stack allocation above; CFI must never silently keep the old CFA. */
    if((word&0x1f800000u)==0x11000000u&&(word&31u)==31u&&!(word&0x20000000u))return TP_A64_UNSUPPORTED;
    return TP_A64_OK;
}
bool tp_native_prefix_supported(const uint32_t *input,size_t available,uintptr_t entry){
    if(!input||!available||available>TP_A64_MAX_WORDS||(entry&3u))return false;
    bool signed_entry=input[0]==0xd503233fu;
    unsigned prefix=input[0]==TP_A64_BTI_C||input[0]==TP_A64_BTI_JC||signed_entry?1u:0u;
    if(input[0]==TP_A64_BTI_J||available<=prefix||entry>UINTPTR_MAX-prefix*4u)return false;
    struct tp_native_prefix plan={0};if(effects(input[prefix],&plan)!=TP_A64_OK||(signed_entry&&plan.signed_ra))return false;
    if(plan.frame||plan.signed_ra)return true;
    uint32_t output;uintptr_t pc=entry+prefix*4u;
    return tp_a64_relocate(input+prefix,1,pc,pc,&output)==TP_A64_OK;
}
enum tp_a64_status tp_native_prefix_plan(const uint32_t *input,size_t available,uintptr_t entry,uintptr_t replacement,uintptr_t trampoline,struct tp_native_prefix *out){
    if(!input||!out||!available||available>TP_A64_MAX_WORDS)return TP_A64_ARGUMENT;
    if((entry|replacement|trampoline)&3u)return TP_A64_ALIGNMENT;
    bool entry_signed=input[0]==0xd503233fu;
    unsigned prefix=input[0]==TP_A64_BTI_C||input[0]==TP_A64_BTI_JC||entry_signed?1u:0u;
    if(input[0]==TP_A64_BTI_J)return TP_A64_UNSUPPORTED;
    if(available<=prefix)return TP_A64_ARGUMENT;
    uintptr_t bytes=(prefix+1)*4u;
    if(entry>UINTPTR_MAX-bytes||trampoline>UINTPTR_MAX-16)return TP_A64_OVERFLOW;
    if((entry<trampoline+16&&trampoline<entry+bytes)||(replacement>=entry&&replacement<entry+bytes)
        ||(replacement>=trampoline&&replacement<trampoline+16))return TP_A64_OVERLAP;
    struct tp_native_prefix candidate={0};struct tp_a64_entry_plan *plan=&candidate.entry;
    plan->patch_address=entry+prefix*4u;plan->preserved_prefix_bytes=prefix*4u;plan->expected_word=input[prefix];
    enum tp_a64_status status=effects(plan->expected_word,&candidate);if(status!=TP_A64_OK)return status;
    if(entry_signed&&candidate.signed_ra)return TP_A64_UNSUPPORTED;
    bool special=candidate.signed_ra||candidate.frame;
    if(!special){
        uint32_t callable_prefix[2]={TP_A64_BTI_C,input[prefix]};
        status=tp_a64_plan_entry(entry_signed?callable_prefix:input,entry_signed?2:available,entry,replacement,trampoline+4,plan);
        if(status!=TP_A64_OK)return status;
    }else{
        status=tp_a64_branch(plan->patch_address,replacement,&plan->branch_word);if(status!=TP_A64_OK)return status;
        plan->trampoline[0]=TP_A64_BTI_C;plan->trampoline[1]=plan->expected_word;
        status=tp_a64_branch(trampoline+12,entry+bytes,&plan->trampoline[2]);if(status!=TP_A64_OK)return status;
    }
    candidate.original[0]=TP_A64_BTI_C;candidate.original[1]=entry_signed?0xd503233fu:TP_A64_NOP;
    candidate.original[2]=plan->trampoline[1];candidate.original[3]=plan->trampoline[2];
    candidate.entry_signed=entry_signed;candidate.signed_ra|=entry_signed;
    *out=candidate;return TP_A64_OK;
}
