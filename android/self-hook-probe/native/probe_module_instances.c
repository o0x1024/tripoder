#define _GNU_SOURCE
#include "probe_module_instances.h"
#include "modules/linker_notifier.h"
#include <android/dlext.h>
#include <dlfcn.h>
#include <stdbool.h>
static void check(struct tp_module_instances *result,bool ok,const char *name){result->checks++;if(!ok){result->failures++;if(!result->first)result->first=name;}}
struct tp_module_instances tp_probe_module_instances(const char *path){
    struct tp_module_instances result={0};struct tp_module_snapshot primary={0},ambiguous={0},next={0};bool lease=false;
    void *one=dlopen(path,RTLD_NOW|RTLD_LOCAL),*two=NULL;check(&result,one!=NULL,"PRIMARY_LOAD");if(!one)goto done;
    check(&result,tp_linker_snapshot(0,&primary)&&primary.live&&!primary.ambiguous,"PRIMARY_IDENTITY");
    uint64_t (*first)(uint64_t)=dlsym(one,"tp_fixture_target");check(&result,first&&first(19)==20,"PRIMARY_CALL");
    lease=tp_linker_acquire(0,primary.generation);check(&result,lease,"PRIMARY_LEASE");if(result.failures)goto done;
    android_dlextinfo ext={.flags=ANDROID_DLEXT_FORCE_LOAD};two=android_dlopen_ext(path,RTLD_NOW|RTLD_LOCAL,&ext);
    check(&result,two&&two!=one,"FORCE_LOAD_DISTINCT_HANDLE");if(!two)goto done;
    uint64_t (*second)(uint64_t)=dlsym(two,"tp_fixture_target");Dl_info info={0};
    check(&result,second&&second!=first&&second(23)==24&&dladdr((void *)second,&info),"SECOND_INSTANCE_CALL");
    check(&result,tp_linker_snapshot(0,&ambiguous)&&ambiguous.live&&ambiguous.ambiguous&&ambiguous.generation==primary.generation
        &&ambiguous.base==primary.base&&ambiguous.references==1,"AMBIGUOUS_PRESERVES_OWNED_PRIMARY");
    check(&result,tp_linker_release(0,primary.generation),"RELEASE_PRIMARY_LEASE");lease=false;
    check(&result,dlclose(one)==0,"CLOSE_PRIMARY_INSTANCE");one=NULL;
    check(&result,tp_linker_snapshot(0,&next)&&next.live&&!next.ambiguous&&next.generation==primary.generation+1
        &&next.base==(uintptr_t)info.dli_fbase,"SECOND_TAKES_NEW_GENERATION");
    check(&result,!tp_linker_acquire(0,primary.generation),"REMOVED_PRIMARY_TOKEN_REJECTED");
    check(&result,dlclose(two)==0,"CLOSE_SECOND_INSTANCE");two=NULL;
    check(&result,tp_linker_snapshot(0,&next)&&!next.live&&!next.ambiguous&&!next.references,"BOTH_INSTANCES_REMOVED");
done:
    if(lease)tp_linker_release(0,primary.generation);
    if(one)dlclose(one);if(two)dlclose(two);return result;
}
