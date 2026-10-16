#include "scalar_inbound.h"
#include "scalar_cases.h"
#include "park_syscall.h"
#include <errno.h>
#include <jni.h>
#include <libunwind.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
static unsigned checks,failures,cases_run;
static const char *first;
extern const unsigned char tp_scalar_inbound_common[];
static unsigned unwind_cases;
static bool actual_unwind(void){
    unw_context_t context;unw_cursor_t cursor;if(unw_getcontext(&context)||unw_init_local(&cursor,&context))return false;
    for(unsigned depth=0;depth<48;depth++){
        unw_word_t pc=0;if(unw_get_reg(&cursor,UNW_REG_IP,&pc))return false;
        uintptr_t begin=(uintptr_t)tp_scalar_inbound_common;
        if(pc>=begin&&pc<begin+88){unwind_cases++;return true;}
        if(unw_step(&cursor)<=0)return false;
    }
    return false;
}
static void check(bool ok,const char *name){checks++;if(!ok){failures++;if(!first)first=name;}}
struct fixture_binding {const struct tp_scalar_fixture_case *fixture;unsigned calls;bool arguments_ok;};
static struct fixture_binding fixture_bindings[13];
static bool capture(void *opaque,const struct tp_scalar_value *arguments,unsigned count,int incoming_errno,struct tp_scalar_result *result){
    struct fixture_binding *binding=opaque;const struct tp_scalar_fixture_case *fixture=binding->fixture;
    binding->calls++;binding->arguments_ok=count==fixture->signature.count&&incoming_errno==17;
    for(unsigned i=0;i<count&&i<TP_SCALAR_MAX_ARGS;i++)
        binding->arguments_ok&=arguments[i].type==fixture->values[i].type&&arguments[i].bits==fixture->values[i].bits;
    binding->arguments_ok&=actual_unwind();
    *result=(struct tp_scalar_result){.value={fixture->signature.result,fixture->return_bits},.error_number=EDOM};
    errno=EIO;return true; /* Dispatch must use explicit result errno. */
}
__attribute__((noinline)) static int64_t original(int32_t value,double multiplier){
    int incoming=errno;errno=(value&1)?EDOM:ERANGE;
    return (int64_t)value*100000+(int64_t)multiplier*100+incoming;
}
static const struct tp_scalar_signature original_signature={.count=2,.result=TP_SCALAR_I64,.arguments={TP_SCALAR_I32,TP_SCALAR_F64}};
static _Atomic unsigned original_calls;
static bool invoke_original(void *opaque,const struct tp_scalar_value *arguments,unsigned count,int incoming_errno,struct tp_scalar_result *result){
    (void)opaque;if(count!=2)return false;
    atomic_fetch_add(&original_calls,1);
    return tp_scalar_call(&original_signature,(void (*)(void))original,arguments,incoming_errno,result)==TP_SCALAR_OK;
}
static struct tp_scalar_inbound original_entry,nested_entry;
static _Atomic unsigned nested_calls;
static bool nested(void *opaque,const struct tp_scalar_value *arguments,unsigned count,int incoming_errno,struct tp_scalar_result *result){
    (void)opaque;if(count!=2)return false;atomic_fetch_add(&nested_calls,1);
    /* Reenter a second immutable slot, then make its original C call. */
    if(tp_scalar_call(&original_signature,original_entry.entry,arguments,incoming_errno,result)!=TP_SCALAR_OK)return false;
    result->value.bits+=7;errno=EACCES;return true;
}
struct worker {unsigned id,calls,errors;};
static struct worker workers[4];
static void *work(void *opaque){
    struct worker *worker=opaque;
    int64_t (*entry)(int32_t,double)=(void *)nested_entry.entry;
    for(unsigned i=0;i<1000;i++){
        int incoming=13+(int)worker->id;errno=incoming;
        int64_t value=entry((int32_t)worker->id,(double)worker->id+0.5);int after=errno;
        if(value!=(int64_t)worker->id*100000+(int64_t)worker->id*100+incoming+7
            ||after!=((worker->id&1)?EDOM:ERANGE))worker->errors++;
        worker->calls++;
    }
    return NULL;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_NativeInboundProbe_run(JNIEnv *env,jclass type){
    (void)type;if(checks)return (*env)->NewStringUTF(env,"{\"passed\":false,\"error\":\"REPEATED_RUN\"}");
    size_t count=0;const struct tp_scalar_fixture_case *fixtures=tp_scalar_fixture_cases(&count);
    check(count==13,"EXACT_COMPILER_CASES");
    for(unsigned i=0;i<count&&i<13;i++){
        fixture_bindings[i].fixture=&fixtures[i];struct tp_scalar_inbound binding={0};
        enum tp_scalar_error error=tp_scalar_inbound_prepare(&fixtures[i].signature,capture,&fixture_bindings[i],&binding);
        check(error==TP_SCALAR_OK,"PREPARE_TYPED_ENTRY");if(error)continue;
        struct tp_scalar_return raw={0};errno=17;fixtures[i].compiler_entry(binding.entry,&raw);int returned_errno=errno;
        struct tp_scalar_value value={0};error=tp_scalar_result(&fixtures[i].signature,&raw,&value);
        check(fixture_bindings[i].arguments_ok,"ACTUAL_COMPILER_ARGUMENTS");
        check(error==TP_SCALAR_OK&&value.type==fixtures[i].signature.result&&value.bits==fixtures[i].return_bits,"TYPED_RETURN_BITS");
        check(returned_errno==EDOM,"EXPLICIT_ERRNO");check(fixture_bindings[i].calls==1,"ONE_CALLBACK");cases_run++;
    }
    struct tp_scalar_signature invalid=original_signature;struct tp_scalar_inbound rejected={0};unsigned capacity=tp_scalar_inbound_remaining();
    invalid.flags=1;check(tp_scalar_inbound_prepare(&invalid,invoke_original,NULL,&rejected)==TP_SCALAR_SIGNATURE,"SIGNATURE_FLAGS_REJECTED");
    invalid=original_signature;invalid.count=17;check(tp_scalar_inbound_prepare(&invalid,invoke_original,NULL,&rejected)==TP_SCALAR_BUDGET,"ARGUMENT_BUDGET_REJECTED");
    invalid=original_signature;invalid.arguments[0]=TP_SCALAR_VOID;check(tp_scalar_inbound_prepare(&invalid,invoke_original,NULL,&rejected)==TP_SCALAR_SIGNATURE,"VOID_ARGUMENT_REJECTED");
    check(tp_scalar_inbound_prepare(&original_signature,NULL,NULL,&rejected)==TP_SCALAR_NULL,"NULL_CALLBACK_REJECTED");
    check(tp_scalar_inbound_remaining()==capacity,"REJECTION_CONSUMES_NO_SLOT");
    check(tp_scalar_inbound_prepare(&original_signature,invoke_original,NULL,&original_entry)==TP_SCALAR_OK,"ORIGINAL_ENTRY_PREPARED");
    check(tp_scalar_inbound_prepare(&original_signature,nested,NULL,&nested_entry)==TP_SCALAR_OK,"NESTED_ENTRY_PREPARED");
    pthread_t threads[4];unsigned created=0,calls=0,errors=0;
    if(!failures)for(;created<4;created++){workers[created].id=created+1;if(pthread_create(&threads[created],NULL,work,&workers[created]))break;}
    for(unsigned i=0;i<created;i++){
        if(pthread_join(threads[i],NULL)){(void)tp_park_syscall(SYS_exit_group,205,0,0,0,0,0);__builtin_trap();}
        calls+=workers[i].calls;errors+=workers[i].errors;
    }
    check(created==4&&calls==4000&&errors==0,"CONCURRENT_NESTED_ORIGINAL_TLS");
    check(atomic_load(&original_calls)==4000&&atomic_load(&nested_calls)==4000,"EXACT_NESTED_DISPATCH_COUNTS");
    /* Final slot exercises the same real entry assembly at the boundary.
     * No root/context/code is reclaimed when entries become unused. */
    struct tp_scalar_inbound last={0};unsigned remaining=tp_scalar_inbound_remaining();
    for(unsigned i=0;i<remaining;i++)if(tp_scalar_inbound_prepare(&original_signature,invoke_original,NULL,&last)!=TP_SCALAR_OK){check(false,"FILL_NATIVE_BUDGET");break;}
    check(last.slot==TP_NATIVE_ENTRY_LIMIT-1&&tp_scalar_inbound_remaining()==0,"FINAL_NATIVE_SLOT");
    int64_t (*final_call)(int32_t,double)=(void *)last.entry;errno=17;
    int64_t final_result=last.entry?final_call(2,3.5):0;int final_errno=errno;
    check(final_result==200317&&final_errno==ERANGE,"FINAL_SLOT_ACTUAL_CALL");
    check(tp_scalar_inbound_prepare(&original_signature,invoke_original,NULL,&rejected)==TP_SCALAR_BUDGET,"OVER_BUDGET_REJECTED");
    char json[1200];snprintf(json,sizeof(json),"{\"passed\":%s,\"checks\":%u,\"failures\":%u,\"firstFailure\":\"%s\",\"typedCases\":%u,\"concurrentCalls\":%u,\"concurrentErrors\":%u,\"nestedCalls\":%u,\"originalCalls\":%u,\"entryCapacity\":%u,\"finalSlot\":%u,\"unwindCases\":%u,\"hookInstalled\":false,\"productAccepted\":false}",
        failures?"false":"true",checks,failures,first?first:"",cases_run,calls,errors,atomic_load(&nested_calls),atomic_load(&original_calls),TP_NATIVE_ENTRY_LIMIT,last.slot,unwind_cases);
    return (*env)->NewStringUTF(env,json);
}
