#include "../../native/modules/module_lifetime.h"
#include <assert.h>
#include <pthread.h>
#include <stdio.h>
static unsigned checks;
#define CHECK(condition) do{checks++;assert(condition);}while(0)
static struct tp_module_slot slot;
static _Atomic unsigned wins,losses;
static _Atomic bool go;
static void *acquire_race(void *unused){
    (void)unused;while(!atomic_load(&go)){}
    if(tp_module_acquire(&slot,1))atomic_fetch_add(&wins,1);else atomic_fetch_add(&losses,1);
    return NULL;
}
int main(void){
    struct tp_module_snapshot snapshot;
    CHECK(tp_module_snapshot(&slot,&snapshot)&&!snapshot.live&&!snapshot.generation&&!snapshot.base);
    CHECK(!tp_module_acquire(&slot,0));CHECK(!tp_module_acquire(&slot,1));CHECK(!tp_module_release(&slot,1));
    CHECK(tp_module_reconcile(&slot,(struct tp_module_seen){1,0})==TP_MODULE_CHANGE_INVALID);
    CHECK(tp_module_reconcile(&slot,(struct tp_module_seen){0x1000,0x2000})==TP_MODULE_CHANGE_OK);
    CHECK(tp_module_snapshot(&slot,&snapshot)&&snapshot.live&&snapshot.generation==1&&snapshot.base==0x2000);
    CHECK(tp_module_reconcile(&slot,(struct tp_module_seen){0x1000,0x2000})==TP_MODULE_CHANGE_OK);
    CHECK(tp_module_acquire(&slot,1));CHECK(tp_module_acquire(&slot,1));
    CHECK(tp_module_snapshot(&slot,&snapshot)&&snapshot.references==2);
    CHECK(tp_module_reconcile(&slot,(struct tp_module_seen){0,0})==TP_MODULE_CHANGE_IN_USE);
    CHECK(!tp_module_release(&slot,2));CHECK(tp_module_release(&slot,1));CHECK(tp_module_release(&slot,1));CHECK(!tp_module_release(&slot,1));
    CHECK(tp_module_reconcile(&slot,(struct tp_module_seen){0x3000,0x4000})==TP_MODULE_CHANGE_INVALID);
    CHECK(tp_module_reconcile(&slot,(struct tp_module_seen){0,0})==TP_MODULE_CHANGE_OK);
    CHECK(!tp_module_acquire(&slot,1));CHECK(tp_module_snapshot(&slot,&snapshot)&&!snapshot.live&&snapshot.generation==1&&snapshot.base==0);
    CHECK(tp_module_reconcile(&slot,(struct tp_module_seen){0x1000,0x2000})==TP_MODULE_CHANGE_OK);
    CHECK(tp_module_snapshot(&slot,&snapshot)&&snapshot.live&&snapshot.generation==2);
    CHECK(!tp_module_acquire(&slot,1));CHECK(tp_module_acquire(&slot,2));CHECK(tp_module_release(&slot,2));
    CHECK(tp_module_reconcile(&slot,(struct tp_module_seen){0,0})==TP_MODULE_CHANGE_OK);
    atomic_store(&slot.state,UINT64_C(0xffffffff00000000));
    CHECK(tp_module_reconcile(&slot,(struct tp_module_seen){1,2})==TP_MODULE_CHANGE_EXHAUSTED);
    /* Concurrent acquisition and removal must have exactly one outcome:
     * either the lease wins and removal rejects, or removal wins and no lease
     * can be granted. This tests the common state word, not timing sleeps. */
    for(unsigned round=0;round<2000;round++){
        atomic_store(&slot.state,0);atomic_store(&go,false);atomic_store(&wins,0);atomic_store(&losses,0);
        CHECK(tp_module_reconcile(&slot,(struct tp_module_seen){0x1000,0x2000})==TP_MODULE_CHANGE_OK);
        pthread_t worker;CHECK(pthread_create(&worker,NULL,acquire_race,NULL)==0);atomic_store(&go,true);
        enum tp_module_change result=tp_module_reconcile(&slot,(struct tp_module_seen){0,0});CHECK(pthread_join(worker,NULL)==0);
        CHECK((result==TP_MODULE_CHANGE_OK&&atomic_load(&losses)==1&&atomic_load(&wins)==0)
            ||(result==TP_MODULE_CHANGE_IN_USE&&atomic_load(&wins)==1&&atomic_load(&losses)==0));
        if(result==TP_MODULE_CHANGE_IN_USE){CHECK(tp_module_release(&slot,1));CHECK(tp_module_reconcile(&slot,(struct tp_module_seen){0,0})==TP_MODULE_CHANGE_OK);}
    }
    printf("{\"passed\":true,\"checks\":%u,\"races\":2000}\n",checks);
}
