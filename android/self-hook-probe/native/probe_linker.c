#define _GNU_SOURCE
#include "modules/linker_notifier.h"
#include "modules/near_notifier.h"
#include "park_syscall.h"
#include "probe_module_concurrency.h"
#include "probe_module_instances.h"
#include "probe_maps.h"
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
static unsigned checks,failures;
static const char *first;
extern void tp_notifier_noop(void),tp_notifier_clobber(void);
extern void tp_notifier_register_oracle(void (*)(void),uint64_t output[100]);
static void check(bool value,const char *name){checks++;if(!value){failures++;if(!first)first=name;}}
static struct tp_module_snapshot snapshot(unsigned index){
    struct tp_module_snapshot value={0};check(tp_linker_snapshot(index,&value),"SNAPSHOT");return value;
}
/* All files are this APK's own fixtures copied by its activity to unique paths.
 * No app data is cleared and no loader handle is retained by the monitor. */
static bool death_witness(const char *directory,struct tp_module_snapshot loaded){
    struct tp_module_snapshot leased={0};struct tp_linker_report report={0};tp_linker_report(&report);
    if(!tp_linker_snapshot(0,&leased)||!leased.live||leased.references!=1||leased.generation!=loaded.generation)return false;
    if(!tp_probe_maps_save(directory,"active-unload"))return false;
    char path[2048],json[1024];int length=snprintf(path,sizeof(path),"%s/active-unload-ready.json",directory);
    if(length<=0||(size_t)length>=sizeof(path))return false;
    int fd=open(path,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0644);if(fd<0)return false;
    length=snprintf(json,sizeof(json),"{\"pid\":%d,\"installed\":%s,\"generation\":%u,\"references\":%u,\"base\":\"%llx\",\"checks\":%u,\"failures\":%u,\"expectedExitStatus\":198}\n",
        getpid(),report.installed?"true":"false",leased.generation,leased.references,(unsigned long long)leased.base,checks,failures);
    bool ok=length>0&&(size_t)length<sizeof(json)&&write(fd,json,(size_t)length)==length&&fsync(fd)==0;
    return close(fd)==0&&ok;
}
static const char *run(const char *paths[4],bool death){
    static char output[3072];struct tp_linker_report report={0};
    struct tp_module_concurrency_report concurrency={0};
    struct tp_module_instances instances={0};
    if(checks)return "{\"passed\":false,\"error\":\"REPEATED_RUN\"}";
    check(tp_linker_install(paths,3),"INSTALL_REAL_LINKER_NOTIFIER");
    if(failures)goto done;
    struct tp_near_notifier oracle={0};tp_linker_report(&report);
    check(tp_near_notifier_prepare(report.notifier,tp_notifier_clobber,&oracle),"FULL_REGISTER_ORACLE_RELAY");
    if(failures)goto done;
    uint64_t expected[100],actual[100];int *error_cell=&errno;*error_cell=EALREADY;
    tp_notifier_register_oracle(tp_notifier_noop,expected);
    tp_notifier_register_oracle((void (*)(void))oracle.entry,actual);
    int returned_errno=*error_cell;expected[16]=oracle.entry;
    check(!memcmp(expected,actual,31*8),"ALL_31_GPRS_PRESERVED");
    check(!memcmp(expected+32,actual+32,32*16),"ALL_32_SIMD_128_BITS_PRESERVED");
    check(expected[31]==actual[31]&&expected[96]==actual[96]&&expected[97]==actual[97],"NZCV_FPSR_FPCR_PRESERVED");
    check(returned_errno==EALREADY,"ERRNO_UNTOUCHED");
    struct tp_module_snapshot absent=snapshot(0);check(!absent.live&&!absent.generation,"INITIAL_NOT_LOADED");
    errno=EBUSY;void *one=dlopen(paths[0],RTLD_NOW|RTLD_LOCAL);check(one!=NULL,"FIRST_DLOPEN");
    if(!one)goto done;
    struct tp_module_snapshot loaded=snapshot(0);check(loaded.live&&loaded.generation==1&&loaded.base,"FIRST_GENERATION");
    uint64_t (*target)(uint64_t)=dlsym(one,"tp_fixture_target");check(target&&target(41)==42,"LOADED_EXECUTABLE");
    void *two=dlopen(paths[0],RTLD_NOW|RTLD_LOCAL);check(two==one,"EXISTING_MODULE_REUSE");
    struct tp_module_snapshot reused=snapshot(0);check(reused.live&&reused.generation==loaded.generation&&reused.base==loaded.base,"REUSE_NOT_NEW_GENERATION");
    if(two)check(dlclose(two)==0,"CLOSE_SECOND_REFERENCE");
    reused=snapshot(0);check(reused.live&&reused.generation==loaded.generation,"ONE_REFERENCE_REMAINS");
    check(tp_linker_acquire(0,loaded.generation),"LEASE_ACQUIRED");
    if(death){
        /* Parent Activity has fsynced a separate witness before invoking us.
         * The fixed monitor must exit_group(198) before dlclose returns. */
        if(failures||!death_witness(paths[3],loaded))return "{\"passed\":false,\"error\":\"ACTIVE_UNLOAD_WITNESS_FAILED\"}";
        dlclose(one);return "{\"passed\":false,\"error\":\"ACTIVE_UNLOAD_RETURNED\"}";
    }
    void *bad=dlopen(paths[1],RTLD_NOW|RTLD_LOCAL);check(bad==NULL,"MALFORMED_ELF_REJECTED");if(bad)dlclose(bad);
    struct tp_module_snapshot invalid=snapshot(1);check(!invalid.live&&!invalid.generation,"INVALID_NEVER_AVAILABLE");
    reused=snapshot(0);check(reused.live&&reused.generation==loaded.generation&&reused.references==1,"UNRELATED_FAILURE_PRESERVES_LEASE");
    check(target&&target(19)==20,"TARGET_AFTER_FAILED_LOAD");
    bad=dlopen(paths[2],RTLD_NOW|RTLD_LOCAL);check(bad==NULL,"UNRESOLVED_RELOCATION_REJECTED");if(bad)dlclose(bad);
    invalid=snapshot(2);check(!invalid.live,"LATE_FAILURE_NOT_LEFT_AVAILABLE");
    check(tp_linker_release(0,loaded.generation),"LEASE_RELEASED");
    check(!tp_linker_release(0,loaded.generation),"DOUBLE_RELEASE_REJECTED");
    check(dlclose(one)==0,"FINAL_DLCLOSE");one=NULL;
    struct tp_module_snapshot removed=snapshot(0);check(!removed.live&&removed.generation==loaded.generation&&!removed.base,"REMOVED_BEFORE_DLCLOSE_RETURNS");
    check(!tp_linker_acquire(0,loaded.generation),"STALE_GENERATION_CANNOT_ACQUIRE");
    one=dlopen(paths[0],RTLD_NOW|RTLD_LOCAL);check(one!=NULL,"RELOAD");
    struct tp_module_snapshot next=snapshot(0);check(next.live&&next.generation==loaded.generation+1,"RELOAD_NEW_GENERATION_EVEN_SAME_ADDRESS");
    check(!tp_linker_acquire(0,loaded.generation),"OLD_GENERATION_NOT_REVIVED");
    check(tp_linker_acquire(0,next.generation),"NEW_GENERATION_LEASE");check(tp_linker_release(0,next.generation),"NEW_GENERATION_RELEASE");
    if(one)check(dlclose(one)==0,"RELOAD_CLOSED");
    next=snapshot(0);check(!next.live&&next.generation==2,"RELOAD_REMOVED");
    instances=tp_probe_module_instances(paths[0]);check(instances.checks==13&&!instances.failures,"MULTIPLE_INSTANCE_IDENTITY_NOT_A_PROCESS_FAILURE");
    concurrency=tp_probe_module_concurrency(paths[0]);
    check(concurrency.threads==4,"FOUR_MODULE_WORKERS");check(concurrency.cycles==400&&concurrency.errors==0,"CONCURRENT_LOAD_ACQUIRE_CALL_RELEASE_CLOSE");
    check(concurrency.removed,"CONCURRENT_FINAL_REMOVAL");
    tp_linker_report(&report);check(report.installed&&report.notifications>=8&&report.consistent>=4&&report.added>=2&&report.removed>=2,"SYNCHRONOUS_NOTIFICATIONS");
done:
    tp_linker_report(&report);
    snprintf(output,sizeof(output),"{\"passed\":%s,\"checks\":%u,\"failures\":%u,\"firstFailure\":\"%s\","
        "\"stage\":\"%s\",\"error\":%d,\"installed\":%s,\"base\":\"%llx\",\"debug\":\"%llx\",\"notifier\":\"%llx\",\"relay\":\"%llx\","
        "\"seals\":%u,\"fdePcs\":%u,\"parked\":%u,\"notifications\":%llu,\"consistent\":%llu,\"added\":%llu,\"removed\":%llu,"
        "\"loaderName\":\"%s\",\"identityFlags\":%u,\"concurrentCycles\":%u,\"concurrentErrors\":%u,\"instanceChecks\":%u,\"instanceFailures\":%u,\"instanceFirstFailure\":\"%s\",\"productAccepted\":false,\"nativeHookInstalled\":false}",failures?"false":"true",checks,failures,first?first:"",report.stage?report.stage:"",report.error,
        report.installed?"true":"false",(unsigned long long)report.base,(unsigned long long)report.debug,(unsigned long long)report.notifier,(unsigned long long)report.relay,
        report.seals,report.fde_pcs,report.parked,(unsigned long long)report.notifications,(unsigned long long)report.consistent,(unsigned long long)report.added,(unsigned long long)report.removed,report.loader_name,report.identity_flags,concurrency.cycles,concurrency.errors,instances.checks,instances.failures,instances.first?instances.first:"");
    return output;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_LinkerProbe_run(JNIEnv *env,jclass type,jstring good,jstring malformed,jstring late,jstring directory,jboolean death){
    (void)type;jstring strings[4]={good,malformed,late,directory};const char *paths[4]={0};unsigned count=0;
    for(;count<4;count++){if(!strings[count])break;paths[count]=(*env)->GetStringUTFChars(env,strings[count],NULL);if(!paths[count])break;}
    const char *result=count==4?run(paths,death):NULL;
    for(unsigned i=0;i<count;i++)(*env)->ReleaseStringUTFChars(env,strings[i],paths[i]);
    return result?(*env)->NewStringUTF(env,result):NULL;
}
