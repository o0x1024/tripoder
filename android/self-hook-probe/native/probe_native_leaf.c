#define _GNU_SOURCE
#include "tripoder_native_hook.h"
#include "modules/linker_notifier.h"
#include "code_page.h"
#include <dlfcn.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
static unsigned checks,failures;
static const char *first;
static void check(bool ok,const char *name){checks++;if(!ok){failures++;if(!first)first=name;}}
struct context {unsigned calls;};static struct context contexts[6];
static bool dispatch(void *opaque,uint64_t token,const struct tp_scalar_value *args,unsigned count,int incoming,struct tp_scalar_result *out){
    ((struct context *)opaque)->calls++;if(!tp_native_original(token,args,count,incoming,out))return false;
    if(out->value.type==TP_SCALAR_VOID)return true;
    if(out->value.type==TP_SCALAR_F32){uint32_t bits=(uint32_t)out->value.bits;float value;memcpy(&value,&bits,4);value+=1.25f;memcpy(&bits,&value,4);out->value.bits=bits;}
    else if(out->value.type==TP_SCALAR_F64){double value;memcpy(&value,&out->value.bits,8);value+=1.25;memcpy(&out->value.bits,&value,8);}
    else out->value.bits+=out->value.type==TP_SCALAR_U64?5:100;
    return true;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_NativeLeafProbe_run(JNIEnv *env,jclass type,jstring library){
    (void)type;const char *path=(*env)->GetStringUTFChars(env,library,NULL);if(!path)return NULL;
    const char *paths[]={path};struct stat file={0};unsigned handles[6]={0};struct tp_native_report reports[6]={0};bool active=false;
    struct tp_module_snapshot module={0};void *loaded=NULL;
    check(stat(path,&file)==0&&file.st_size>0&&file.st_size<4096,"REAL_PARTIAL_FILE_PAGE");
    check(tp_linker_install(paths,1),"LEAF_MODULE_OBSERVER");if(failures)goto done;
    loaded=dlopen(path,RTLD_NOW|RTLD_LOCAL);check(loaded!=NULL,"LOAD_NATURAL_LEAF_LIBRARY");if(!loaded)goto done;
    int32_t (*constant)(void)=dlsym(loaded,"tp_leaf_constant"),(*add)(int32_t,int32_t)=dlsym(loaded,"tp_leaf_add");
    uint64_t (*identity)(uint64_t)=dlsym(loaded,"tp_leaf_identity");float (*floating)(float)=dlsym(loaded,"tp_leaf_float");
    double (*zero)(void)=dlsym(loaded,"tp_leaf_zero");void (*nothing)(void)=dlsym(loaded,"tp_leaf_void");
    check(constant&&add&&identity&&floating&&zero&&nothing,"SIX_TYPED_LEAF_EXPORTS");if(failures)goto done;
    check(tp_linker_snapshot(0,&module)&&module.live,"LEAF_LOADER_GENERATION");if(failures)goto done;
    check(constant()==42&&add(3,4)==7&&identity(UINT64_C(0xfedcba9876543210))==UINT64_C(0xfedcba9876543210)&&floating(1.25f)==1.75f&&zero()==0.0,"LEAF_ORIGINAL_VALUES");
    const struct tp_scalar_signature signatures[6]={
        {.result=TP_SCALAR_I32},{.count=2,.result=TP_SCALAR_I32,.arguments={TP_SCALAR_I32,TP_SCALAR_I32}},
        {.count=1,.result=TP_SCALAR_U64,.arguments={TP_SCALAR_U64}},
        {.count=1,.result=TP_SCALAR_F32,.arguments={TP_SCALAR_F32}},{.result=TP_SCALAR_F64},{.result=TP_SCALAR_VOID}};
    uintptr_t entries[6]={(uintptr_t)constant,(uintptr_t)add,(uintptr_t)identity,(uintptr_t)floating,(uintptr_t)zero,(uintptr_t)nothing};
    for(unsigned i=0;i<6;i++){
        check(tp_native_prepare(0,module.generation,entries[i]-module.base,path,&signatures[i],dispatch,&contexts[i],&handles[i]),"PREPARE_NATURAL_LEAF_PREFIX");if(failures)goto done;
        check(tp_native_report(handles[i],&reports[i])&&!reports[i].frame&&!reports[i].signed_ra&&reports[i].fde_pcs==8,"LEAF_CFA_AND_REAL_FDE");if(failures)goto done;
    }
    check(tp_native_publish(handles,6),"PUBLISH_LEAF_SHARED_PAGE");if(failures)goto done;active=true;
    check(constant()==142,"MOV_IMMEDIATE_LEAF");check(add(3,4)==107,"REGISTER_ALU_LEAF");
    check(identity(UINT64_C(0xfedcba9876543210))==UINT64_C(0xfedcba9876543215),"RET_FIRST_IDENTITY_LEAF");
    check(floating(1.25f)==3.0f,"SCALAR_FP_IMMEDIATE_LEAF");check(zero()==1.25,"SIMD_IMMEDIATE_LEAF");nothing();
    bool one_each=true;for(unsigned i=0;i<6;i++){struct tp_native_report report;if(contexts[i].calls!=1||!tp_native_report(handles[i],&report)||report.original_calls!=1)one_each=false;}
    check(one_each,"VOID_RET_AND_ALL_ORIGINALS_ONCE");
    check(tp_native_retire(handles,6),"RESTORE_PARTIAL_FILE_PAGE");if(failures)goto done;active=false;
    struct tp_page_identity page={0};check(tp_code_page_identity((void *)(entries[0]&~(uintptr_t)4095),"r-xp",&page)&&page.inode,"PARTIAL_SOURCE_FILE_MAPPING_RESTORED");
    check(constant()==42&&add(3,4)==7&&floating(1.25f)==1.75f&&zero()==0.0,"LEAVES_AFTER_RETIRE");
    check(dlclose(loaded)==0,"LEAF_MODULE_UNLOAD");loaded=NULL;check(tp_linker_snapshot(0,&module)&&!module.live,"LEAF_MODULE_REMOVED");
done:;
    // Failure retains its precise state; no speculative restoration/unload.
    char json[1400];snprintf(json,sizeof(json),"{\"passed\":%s,\"checks\":%u,\"failures\":%u,\"firstFailure\":\"%s\",\"exports\":6,\"fileBytes\":%lld,\"active\":%s,\"hookInstalled\":true,\"productAccepted\":false}",
        failures?"false":"true",checks,failures,first?first:"",(long long)file.st_size,active?"true":"false");
    (*env)->ReleaseStringUTFChars(env,library,path);return (*env)->NewStringUTF(env,json);
}
