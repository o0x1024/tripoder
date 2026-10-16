#include <assert.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <pthread.h>
#include <jni.h>
#include "../../self-hook/native/physical_application.c"

/* Host-only JNI model: locals expire, globals are distinct handles, each thread owns its JNIEnv. */
enum kind { CLASS_APP,CLASS_THREAD,CLASS_APK,APP,ACTIVITY_THREAD,LOADED_PACKAGE,FIELD,ARRAY,ERROR,STRING };
struct object { enum kind kind;int slot; };
struct handle {struct object *object;bool global,alive;pthread_t owner;int depth;};
static struct object class_app={CLASS_APP,0},class_thread={CLASS_THREAD,0},class_apk={CLASS_APK,0};
static struct object app={APP,0},other_app={APP,1},moved_app={APP,2};
static struct object activity_thread={ACTIVITY_THREAD,0},other_thread={ACTIVITY_THREAD,1};
static struct object loaded_apk={LOADED_PACKAGE,0},other_apk={LOADED_PACKAGE,1};
static struct object field_objects[4]={{FIELD,0},{FIELD,1},{FIELD,2},{FIELD,3}};
static struct object fields_array={ARRAY,0},error_class={ERROR,0},report_string={STRING,0};
static struct object *current_thread,*initial_application,*host_apk,*apk_application,*current_host;
static struct handle *globals[32];static unsigned global_used,global_live,new_globals,deleted_globals;
static _Thread_local struct handle *locals[8192];static _Thread_local unsigned local_used;
static _Thread_local int depth,pending_exception,reads,from_fields,frames;
static _Thread_local int fail_read,fail_from,fail_global,fail_frame;
static _Thread_local bool mutate_after_roots;
static _Thread_local JNIEnv *expected_env;
static _Thread_local char error_text[256],report_text[256];
static unsigned checks,traps;
static void check(bool value){checks++;if(!value){fprintf(stderr,"check failed: %u\n",checks);abort();}}
static void env_ok(JNIEnv *env){assert(env==expected_env);}
static struct object *object(jobject value){
    if(!value)return NULL;struct handle *handle=(struct handle *)value;
    assert(handle->alive);assert(handle->global||pthread_equal(handle->owner,pthread_self()));return handle->object;
}
static jobject local(struct object *value){
    if(!value)return NULL;assert(local_used<8192);struct handle *handle=calloc(1,sizeof(*handle));assert(handle);
    *handle=(struct handle){.object=value,.alive=true,.owner=pthread_self(),.depth=depth};locals[local_used++]=handle;return (jobject)handle;
}
static void expire_locals(void){for(unsigned i=0;i<local_used;i++)locals[i]->alive=false;}
static void free_locals(void){for(unsigned i=0;i<local_used;i++)free(locals[i]);local_used=0;}
static jboolean JNICALL exception_check(JNIEnv *env){env_ok(env);return pending_exception?JNI_TRUE:JNI_FALSE;}
static jclass JNICALL find_class(JNIEnv *env,const char *name){env_ok(env);assert(!pending_exception);assert(!strcmp(name,"java/lang/IllegalStateException"));return (jclass)local(&error_class);}
static jint JNICALL throw_new(JNIEnv *env,jclass type,const char *message){env_ok(env);assert(object(type)==&error_class&&!pending_exception);pending_exception=1;snprintf(error_text,sizeof(error_text),"%s",message);return JNI_OK;}
static void JNICALL delete_local(JNIEnv *env,jobject value){env_ok(env);if(value){object(value);((struct handle *)value)->alive=false;}}
static jobject JNICALL new_global(JNIEnv *env,jobject value){
    env_ok(env);assert(!pending_exception);struct object *target=object(value);new_globals++;
    if((int)new_globals==fail_global){pending_exception=42;return NULL;}
    assert(global_used<32);struct handle *handle=calloc(1,sizeof(*handle));assert(handle);
    *handle=(struct handle){.object=target,.global=true,.alive=true};globals[global_used++]=handle;global_live++;
    if(mutate_after_roots&&new_globals==4)current_thread=&other_thread;
    return (jobject)handle;
}
static void JNICALL delete_global(JNIEnv *env,jobject value){env_ok(env);if(value){object(value);assert(((struct handle *)value)->global);((struct handle *)value)->alive=false;global_live--;deleted_globals++;}}
static jboolean JNICALL is_same(JNIEnv *env,jobject left,jobject right){env_ok(env);assert(!pending_exception);return object(left)==object(right)?JNI_TRUE:JNI_FALSE;}
static jboolean JNICALL instance_of(JNIEnv *env,jobject value,jclass type){
    env_ok(env);assert(!pending_exception);struct object *target=object(value),*owner=object(type);if(!target)return JNI_TRUE;
    return (owner==&class_app&&target->kind==APP)||(owner==&class_thread&&target->kind==ACTIVITY_THREAD)||(owner==&class_apk&&target->kind==LOADED_PACKAGE)?JNI_TRUE:JNI_FALSE;
}
static jint JNICALL push_frame(JNIEnv *env,jint capacity){env_ok(env);assert(!pending_exception);assert(capacity>=8);frames++;if(frames==fail_frame){pending_exception=42;return JNI_ERR;}depth++;return JNI_OK;}
static jobject JNICALL pop_frame(JNIEnv *env,jobject result){env_ok(env);assert(depth>0&&result==NULL);for(unsigned i=0;i<local_used;i++)if(locals[i]->depth>=depth)locals[i]->alive=false;depth--;return NULL;}
static jsize JNICALL array_length(JNIEnv *env,jarray array){env_ok(env);assert(!pending_exception&&object(array)==&fields_array);return 4;}
static jobject JNICALL array_element(JNIEnv *env,jobjectArray array,jsize index){env_ok(env);assert(!pending_exception&&object(array)==&fields_array&&index>=0&&index<4);return local(&field_objects[index]);}
static jfieldID JNICALL reflected_field(JNIEnv *env,jobject field){env_ok(env);assert(!pending_exception);struct object *value=object(field);assert(value->kind==FIELD);from_fields++;if(from_fields==fail_from){pending_exception=42;return NULL;}return (jfieldID)(uintptr_t)(value->slot+1);}
static bool read_failed(void){reads++;if(reads==fail_read){pending_exception=42;return true;}return false;}
static jobject JNICALL static_object_field(JNIEnv *env,jclass owner,jfieldID field){env_ok(env);assert(!pending_exception&&object(owner)==&class_thread&&(uintptr_t)field==4);return read_failed()?NULL:local(current_thread);}
static jobject JNICALL object_field(JNIEnv *env,jobject receiver,jfieldID field){
    env_ok(env);assert(!pending_exception);struct object *value=object(receiver);assert(value);
    if(read_failed())return NULL;
    switch((uintptr_t)field){
        case 1:assert(value->kind==ACTIVITY_THREAD);return local(initial_application);
        case 2:assert(value->kind==APP);return local(host_apk);
        case 3:assert(value->kind==LOADED_PACKAGE);return local(apk_application);
        default:abort();
    }
}
static jstring JNICALL new_string(JNIEnv *env,const char *value){env_ok(env);assert(!pending_exception);snprintf(report_text,sizeof(report_text),"%s",value);return (jstring)local(&report_string);}
static jobject JNICALL getter_trap(JNIEnv *env,jobject receiver,jmethodID method,...){(void)env;(void)receiver;(void)method;traps++;abort();}
static jobject JNICALL static_getter_trap(JNIEnv *env,jclass owner,jmethodID method,...){return getter_trap(env,owner,method);}
static jmethodID JNICALL method_trap(JNIEnv *env,jclass owner,const char *name,const char *type){(void)env;(void)owner;(void)name;(void)type;traps++;abort();}
static jfieldID JNICALL lookup_trap(JNIEnv *env,jclass owner,const char *name,const char *type){(void)env;(void)owner;(void)name;(void)type;traps++;abort();}
static void JNICALL write_trap(JNIEnv *env,jobject receiver,jfieldID field,jobject value){(void)env;(void)receiver;(void)field;(void)value;traps++;abort();}
static void JNICALL clear_trap(JNIEnv *env){(void)env;traps++;abort();}
static const struct JNINativeInterface_ functions={
    .ExceptionCheck=exception_check,.FindClass=find_class,.ThrowNew=throw_new,.DeleteLocalRef=delete_local,
    .NewGlobalRef=new_global,.DeleteGlobalRef=delete_global,.IsSameObject=is_same,.IsInstanceOf=instance_of,
    .PushLocalFrame=push_frame,.PopLocalFrame=pop_frame,.GetArrayLength=array_length,.GetObjectArrayElement=array_element,
    .FromReflectedField=reflected_field,.GetStaticObjectField=static_object_field,.GetObjectField=object_field,.NewStringUTF=new_string,
    .CallObjectMethod=getter_trap,.CallStaticObjectMethod=static_getter_trap,.GetMethodID=method_trap,.GetStaticMethodID=method_trap,
    .GetFieldID=lookup_trap,.GetStaticFieldID=lookup_trap,.SetObjectField=write_trap,.ExceptionClear=clear_trap
};
static void reset(JNIEnv *env){
    pending_exception=0;release_preparation(env);
    for(unsigned i=0;i<global_used;i++)free(globals[i]);global_used=global_live=new_globals=deleted_globals=0;
    free_locals();depth=reads=from_fields=frames=fail_read=fail_from=fail_global=fail_frame=0;mutate_after_roots=false;
    memset(&physical,0,sizeof(physical));physical.reason="NOT_PREPARED";error_text[0]=report_text[0]=0;
    current_host=&app;current_thread=&activity_thread;initial_application=&app;host_apk=&loaded_apk;apk_application=&app;
}
static void prepare(JNIEnv *env){
    Java_dev_tripoder_selfhook_PhysicalApplication_prepareNative(env,NULL,local(current_host),(jclass)local(&class_app),
        (jclass)local(&class_thread),(jclass)local(&class_apk),(jobjectArray)local(&fields_array));
}
static void native_check(JNIEnv *env){Java_dev_tripoder_selfhook_PhysicalApplication_checkNative(env,NULL,local(current_host));}
static void healthy(JNIEnv *env){prepare(env);check(!pending_exception&&physical.phase==READY&&physical.checks==1);check(global_live==4&&physical.retained==4&&depth==0);expire_locals();}
static void move_host(void){
    for(unsigned i=0;i<global_used;i++)if(globals[i]->object==&app)globals[i]->object=&moved_app;
    current_host=initial_application=apk_application=&moved_app;
}
static void *worker(void *unused){
    (void)unused;JNIEnv table=&functions;expected_env=&table;
    for(unsigned i=0;i<100;i++){native_check(&table);assert(!pending_exception&&depth==0);expire_locals();}
    free_locals();return NULL;
}
int main(void){
    JNIEnv table=&functions;JNIEnv *env=&table;expected_env=env;reset(env);
    healthy(env);native_check(env);check(!pending_exception&&physical.checks==2&&global_live==4);
    move_host();native_check(env);check(!pending_exception&&physical.checks==3);check(new_globals==4&&deleted_globals==0);
    pthread_t threads[4];for(unsigned i=0;i<4;i++)assert(!pthread_create(&threads[i],NULL,worker,NULL));
    for(unsigned i=0;i<4;i++)assert(!pthread_join(threads[i],NULL));check(physical.checks==403&&global_live==4);
    Java_dev_tripoder_selfhook_PhysicalApplication_reportNative(env,NULL);check(strstr(report_text,"\"ready\":true")&&strstr(report_text,"\"retainedGlobalRefs\":4"));
    unsigned long long prior=physical.checks;pending_exception=42;native_check(env);check(pending_exception==42&&physical.phase==READY&&physical.checks==prior);
    check(Java_dev_tripoder_selfhook_PhysicalApplication_reportNative(env,NULL)==NULL&&pending_exception==42);pending_exception=0;
    prepare(env);check(pending_exception==1&&physical.phase==READY&&global_live==4&&strstr(error_text,"ALREADY_ATTEMPTED"));
    reset(env);pending_exception=42;prepare(env);check(pending_exception==42&&physical.phase==UNPREPARED&&global_live==0);pending_exception=0;
    native_check(env);check(pending_exception==1&&physical.phase==UNPREPARED&&strstr(error_text,"NOT_READY"));
    for(unsigned bad=0;bad<4;bad++){
        reset(env);
        if(bad==0)current_thread=NULL;else if(bad==1)initial_application=&other_app;
        else if(bad==2)host_apk=NULL;else apk_application=&other_app;
        prepare(env);check(pending_exception==1&&physical.phase==FAILED&&global_live==0&&depth==0);
        pending_exception=0;prepare(env);check(pending_exception==1&&global_live==0&&strstr(error_text,"ALREADY_ATTEMPTED"));
    }
    for(unsigned bad=0;bad<5;bad++){
        reset(env);healthy(env);
        if(bad==0)current_thread=&other_thread;else if(bad==1)initial_application=&other_app;
        else if(bad==2)host_apk=&other_apk;else if(bad==3)apk_application=&other_app;
        if(bad==4)Java_dev_tripoder_selfhook_PhysicalApplication_checkNative(env,NULL,local(&other_app));else native_check(env);
        check(pending_exception==1&&physical.phase==FAILED&&global_live==4&&depth==0);
        pending_exception=0;current_thread=&activity_thread;initial_application=apk_application=&app;host_apk=&loaded_apk;
        native_check(env);check(pending_exception==1&&physical.phase==FAILED&&global_live==4&&strstr(error_text,"NOT_READY"));
    }
    for(int fault=1;fault<=8;fault++){
        reset(env);fail_read=fault;prepare(env);check(pending_exception==42&&physical.phase==FAILED&&global_live==0&&depth==0);
    }
    for(int fault=1;fault<=4;fault++){
        reset(env);fail_from=fault;prepare(env);check(pending_exception==42&&physical.phase==FAILED&&global_live==0&&depth==0);
        reset(env);fail_global=fault;prepare(env);check(pending_exception==42&&physical.phase==FAILED&&global_live==0&&depth==0);check(deleted_globals==(unsigned)(fault-1));
        reset(env);healthy(env);fail_read=reads+fault;native_check(env);check(pending_exception==42&&physical.phase==FAILED&&global_live==4&&depth==0);
    }
    for(int fault=1;fault<=2;fault++){
        reset(env);fail_frame=fault;prepare(env);check(pending_exception==42&&physical.phase==FAILED&&global_live==0&&depth==0);
    }
    reset(env);healthy(env);fail_frame=frames+1;native_check(env);check(pending_exception==42&&physical.phase==FAILED&&global_live==4&&depth==0);
    reset(env);mutate_after_roots=true;prepare(env);check(pending_exception==1&&physical.phase==FAILED&&global_live==0&&deleted_globals==4&&depth==0);
    check(traps==0);reset(env);free_locals();printf("physical JNI mock checks=%u (managed getters/writes/lookups=0)\n",checks);return 0;
}
