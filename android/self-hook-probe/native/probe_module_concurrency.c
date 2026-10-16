#define _GNU_SOURCE
#include "probe_module_concurrency.h"
#include "modules/linker_notifier.h"
#include "park_syscall.h"
#include <dlfcn.h>
#include <pthread.h>
#include <stdatomic.h>
static const char *library_path;
static _Atomic unsigned cycles,errors;
static _Atomic bool begin;
static void *worker(void *unused){
    (void)unused;uint32_t previous=0;
    while(!atomic_load_explicit(&begin,memory_order_acquire)){}
    for(unsigned i=0;i<100;i++){
        void *handle=dlopen(library_path,RTLD_NOW|RTLD_LOCAL);
        if(!handle){atomic_fetch_add(&errors,1);break;}
        struct tp_module_snapshot live={0};
        bool owned=tp_linker_snapshot(0,&live)&&live.live&&live.generation>=previous&&tp_linker_acquire(0,live.generation);
        if(!owned)atomic_fetch_add(&errors,1);
        else{
            previous=live.generation;uint64_t (*call)(uint64_t)=dlsym(handle,"tp_fixture_target");
            Dl_info info={0};
            if(!call||!dladdr((void *)call,&info)||(uintptr_t)info.dli_fbase!=live.base||call(i)!=i+1u)atomic_fetch_add(&errors,1);
            if(!tp_linker_release(0,live.generation))atomic_fetch_add(&errors,1);
        }
        if(dlclose(handle))atomic_fetch_add(&errors,1);
        atomic_fetch_add(&cycles,1);
    }
    return NULL;
}
struct tp_module_concurrency_report tp_probe_module_concurrency(const char *path){
    library_path=path;pthread_t threads[4];unsigned count=0;
    for(;count<4;count++)if(pthread_create(&threads[count],NULL,worker,NULL))break;
    atomic_store_explicit(&begin,true,memory_order_release);
    for(unsigned i=0;i<count;i++)if(pthread_join(threads[i],NULL)){
        (void)tp_park_syscall(SYS_exit_group,203,0,0,0,0,0);__builtin_trap();
    }
    struct tp_module_snapshot final={0};bool removed=tp_linker_snapshot(0,&final)&&!final.live&&final.generation>2&&!final.references;
    return (struct tp_module_concurrency_report){count,atomic_load(&cycles),atomic_load(&errors),removed};
}
