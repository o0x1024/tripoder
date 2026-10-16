/* Exercise the production resolver with a native root still in from-space.
 * The ART barrier is substituted, not the resolver or declaring-class policy. */
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#ifndef TP_FRAMEWORK_SOURCE
#define TP_FRAMEWORK_SOURCE "../native/art_framework.c"
#endif
#include TP_FRAMEWORK_SOURCE

static _Alignas(8) unsigned char klass_bytes[0x38];
static struct {uint32_t count,padding;uint32_t method[8];} methods;
static uint32_t field_words[4];
static unsigned reflected_methods,reflected_fields,barriers;
static bool inherited,bad_id;
static int failures;
#define CHECK(test) do{if(!(test)){fprintf(stderr,"failed: %s:%d: %s\n",__FILE__,__LINE__,#test);failures++;}}while(0)
static void *current_thread(void){return klass_bytes;}
static uintptr_t decode_owner(void *thread,jobject object){CHECK(thread==klass_bytes);CHECK(object==(jobject)(uintptr_t)0x11);return (uintptr_t)klass_bytes;}
static uintptr_t barrier(const uint32_t *slot){
    CHECK(slot==methods.method||slot==field_words);CHECK(*slot==UINT32_C(0x20f5310));barriers++;
    return inherited?(uintptr_t)klass_bytes+8:(uintptr_t)klass_bytes;
}
static void *decode_id(void *manager,jmethodID id){(void)manager;return bad_id?NULL:(void *)id;}
static jobject reflected_method(JNIEnv *env,jclass owner_class,jmethodID id,jboolean is_static){
    (void)env;CHECK(owner_class==(jclass)(uintptr_t)0x11);CHECK(id==(jmethodID)methods.method);CHECK(is_static==JNI_FALSE);reflected_methods++;return (jobject)(uintptr_t)0x22;
}
static void *find_field_stub(uintptr_t owner_class,struct string_view name,struct string_view type){
    CHECK(owner_class==(uintptr_t)klass_bytes);CHECK(name.size>0&&type.size>0);return field_words;
}
static jfieldID encode_field_stub(void *field){CHECK(field==field_words);return (jfieldID)field;}
static void *decode_field_stub(void *manager,jfieldID id){(void)manager;return (void *)id;}
static jobject reflected_field(JNIEnv *env,jclass owner_class,jfieldID id,jboolean is_static){
    (void)env;CHECK(owner_class==(jclass)(uintptr_t)0x11);CHECK(id==(jfieldID)field_words);CHECK(is_static==JNI_FALSE);reflected_fields++;return (jobject)(uintptr_t)0x33;
}
/* Unused preparation dependencies: this test supplies an already bound registry. */
int tp_art_method_snapshot(JNIEnv *env,jobject method,struct tp_art_method_view *result){(void)env;(void)method;(void)result;return 0;}
int tp_art_access_bind(struct tp_art_access *result){(void)result;return 0;}
int tp_art_validate_code(const struct tp_art_access *access,uintptr_t rva,const void *bytes,unsigned size){(void)access;(void)rva;(void)bytes;(void)size;return 0;}
int tp_art_readable(uintptr_t address,unsigned bytes){(void)address;(void)bytes;return 0;}

int main(void){
    struct JNINativeInterface_ functions={.ToReflectedMethod=reflected_method,.ToReflectedField=reflected_field};JNIEnv env=&functions;
    registry.access.current=current_thread;registry.decode_object=decode_owner;registry.read_root=barrier;
    registry.decode_method=decode_id;registry.find_field=find_field_stub;registry.encode_field=encode_field_stub;registry.decode_field=decode_field_stub;
    registry.range_count=1;registry.ranges[0]=(struct range){1,UINT64_C(0x00ffffffffffffff)};
    registry.classes[23]=registry.classes[1]=(jclass)(uintptr_t)0x11;
    *(uintptr_t *)(klass_bytes+0x30)=(uintptr_t)&methods;methods.count=1;
    methods.method[0]=field_words[0]=UINT32_C(0x20f5310);methods.method[1]=field_words[1]=1;methods.method[2]=42;
    atomic_store(&ready,true);
    CHECK(count_methods(&env,NULL,23)==1);
    CHECK(resolve_method(&env,NULL,23,0)==(jobject)(uintptr_t)0x22);
    CHECK(reflected_methods==1&&barriers==1&&atomic_load(&null_reason[1])==0);
    CHECK(resolve_field(&env,NULL,0)==(jobject)(uintptr_t)0x33);
    CHECK(reflected_fields==1&&barriers==2);
    inherited=true;
    CHECK(resolve_method(&env,NULL,23,0)==NULL&&atomic_load(&null_reason[1])==1);
    CHECK(resolve_field(&env,NULL,0)==NULL&&reflected_fields==1);
    inherited=false;methods.method[2]=UINT32_MAX;
    CHECK(resolve_method(&env,NULL,23,0)==NULL&&atomic_load(&null_reason[2])==1);
    methods.method[2]=42;methods.method[1]=UINT32_C(0x10008);
    CHECK(resolve_method(&env,NULL,23,0)==NULL&&atomic_load(&null_reason[3])==1);
    methods.method[1]=1;bad_id=true;
    CHECK(resolve_method(&env,NULL,23,0)==NULL&&atomic_load(&null_reason[4])==1);
    CHECK(resolve_method(&env,NULL,23,1)==NULL&&atomic_load(&null_reason[0])==1);
    CHECK(reflected_methods==1&&reflected_fields==1&&barriers==7);
    if(failures)return EXIT_FAILURE;
    puts("framework native roots: forwarded method/field accepted; inherited, clinit, runtime and invalid IDs rejected");return EXIT_SUCCESS;
}
