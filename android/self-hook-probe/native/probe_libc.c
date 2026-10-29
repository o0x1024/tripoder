#define _GNU_SOURCE
#include "tripoder_fixed_libc.h"
#include "tripoder_owned_hook.h"
#include "code_page.h"
#include "io_adapter.h"
#include "path_policy.h"
#include "probe_maps.h"
#include "probe_libc_cases.h"
#include "park_syscall.h"
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static struct {
    _Atomic unsigned long long hits[11], worker_calls;
    _Atomic unsigned worker_errors;
    _Atomic bool stop;
    uintptr_t base;
    const char *worker_path;
    unsigned checks, failures;
    const char *first;
} test;
static int hook_open(int d,const char *p,int f,unsigned m){atomic_fetch_add(&test.hits[0],1);return tp_openat(d,p,f,m);}
static int hook_stat(int d,const char *p,struct stat *s,int f){atomic_fetch_add(&test.hits[1],1);return tp_fstatat(d,p,s,f);}
static int hook_unlink(int d,const char *p,int f){atomic_fetch_add(&test.hits[2],1);return tp_unlinkat(d,p,f);}
static int hook_mkdir(int d,const char *p,unsigned m){atomic_fetch_add(&test.hits[3],1);return tp_mkdirat(d,p,m);}
static int hook_rename(int a,const char *b,int c,const char *d,unsigned f){atomic_fetch_add(&test.hits[4],1);return tp_renameat2(a,b,c,d,f);}
static int hook_access(int d,const char *p,int m){atomic_fetch_add(&test.hits[5],1);return tp_faccessat(d,p,m);}
static ssize_t hook_readlink(int d,const char *p,char *b,size_t n){atomic_fetch_add(&test.hits[6],1);return tp_readlinkat(d,p,b,n);}
static int hook_chdir(const char *p){atomic_fetch_add(&test.hits[7],1);return tp_chdir(p);}
static int hook_symlink(const char *a,int b,const char *c){atomic_fetch_add(&test.hits[8],1);return tp_symlinkat(a,b,c);}
static int hook_link(int a,const char *b,int c,const char *d,int f){atomic_fetch_add(&test.hits[9],1);return tp_linkat(a,b,c,d,f);}
static int hook_chmod(int d,const char *p,unsigned m){atomic_fetch_add(&test.hits[10],1);return tp_fchmodat(d,p,m);}
static const struct tp_fixed_libc_replacements replacements={hook_open,hook_stat,hook_unlink,hook_mkdir,hook_rename,
    hook_access,hook_readlink,hook_chdir,hook_symlink,hook_link,hook_chmod};
static void check(int condition,const char *name){test.checks++;if(!condition){test.failures++;if(!test.first)test.first=name;}}
static void *worker(void *unused){
    (void)unused;
    int (*open_entry)(int,const char *,int,unsigned)=(void *)(test.base+0xe0a40);
    int (*stat_entry)(int,const char *,struct stat *,int)=(void *)(test.base+0xe0b40);
    while(!atomic_load(&test.stop)){
        struct stat s;
        int fd=open_entry(AT_FDCWD,test.worker_path,O_RDONLY|O_CLOEXEC,0);
        if(fd<0)atomic_fetch_add(&test.worker_errors,1);
        else {char data[4];if(tp_park_syscall(SYS_read,fd,(long)data,4,0,0,0)!=4||memcmp(data,"test",4))atomic_fetch_add(&test.worker_errors,1);
            tp_park_syscall(SYS_close,fd,0,0,0,0,0);}
        if(stat_entry(AT_FDCWD,test.worker_path,&s,0)||s.st_size!=4)atomic_fetch_add(&test.worker_errors,1);
        atomic_fetch_add(&test.worker_calls,1);
    }
    return NULL;
}
static int advance(unsigned long long previous){
    uint64_t deadline=tp_park_now()+UINT64_C(1000000000);
    while(atomic_load(&test.worker_calls)<=previous){if(tp_park_now()>=deadline)return 0;tp_park_sleep(100000);}
    return 1;
}
static int dump(const char *directory,const char *stage,const struct tp_fixed_libc_report *r){
    char path[PATH_MAX];
    int n=snprintf(path,sizeof(path),"%s/libc-pages-%s.bin",directory,stage);
    if(n<=0||n>=PATH_MAX)return 0;
    long fd=tp_park_syscall(SYS_openat,AT_FDCWD,(long)path,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0644,0,0);
    if(fd<0)return 0;
    int ok=1;
    for(unsigned i=0;i<2;i++){
        size_t written=0;
        while(written<4096){long count=tp_park_syscall(SYS_write,fd,(long)(r->pages[i]+written),4096-written,0,0,0);
            if(count<=0){ok=0;break;}written+=(size_t)count;}
        if(!ok)break;
    }
    if(tp_park_syscall(SYS_fsync,fd,0,0,0,0,0))ok=0;
    if(tp_park_syscall(SYS_close,fd,0,0,0,0,0))ok=0;
    return ok;
}
static int snapshot(const char *directory,const char *stage,const struct tp_fixed_libc_report *r){
    return tp_probe_maps_save(directory,stage)&&tp_probe_smaps_save(directory,stage)&&dump(directory,stage,r);
}
static void fatal(const char *directory){
    char path[PATH_MAX];
    int n=snprintf(path,sizeof(path),"%s/libc-fatal.txt",directory);
    if(n>0&&n<PATH_MAX){long fd=tp_park_syscall(SYS_openat,AT_FDCWD,(long)path,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0644,0,0);
        if(fd>=0){const char reason[]="FIXED_LIBC_RESTORE_FAILED\n";tp_park_syscall(SYS_write,fd,(long)reason,sizeof(reason)-1,0,0,0);
            tp_park_syscall(SYS_fsync,fd,0,0,0,0,0);tp_park_syscall(SYS_close,fd,0,0,0,0,0);}}
    tp_park_syscall(SYS_exit_group,197,0,0,0,0,0);__builtin_trap();
}
/* p: data, canonical data, artifacts, canonical artifacts, package,
 * logical test directory, physical concurrent file, own external denial path, evidence. */
static const char *run(const char *p[9]){
    static char report[6144];
    if(test.checks)return "{\"passed\":false,\"error\":\"REPEATED_RUN\"}";
    struct tp_fixed_libc_report state={0};
    struct tp_owned_page_report pages={0};
    struct tp_libc_case_result cases={0};
    bool active=false,after_restore=false;
    int status=TP_FIXED_LIBC_STATE;
    unsigned made=0;
    unsigned long long before_calls=0,active_calls=0;
    pthread_t threads[4];
    int cwd=(int)tp_park_syscall(SYS_openat,AT_FDCWD,(long)".",O_PATH|O_DIRECTORY|O_CLOEXEC,0,0,0);
    check(cwd>=0,"SAVE_ORIGINAL_CWD");
    if(test.failures)goto done;
    check(tp_configure(p[0],p[1],p[2],p[3],p[4])==0,"REUSE_RUNTIME_PATH_POLICY");
    if(test.failures)goto cleanup;
    status=tp_fixed_libc_prepare(&replacements);
    check(status==TP_FIXED_LIBC_OK,"FIXED_LIBC_IDENTITY_PAGES_AND_ABSOLUTE_ENTRIES");
    if(test.failures)goto cleanup;
    check(tp_fixed_libc_prepare(&replacements)==TP_FIXED_LIBC_STATE,"SECOND_OWNER_REJECTED");
    tp_fixed_libc_report(&state);
    check(state.validated_entries==10&&state.changed_words==40&&state.source_bti&&state.absolute_entries==10,"EXACT_TEN_BTI_PRESERVING_ABSOLUTE_ENTRIES");
    check(snapshot(p[8],"libc-prepared",&state),"PREPARED_MAPPING_AND_BYTE_EVIDENCE");
    if(test.failures)goto cleanup;
    test.base=state.base;test.worker_path=p[6];
    for(;made<4;made++)if(pthread_create(&threads[made],NULL,worker,NULL))break;
    check(made==4&&advance(0),"FOUR_ORIGINAL_LIBC_CALLERS");
    if(test.failures)goto cleanup;
    before_calls=atomic_load(&test.worker_calls);
    tp_fixed_libc_test_reject_next_batch();
    status=tp_fixed_libc_activate();
    check(status==TP_FIXED_LIBC_PAGE,"SECOND_PAGE_PREIMAGE_REJECTS_WHOLE_BATCH");
    tp_fixed_libc_report(&state);
    check(!state.enabled&&state.publications==0&&state.rejected_batches==1&&advance(before_calls),"REJECTED_BATCH_UNCHANGED_AND_CALLERS_RESUMED");
    check(snapshot(p[8],"libc-rejected",&state),"REJECTED_BATCH_BOTH_PAGE_IMAGES");
    if(test.failures)goto cleanup;
    status=tp_fixed_libc_activate();
    check(status==TP_FIXED_LIBC_OK,"ATOMIC_TWO_PAGE_ACTIVATION");
    if(test.failures)goto cleanup;
    active=true;
    check(tp_fixed_libc_activate()==TP_FIXED_LIBC_STATE,"DUPLICATE_ACTIVATION_REJECTED");
    check(snapshot(p[8],"libc-active",&state),"ACTIVE_MAPPING_AND_BYTE_EVIDENCE");
    tp_probe_libc_cases(state.base,p[5],p[2],p[7],&cases);
    check(cases.checks==24&&cases.failures==0,"ALL_STORAGE_ADAPTER_CASES");
    active_calls=atomic_load(&test.worker_calls);
    check(active_calls>before_calls,"CALLERS_CONTINUE_THROUGH_REAL_LIBC_REPLACEMENTS");
    status=tp_fixed_libc_deactivate();
    if(status!=TP_FIXED_LIBC_OK)fatal(p[8]);
    active=false;
    check(tp_fixed_libc_deactivate()==TP_FIXED_LIBC_STATE,"DUPLICATE_DEACTIVATION_REJECTED");
    tp_fixed_libc_report(&state);
    check(!state.enabled&&state.publications==2&&state.restorations==2,"BOTH_ORIGINAL_FILE_MAPPINGS_RESTORED");
    check(snapshot(p[8],"libc-restored",&state),"RESTORED_MAPPING_AND_BYTE_EVIDENCE");
    after_restore=advance(active_calls);
    check(after_restore,"CALLERS_CONTINUE_AFTER_RESTORATION");
cleanup:
    if(active){if(tp_fixed_libc_deactivate()!=TP_FIXED_LIBC_OK)fatal(p[8]);active=false;}
    atomic_store(&test.stop,true);
    for(unsigned i=0;i<made;i++)pthread_join(threads[i],NULL);
    check(atomic_load(&test.worker_errors)==0,"CONCURRENT_DESCRIPTOR_CONTENT_STAT_ERRORS_ZERO");
    bool all_hits=true;
    for(unsigned i=0;i<10;i++)if(!atomic_load(&test.hits[i]))all_hits=false;
    check(all_hits,"ALL_TEN_ACTUAL_KERNEL_ENTRY_REPLACEMENTS_EXECUTED");
    if(cwd>=0){check(tp_park_syscall(SYS_fchdir,cwd,0,0,0,0,0)==0,"ORIGINAL_CWD_RESTORED");tp_park_syscall(SYS_close,cwd,0,0,0,0,0);}
    if(state.restorations==2){
        int (*open_entry)(int,const char *,int,unsigned)=(void *)(state.base+0xe0a40);
        int fd=open_entry(AT_FDCWD,p[7],O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0600);
        check(fd>=0,"ORIGINAL_OWN_EXTERNAL_PATH_SEMANTICS_RESTORED");
        if(fd>=0){tp_park_syscall(SYS_close,fd,0,0,0,0,0);tp_park_syscall(SYS_unlinkat,AT_FDCWD,(long)p[7],0,0,0,0);}
    }
    tp_code_pages_report(&pages);
    check(pages.live_staging_pages==0&&pages.live_anonymous_pages==0,"SHARED_PAGE_TEMPORARIES_RECLAIMED");
done:
    tp_fixed_libc_report(&state);
    snprintf(report,sizeof(report),
        "{\"passed\":%s,\"checks\":%u,\"failures\":%u,\"firstFailure\":\"%s\",\"lastStatus\":%d,"
        "\"caseChecks\":%u,\"caseFailures\":%u,\"caseFirstFailure\":\"%s\",\"base\":\"%llx\","
        "\"pages\":[\"%llx\",\"%llx\"],\"targets\":[\"%llx\",\"%llx\",\"%llx\",\"%llx\",\"%llx\",\"%llx\",\"%llx\",\"%llx\",\"%llx\",\"%llx\"],\"validatedEntries\":%u,\"changedWords\":%u,"
        "\"publications\":%u,\"restorations\":%u,\"rejectedBatches\":%u,\"pauseStatus\":%d,\"enabled\":%s,\"sourceBti\":%s,"
        "\"quiescenceRejections\":%u,\"admission\":{\"enable\":%s,\"attempts\":%u,\"elapsedNs\":%llu,\"outcome\":%u,"
        "\"contextStatus\":%d,\"contextIndex\":%d,\"unsafeTid\":%d,\"unsafePc\":\"%llx\",\"observedThreads\":%u,\"parkedThreads\":%u,"
        "\"unsafePcCounts\":[[%u,%u,%u],[%u,%u,%u],[%u,%u,%u],[%u,%u,%u],[%u,%u,%u],"
        "[%u,%u,%u],[%u,%u,%u],[%u,%u,%u],[%u,%u,%u],[%u,%u,%u]]},"
        "\"absoluteEntries\":%u,\"processLifetimeLease\":%s,\"workerCalls\":%llu,\"workerErrors\":%u,"
        "\"workersBefore\":%llu,\"workersActive\":%llu,\"workersAfterRestore\":%s,"
        "\"entryCalls\":[%llu,%llu,%llu,%llu,%llu,%llu,%llu,%llu,%llu,%llu],"
        "\"liveStagingPages\":%u,\"liveAnonymousPages\":%u,\"runtimePolicyReused\":true,"
        "\"fixedLibcStorageOnly\":true,\"genericNativeHook\":false,\"btiFaultOracleTested\":false,\"productMigrated\":false}",
        test.failures==0?"true":"false",test.checks,test.failures,test.first?test.first:"",status,
        cases.checks,cases.failures,cases.first?cases.first:"",(unsigned long long)state.base,
        (unsigned long long)state.pages[0],(unsigned long long)state.pages[1],(unsigned long long)state.targets[0],(unsigned long long)state.targets[1],(unsigned long long)state.targets[2],(unsigned long long)state.targets[3],(unsigned long long)state.targets[4],(unsigned long long)state.targets[5],(unsigned long long)state.targets[6],(unsigned long long)state.targets[7],(unsigned long long)state.targets[8],(unsigned long long)state.targets[9],
        state.validated_entries,state.changed_words,state.publications,state.restorations,state.rejected_batches,state.pause_status,
        state.enabled?"true":"false",state.source_bti?"true":"false",
        state.quiescence_rejections,state.admission_enable?"true":"false",state.admission_attempts,
        (unsigned long long)state.admission_elapsed_ns,state.admission_outcome,
        state.context_status,state.context_index,state.unsafe_tid,(unsigned long long)state.unsafe_pc,state.pause.observed,state.pause.parked,
        state.unsafe_pc_counts[0][0],state.unsafe_pc_counts[0][1],state.unsafe_pc_counts[0][2],
        state.unsafe_pc_counts[1][0],state.unsafe_pc_counts[1][1],state.unsafe_pc_counts[1][2],
        state.unsafe_pc_counts[2][0],state.unsafe_pc_counts[2][1],state.unsafe_pc_counts[2][2],
        state.unsafe_pc_counts[3][0],state.unsafe_pc_counts[3][1],state.unsafe_pc_counts[3][2],
        state.unsafe_pc_counts[4][0],state.unsafe_pc_counts[4][1],state.unsafe_pc_counts[4][2],
        state.unsafe_pc_counts[5][0],state.unsafe_pc_counts[5][1],state.unsafe_pc_counts[5][2],
        state.unsafe_pc_counts[6][0],state.unsafe_pc_counts[6][1],state.unsafe_pc_counts[6][2],
        state.unsafe_pc_counts[7][0],state.unsafe_pc_counts[7][1],state.unsafe_pc_counts[7][2],
        state.unsafe_pc_counts[8][0],state.unsafe_pc_counts[8][1],state.unsafe_pc_counts[8][2],
        state.unsafe_pc_counts[9][0],state.unsafe_pc_counts[9][1],state.unsafe_pc_counts[9][2],state.absolute_entries,
        state.lease_held?"true":"false",atomic_load(&test.worker_calls),atomic_load(&test.worker_errors),before_calls,active_calls,
        after_restore?"true":"false",atomic_load(&test.hits[0]),atomic_load(&test.hits[1]),atomic_load(&test.hits[2]),
        atomic_load(&test.hits[3]),atomic_load(&test.hits[4]),atomic_load(&test.hits[5]),atomic_load(&test.hits[6]),
        atomic_load(&test.hits[7]),atomic_load(&test.hits[8]),atomic_load(&test.hits[9]),pages.live_staging_pages,pages.live_anonymous_pages);
    return report;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_LibcProbe_runNative(JNIEnv *env,jclass type,
        jstring data,jstring canonical,jstring artifacts,jstring canonical_artifacts,jstring package,
        jstring logical,jstring concurrent,jstring foreign,jstring evidence){
    (void)type;
    jstring values[9]={data,canonical,artifacts,canonical_artifacts,package,logical,concurrent,foreign,evidence};
    const char *p[9]={0};
    unsigned count=0;
    for(;count<9;count++){if(!values[count])break;p[count]=(*env)->GetStringUTFChars(env,values[count],NULL);if(!p[count])break;}
    const char *result=count==9?run(p):NULL;
    for(unsigned i=0;i<count;i++)(*env)->ReleaseStringUTFChars(env,values[i],p[i]);
    return result?(*env)->NewStringUTF(env,result):NULL;
}
