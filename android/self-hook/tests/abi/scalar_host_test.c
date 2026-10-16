#include "scalar_abi.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>

static unsigned checks,calls;
static int observed_errno;
static struct tp_scalar_frame observed_frame;
/* Host-only stand-in for assembly execution. It is never included in an Android library. */
void tp_scalar_call_frame(void (*target)(void),const struct tp_scalar_frame *frame,struct tp_scalar_return *output){
    (void)target;calls++;observed_errno=errno;observed_frame=*frame;output->gpr=UINT64_C(0xfedcba9876543210);output->fpr=UINT64_C(0x80000000);errno=ERANGE;
}
static void owned_dummy(void){}
static void check(bool value){checks++;assert(value);}
static void unchanged_plan(struct tp_scalar_signature signature,enum tp_scalar_error expected){
    struct tp_scalar_layout output,before;memset(&output,0xa5,sizeof(output));before=output;
    check(tp_scalar_plan(&signature,&output)==expected);check(memcmp(&output,&before,sizeof(output))==0);
}
int main(void){
    struct tp_scalar_signature signature={.result=TP_SCALAR_VOID};struct tp_scalar_layout layout;
    check(tp_scalar_plan(&signature,&layout)==TP_SCALAR_OK&&layout.stack_size==0&&layout.result.width==0);
    check(tp_scalar_plan(NULL,&layout)==TP_SCALAR_NULL);check(tp_scalar_plan(&signature,NULL)==TP_SCALAR_NULL);
    signature.count=17;unchanged_plan(signature,TP_SCALAR_BUDGET);signature.count=0;
    signature.flags=1;unchanged_plan(signature,TP_SCALAR_SIGNATURE);signature.flags=0;
    signature.result=(enum tp_scalar_type)77;unchanged_plan(signature,TP_SCALAR_SIGNATURE);signature.result=TP_SCALAR_VOID;
    signature.count=1;signature.arguments[0]=TP_SCALAR_VOID;unchanged_plan(signature,TP_SCALAR_SIGNATURE);
    signature.arguments[0]=(enum tp_scalar_type)77;unchanged_plan(signature,TP_SCALAR_SIGNATURE);
    for(enum tp_scalar_type type=TP_SCALAR_I32;type<=TP_SCALAR_F64;type++)for(uint32_t count=0;count<=16;count++){
        memset(&signature,0,sizeof(signature));signature.count=count;signature.result=type;struct tp_scalar_value values[16]={0},roundtrip[16]={0};
        for(uint32_t i=0;i<count;i++){signature.arguments[i]=type;values[i].type=type;values[i].bits=(type==TP_SCALAR_I32||type==TP_SCALAR_U32||type==TP_SCALAR_F32)?UINT32_C(0x80000001)+i:UINT64_C(0x8000000000000001)+i;}
        check(tp_scalar_plan(&signature,&layout)==TP_SCALAR_OK);uint32_t slots=count>8?count-8:0;check(layout.stack_size==((slots*8+15)&~15u));
        for(uint32_t i=0;i<count;i++)check(layout.arguments[i].bank==(i<8?(type>=TP_SCALAR_F32?TP_SCALAR_FPR:TP_SCALAR_GPR):TP_SCALAR_STACK)&&layout.arguments[i].index==(i<8?i:i-8));
        struct tp_scalar_frame frame;check(tp_scalar_pack(&signature,values,&frame)==TP_SCALAR_OK);check(tp_scalar_capture(&signature,&frame,roundtrip)==TP_SCALAR_OK);
        for(uint32_t i=0;i<count;i++)check(roundtrip[i].type==values[i].type&&roundtrip[i].bits==values[i].bits);
        if(count&&type==TP_SCALAR_I32){frame.gpr[0]|=UINT64_C(0xdeadbeef)<<32;check(tp_scalar_capture(&signature,&frame,roundtrip)==TP_SCALAR_OK&&roundtrip[0].bits==values[0].bits);}
    }
    memset(&signature,0,sizeof(signature));signature.count=16;signature.result=TP_SCALAR_F64;
    for(unsigned i=0;i<16;i++)signature.arguments[i]=(i&1)?TP_SCALAR_F64:TP_SCALAR_I64;
    check(tp_scalar_plan(&signature,&layout)==TP_SCALAR_OK&&layout.stack_size==0);
    for(unsigned i=0;i<16;i++)check(layout.arguments[i].bank==((i&1)?TP_SCALAR_FPR:TP_SCALAR_GPR)&&layout.arguments[i].index==i/2);
    for(unsigned i=0;i<16;i++)signature.arguments[i]=i<10?TP_SCALAR_U64:TP_SCALAR_F32;
    check(tp_scalar_plan(&signature,&layout)==TP_SCALAR_OK&&layout.stack_size==16);
    check(layout.arguments[8].bank==TP_SCALAR_STACK&&layout.arguments[8].index==0&&layout.arguments[9].index==1);
    check(layout.arguments[10].bank==TP_SCALAR_FPR&&layout.arguments[10].index==0);
    struct tp_scalar_frame output,before;memset(&output,0xa5,sizeof(output));before=output;
    memset(&signature,0,sizeof(signature));signature.count=1;signature.result=TP_SCALAR_U64;signature.arguments[0]=TP_SCALAR_U32;
    struct tp_scalar_value value={.type=TP_SCALAR_U32,.bits=UINT64_C(1)<<32};
    check(tp_scalar_pack(&signature,&value,&output)==TP_SCALAR_VALUE&&memcmp(&output,&before,sizeof(output))==0);
    value.bits=0;value.type=TP_SCALAR_I32;check(tp_scalar_pack(&signature,&value,&output)==TP_SCALAR_VALUE&&memcmp(&output,&before,sizeof(output))==0);
    check(tp_scalar_pack(&signature,NULL,&output)==TP_SCALAR_NULL);value.type=TP_SCALAR_U32;value.bits=UINT32_MAX;
    check(tp_scalar_pack(&signature,&value,&output)==TP_SCALAR_OK);output.stack_size=16;struct tp_scalar_value captured={.type=TP_SCALAR_I64,.bits=17},old=captured;
    check(tp_scalar_capture(&signature,&output,&captured)==TP_SCALAR_VALUE&&captured.type==old.type&&captured.bits==old.bits);
    struct tp_scalar_return raw={.gpr=UINT64_MAX,.fpr=UINT64_C(0xabcdef0180000000)};signature.result=TP_SCALAR_F32;
    check(tp_scalar_result(&signature,&raw,&captured)==TP_SCALAR_OK&&captured.type==TP_SCALAR_F32&&captured.bits==UINT32_C(0x80000000));
    signature.result=TP_SCALAR_I64;check(tp_scalar_result(&signature,&raw,&captured)==TP_SCALAR_OK&&captured.bits==UINT64_MAX);
    struct tp_scalar_result result={0};errno=EDOM;
    check(tp_scalar_call(&signature,owned_dummy,&value,17,&result)==TP_SCALAR_OK);check(calls==1&&observed_errno==17&&observed_frame.gpr[0]==UINT32_MAX);
    check(result.value.type==TP_SCALAR_I64&&result.value.bits==UINT64_C(0xfedcba9876543210)&&result.error_number==ERANGE&&errno==ERANGE);
    unsigned old_calls=calls;errno=EBUSY;struct tp_scalar_result unchanged;memset(&unchanged,0xa5,sizeof(unchanged));struct tp_scalar_result saved=unchanged;
    check(tp_scalar_call(&signature,NULL,&value,17,&unchanged)==TP_SCALAR_TARGET&&errno==EBUSY&&calls==old_calls&&memcmp(&unchanged,&saved,sizeof(saved))==0);
    check(tp_scalar_call(&signature,(void(*)(void))(uintptr_t)3,&value,17,&unchanged)==TP_SCALAR_TARGET&&errno==EBUSY&&calls==old_calls);
    check(tp_scalar_call(&signature,(void(*)(void))(UINT64_C(0xab)<<56),&value,17,&unchanged)==TP_SCALAR_TARGET&&errno==EBUSY&&calls==old_calls);
    signature.flags=1;check(tp_scalar_call(&signature,owned_dummy,&value,17,&unchanged)==TP_SCALAR_SIGNATURE&&errno==EBUSY&&calls==old_calls);
    printf("SCALAR_ABI_HOST_PASS checks=%u assemblyExecuted=false\n",checks);return 0;
}
