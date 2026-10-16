#include "scalar_cases.h"
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

_Static_assert(offsetof(struct tp_scalar_capture_state,entry_sp)==200,"capture SP");
_Static_assert(offsetof(struct tp_scalar_capture_state,return_gpr)==208,"capture return GPR");
_Static_assert(offsetof(struct tp_scalar_capture_state,return_fpr)==216,"capture return FPR");
_Static_assert(offsetof(struct tp_scalar_capture_state,errno_cell)==224,"capture errno cell");
_Static_assert(offsetof(struct tp_scalar_capture_state,entry_errno)==232,"capture entry errno");
_Static_assert(offsetof(struct tp_scalar_capture_state,return_errno)==236,"capture return errno");
struct tp_scalar_capture_state tp_scalar_capture_state __attribute__((visibility("hidden")));
extern void tp_scalar_capture_entry(void) __attribute__((visibility("hidden")));
static atomic_flag fixture_busy=ATOMIC_FLAG_INIT;
static void check(struct tp_scalar_fixture_report *report,bool passed){report->checks++;if(!passed)report->failures++;}
static void reset(const struct tp_scalar_fixture_case *fixture,uint32_t stack_size){
    memset(&tp_scalar_capture_state,0,sizeof(tp_scalar_capture_state));tp_scalar_capture_state.frame.stack_size=stack_size;
    if(fixture->signature.result==TP_SCALAR_F32||fixture->signature.result==TP_SCALAR_F64)tp_scalar_capture_state.return_fpr=fixture->return_bits;
    else if(fixture->signature.result!=TP_SCALAR_VOID)tp_scalar_capture_state.return_gpr=fixture->return_bits;
    tp_scalar_capture_state.errno_cell=&errno;tp_scalar_capture_state.return_errno=EDOM;
}
static void captured(struct tp_scalar_fixture_report *report,const struct tp_scalar_fixture_case *fixture){
    struct tp_scalar_value values[TP_SCALAR_MAX_ARGS]={0};enum tp_scalar_error error=tp_scalar_capture(&fixture->signature,&tp_scalar_capture_state.frame,values);
    check(report,error==TP_SCALAR_OK);if(error)return;
    check(report,(tp_scalar_capture_state.entry_sp&15u)==0);check(report,tp_scalar_capture_state.entry_errno==17);
    for(uint32_t i=0;i<fixture->signature.count;i++)check(report,values[i].type==fixture->values[i].type&&values[i].bits==fixture->values[i].bits);
}
static void one(struct tp_scalar_fixture_report *report,const struct tp_scalar_fixture_case *fixture){
    struct tp_scalar_layout layout;enum tp_scalar_error error=tp_scalar_plan(&fixture->signature,&layout);check(report,error==TP_SCALAR_OK);if(error)return;
    reset(fixture,layout.stack_size);errno=17;struct tp_scalar_return compiler={0};fixture->compiler_call(&compiler);int compiler_errno=errno;
    captured(report,fixture);struct tp_scalar_value compiler_result={0};error=tp_scalar_result(&fixture->signature,&compiler,&compiler_result);
    check(report,error==TP_SCALAR_OK&&compiler_result.type==fixture->signature.result&&compiler_result.bits==fixture->return_bits);check(report,compiler_errno==EDOM);
    reset(fixture,layout.stack_size);struct tp_scalar_result own={0};errno=81;
    error=tp_scalar_call(&fixture->signature,tp_scalar_capture_entry,fixture->values,17,&own);int own_errno=errno;
    check(report,error==TP_SCALAR_OK);if(error)return;captured(report,fixture);
    check(report,own.value.type==compiler_result.type&&own.value.bits==compiler_result.bits);check(report,own.error_number==EDOM&&own_errno==EDOM);report->cases++;
}
/* Ordinary owned C target; no global capture state, used to check simultaneous TLS errno ownership. */
__attribute__((noinline,visibility("hidden"))) int64_t tp_scalar_owned_errno(int32_t value,double multiplier){
    int entry=errno;errno=(value&1)?EDOM:ERANGE;return (int64_t)value*100000+(int64_t)multiplier*100+entry;
}
struct worker {uint32_t index,calls,errors;};
static void *worker(void *opaque){
    struct worker *state=opaque;struct tp_scalar_signature signature={.count=2,.result=TP_SCALAR_I64,.arguments={TP_SCALAR_I32,TP_SCALAR_F64}};
    double multiplier=(double)state->index+0.5;uint64_t bits;memcpy(&bits,&multiplier,sizeof(bits));
    struct tp_scalar_value args[2]={{.type=TP_SCALAR_I32,.bits=state->index},{.type=TP_SCALAR_F64,.bits=bits}};
    for(uint32_t i=0;i<1000;i++){
        int incoming=13+(int)state->index;struct tp_scalar_result result={0};
        enum tp_scalar_error error=tp_scalar_call(&signature,(void(*)(void))tp_scalar_owned_errno,args,incoming,&result);int current=errno;
        int64_t actual;memcpy(&actual,&result.value.bits,sizeof(actual));int expected_errno=(state->index&1)?EDOM:ERANGE;
        if(error!=TP_SCALAR_OK||result.value.type!=TP_SCALAR_I64||actual!=(int64_t)state->index*100000+(int64_t)multiplier*100+incoming||result.error_number!=expected_errno||current!=expected_errno)state->errors++;
        state->calls++;
    }
    return NULL;
}
int tp_scalar_fixture_run(struct tp_scalar_fixture_report *report){
    if(!report)return -1;if(atomic_flag_test_and_set(&fixture_busy))return -2;int saved_errno=errno;struct tp_scalar_fixture_report result={0};
    size_t count;const struct tp_scalar_fixture_case *cases=tp_scalar_fixture_cases(&count);
    for(size_t i=0;i<count;i++)one(&result,&cases[i]);
    pthread_t threads[4];struct worker states[4]={0};unsigned created=0;
    for(unsigned i=0;i<4;i++){states[i].index=i+1;if(pthread_create(&threads[i],NULL,worker,&states[i])!=0){check(&result,false);break;}created++;}
    for(unsigned i=0;i<created;i++){
        // A failed join does not prove the worker released its stack-backed
        // state. Stop this owned fixture process before that state can expire.
        if(pthread_join(threads[i],NULL)!=0)abort();
        check(&result,true);result.concurrent_calls+=states[i].calls;result.concurrent_errors+=states[i].errors;
    }
    check(&result,created==4&&result.concurrent_calls==4000&&result.concurrent_errors==0);check(&result,result.cases==count);
    result.passed=result.failures==0;*report=result;errno=saved_errno;atomic_flag_clear(&fixture_busy);return result.passed?0:-3;
}
