#define _GNU_SOURCE
#include "tripoder_fixed_libc.h"
#include "tripoder_thread_park.h"
#include "path_policy.h"
#include "io_adapter.h"
#include "elf_plt_hook.h"
#include <fcntl.h>
#include <jni.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <unistd.h>
#include <link.h>
#include <dlfcn.h>
#include <signal.h>
#include <ucontext.h>
#include <unwind.h>

struct trace_state {
    int count;
    void *addrs[32];
};

static _Unwind_Reason_Code trace_cb(struct _Unwind_Context *ctx, void *arg) {
    struct trace_state *state = (struct trace_state *)arg;
    if (state->count < 32) {
        state->addrs[state->count++] = (void *)_Unwind_GetIP(ctx);
        return _URC_NO_REASON;
    }
    return _URC_END_OF_STACK;
}

static void on_exit_handler(void) {
    void *liblog = dlopen("liblog.so", RTLD_NOW);
    int (*log_print)(int, const char*, const char*, ...) = liblog ? (int (*)(int, const char*, const char*, ...))dlsym(liblog, "__android_log_print") : NULL;
    if (!log_print) return;

    struct trace_state state;
    memset(&state, 0, sizeof(state));
    _Unwind_Backtrace(trace_cb, &state);
    log_print(6, "TripoderExit", "ATEXIT CALLED! tid=%d, frames=%d", (int)syscall(SYS_gettid), state.count);
    for (int i = 0; i < state.count; i++) {
        Dl_info info;
        if (dladdr(state.addrs[i], &info) && info.dli_fname) {
            log_print(6, "TripoderExit", "  #%02d pc %p (%s + 0x%lx %s)",
                i, state.addrs[i], info.dli_fname,
                (unsigned long)((uintptr_t)state.addrs[i] - (uintptr_t)info.dli_fbase),
                info.dli_sname ? info.dli_sname : "?");
        } else {
            log_print(6, "TripoderExit", "  #%02d pc %p", i, state.addrs[i]);
        }
    }
}

static struct sigaction s_old_segv, s_old_bus;
static void tp_crash_handler(int sig, siginfo_t *info, void *uctx_ptr) {
    ucontext_t *uc = (ucontext_t *)uctx_ptr;
    uintptr_t pc = 0, sp = 0, lr = 0;
#if defined(__aarch64__)
    if (uc) {
        pc = (uintptr_t)uc->uc_mcontext.pc;
        sp = (uintptr_t)uc->uc_mcontext.sp;
        lr = (uintptr_t)uc->uc_mcontext.regs[30];
    }
#endif
    Dl_info pc_info = {0}, lr_info = {0};
    if (pc) dladdr((void *)pc, &pc_info);
    if (lr) dladdr((void *)lr, &lr_info);

    char msg[1024];
    int n = snprintf(msg, sizeof(msg),
        "[TripoderCrash] sig=%d code=%d addr=%p pc=%p (%s + 0x%lx %s) lr=%p (%s + 0x%lx %s) sp=%p\n",
        sig, info ? info->si_code : 0, info ? info->si_addr : 0,
        (void *)pc, pc_info.dli_fname ? pc_info.dli_fname : "?", (unsigned long)(pc ? pc - (uintptr_t)pc_info.dli_fbase : 0), pc_info.dli_sname ? pc_info.dli_sname : "?",
        (void *)lr, lr_info.dli_fname ? lr_info.dli_fname : "?", (unsigned long)(lr ? lr - (uintptr_t)lr_info.dli_fbase : 0), lr_info.dli_sname ? lr_info.dli_sname : "?",
        (void *)sp);
    if (n > 0) {
        syscall(SYS_write, 2, msg, (size_t)n);
        int (*log_print)(int, const char*, const char*, ...) = (int (*)(int, const char*, const char*, ...))dlsym(RTLD_DEFAULT, "__android_log_print");
        if (log_print) log_print(6, "TripoderCrash", "%s", msg);
    }
    struct sigaction *old_sa = (sig == SIGSEGV) ? &s_old_segv : &s_old_bus;
    if ((old_sa->sa_flags & SA_SIGINFO) && old_sa->sa_sigaction) {
        old_sa->sa_sigaction(sig, info, uctx_ptr);
    } else if (old_sa->sa_handler != SIG_DFL && old_sa->sa_handler != SIG_IGN && old_sa->sa_handler) {
        old_sa->sa_handler(sig);
    } else {
        signal(sig, SIG_DFL);
        raise(sig);
    }
}
static void tp_install_crash_handler(void) {
    atexit(on_exit_handler);
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = tp_crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigaction(SIGSEGV, &sa, &s_old_segv);
    sigaction(SIGBUS, &sa, &s_old_bus);
}

static int (*s_real_loader_dladdr)(const void *, Dl_info *);
static _Thread_local char s_dladdr_buf[PATH_MAX];

static int tp_wrapped_dladdr(const void *addr, Dl_info *info) {
    if (!s_real_loader_dladdr) return 0;
    int ret = s_real_loader_dladdr(addr, info);
    if (ret && info && info->dli_fname) {
        ssize_t n = tp_unresolve(info->dli_fname, strlen(info->dli_fname), s_dladdr_buf, sizeof(s_dladdr_buf)-1);
        if (n > 0) {
            s_dladdr_buf[n] = '\0';
            info->dli_fname = s_dladdr_buf;
        }
    }
    return ret;
}

typedef int (*dl_iterate_cb)(struct dl_phdr_info *info, size_t size, void *data);
struct wrapped_iter_ctx {
    dl_iterate_cb original_cb;
    void *original_data;
};
static _Thread_local char s_iter_buf[PATH_MAX];

static int tp_iter_callback_wrapper(struct dl_phdr_info *info, size_t size, void *data) {
    struct wrapped_iter_ctx *ctx = (struct wrapped_iter_ctx *)data;
    if (info && info->dlpi_name) {
        ssize_t n = tp_unresolve(info->dlpi_name, strlen(info->dlpi_name), s_iter_buf, sizeof(s_iter_buf)-1);
        if (n > 0) {
            s_iter_buf[n] = '\0';
            struct dl_phdr_info copy = *info;
            copy.dlpi_name = s_iter_buf;
            return ctx->original_cb(&copy, size, ctx->original_data);
        }
    }
    return ctx->original_cb(info, size, ctx->original_data);
}

static int (*s_real_loader_dl_iterate_phdr)(dl_iterate_cb, void *);
static int tp_wrapped_dl_iterate_phdr(dl_iterate_cb cb, void *data) {
    if (!s_real_loader_dl_iterate_phdr) return -1;
    struct wrapped_iter_ctx ctx = {cb, data};
    return s_real_loader_dl_iterate_phdr(tp_iter_callback_wrapper, &ctx);
}

static int hook_libdl_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size; (void)data;
    if (info->dlpi_name && strstr(info->dlpi_name, "libdl.so")) {
        uintptr_t slot_dladdr = 0, slot_iter = 0;
        if (tp_elf_plt_find_slot(info->dlpi_addr, info->dlpi_phdr, info->dlpi_phnum, "__loader_dladdr", &slot_dladdr) == 0 && slot_dladdr) {
            uintptr_t old = 0;
            if (tp_elf_plt_patch_slot(slot_dladdr, (uintptr_t)tp_wrapped_dladdr, &old) == 0 && old) {
                s_real_loader_dladdr = (int (*)(const void *, Dl_info *))old;
            }
        }
        if (tp_elf_plt_find_slot(info->dlpi_addr, info->dlpi_phdr, info->dlpi_phnum, "__loader_dl_iterate_phdr", &slot_iter) == 0 && slot_iter) {
            uintptr_t old = 0;
            if (tp_elf_plt_patch_slot(slot_iter, (uintptr_t)tp_wrapped_dl_iterate_phdr, &old) == 0 && old) {
                s_real_loader_dl_iterate_phdr = (int (*)(dl_iterate_cb, void *))old;
            }
        }
        return 1;
    }
    return 0;
}

static _Atomic bool attempted;
static void fail(JNIEnv *env,const char *reason){
    if((*env)->ExceptionCheck(env))return;
    jclass type=(*env)->FindClass(env,"java/lang/IllegalStateException");
    if(type)(*env)->ThrowNew(env,type,reason);
}
static void fail_io(JNIEnv *env,const char *reason){
    if((*env)->ExceptionCheck(env))return;
    jclass type=(*env)->FindClass(env,"java/io/IOException");
    if(type)(*env)->ThrowNew(env,type,reason);
}
/* Resolve through the existing path policy, but obtain identity from the real
 * opened FD. Guest-visible readlink rewrites are not linker identities. */
JNIEXPORT jstring JNICALL Java_dev_tripoder_runtime_RuntimeSelfHookStorage_physicalArtifactPath(JNIEnv *env,jclass unused,jstring path){
    (void)unused;
    if(!path){fail_io(env,"ARTIFACT_PHYSICAL_PATH_REQUIRED");return NULL;}
    const char *name=(*env)->GetStringUTFChars(env,path,NULL);if(!name)return NULL;
    int fd=tp_openat(AT_FDCWD,name,O_PATH|O_NOFOLLOW|O_CLOEXEC,0);
    (*env)->ReleaseStringUTFChars(env,path,name);
    if(fd<0){fail_io(env,"ARTIFACT_PHYSICAL_OPEN_FAILED");return NULL;}
    struct stat st;char link[64],actual[PATH_MAX];
    bool regular=syscall(SYS_fstat,fd,&st)==0&&S_ISREG(st.st_mode);
    snprintf(link,sizeof(link),"/proc/self/fd/%d",fd);
    ssize_t size=regular?(ssize_t)syscall(SYS_readlinkat,AT_FDCWD,link,actual,sizeof(actual)-1):-1;
    syscall(SYS_close,fd);
    if(size<=0||size>=(ssize_t)sizeof(actual)-1){fail_io(env,"ARTIFACT_PHYSICAL_IDENTITY_FAILED");return NULL;}
    actual[size]=0;return (*env)->NewStringUTF(env,actual);
}
JNIEXPORT void JNICALL Java_dev_tripoder_runtime_RuntimeSelfHookStorage_installNative(JNIEnv *env,jclass unused,jobjectArray input,jboolean thin){
    (void)unused;bool expected=false;
    if(!atomic_compare_exchange_strong(&attempted,&expected,true)){fail(env,"NATIVE_STORAGE_ALREADY_ATTEMPTED");return;}
    jsize count=input?(*env)->GetArrayLength(env,input):0;
    if(count!=5&&count!=6){fail(env,"NATIVE_STORAGE_ARGUMENTS");return;}
    char paths[6][PATH_MAX];
    for(jsize i=0;i<count;i++){
        jbyteArray value=(*env)->GetObjectArrayElement(env,input,i);
        if(!value){fail(env,"NATIVE_STORAGE_PATH_REQUIRED");return;}
        jsize size=(*env)->GetArrayLength(env,value);
        if(size<=0||size>=PATH_MAX){(*env)->DeleteLocalRef(env,value);fail(env,"NATIVE_STORAGE_PATH_BUDGET");return;}
        (*env)->GetByteArrayRegion(env,value,0,size,(jbyte *)paths[i]);(*env)->DeleteLocalRef(env,value);
        if((*env)->ExceptionCheck(env))return;
        if(memchr(paths[i],0,(size_t)size)){fail(env,"NATIVE_STORAGE_PATH_NUL");return;}
        paths[i][size]=0;
    }
    if(tp_configure(paths[0],paths[1],paths[2],paths[3],paths[4])!=0){fail(env,"NATIVE_STORAGE_CONFIGURATION_FAILED");return;}
    if(tp_configure_process(count==6?paths[5]:paths[4])!=0){fail(env,"NATIVE_STORAGE_PROCESS_CONFIGURATION_FAILED");return;}
    tp_configure_thin(thin);
    if(tp_park_prepare_process()!=TP_PARK_OK){fail(env,"NATIVE_STORAGE_THREAD_RESERVATION_FAILED");return;}
    const struct tp_fixed_libc_replacements replacements={tp_openat,tp_fstatat,tp_unlinkat,tp_mkdirat,tp_renameat2,
        tp_faccessat,tp_readlinkat,tp_chdir,tp_symlinkat,tp_linkat};
    enum tp_fixed_libc_status prepared=tp_fixed_libc_prepare(&replacements);
    enum tp_fixed_libc_status activated=prepared==TP_FIXED_LIBC_OK?tp_fixed_libc_activate():TP_FIXED_LIBC_STATE;
    if(prepared!=TP_FIXED_LIBC_OK||activated!=TP_FIXED_LIBC_OK){
        struct tp_fixed_libc_report report;tp_fixed_libc_report(&report);char reason[600];
        snprintf(reason,sizeof(reason),"NATIVE_STORAGE_PUBLICATION_FAILED:prepare=%d:activate=%d:pause=%d:observed=%u:parked=%u:pendingTid=%d:maskValid=%d:mask=%llx:errno=%d:absolute=%u:changed=%u:admission=%u:attempts=%u:elapsedNs=%llu:contextStatus=%d:contextIndex=%d:unsafeTid=%d:unsafePc=%llx:quiescenceRejections=%u",
            prepared,activated,report.pause_status,report.pause.observed,report.pause.parked,report.pause.pending_tid,
            report.pause.pending_mask_valid,(unsigned long long)report.pause.pending_signal_mask,report.pause.system_error,
            report.absolute_entries,report.changed_words,report.admission_outcome,report.admission_attempts,
            (unsigned long long)report.admission_elapsed_ns,report.context_status,report.context_index,report.unsafe_tid,
            (unsigned long long)report.unsafe_pc,report.quiescence_rejections);
        fail(env,reason);return;
    }
    struct tp_fixed_libc_report report;tp_fixed_libc_report(&report);
    if(!report.ready||!report.enabled||report.validated_entries!=10||report.changed_words!=40||report.absolute_entries!=10||report.publications!=2||
       report.restorations||!report.source_bti||!report.lease_held)
        fail(env,"NATIVE_STORAGE_PUBLICATION_UNCONFIRMED");
    dl_iterate_phdr(hook_libdl_cb, NULL);
    tp_install_crash_handler();
}
JNIEXPORT jbyteArray JNICALL Java_dev_tripoder_runtime_RuntimeSelfHookStorage_readPhysicalProc(JNIEnv *env,jclass unused,jstring path,jint limit){
    (void)unused;
    if(!path||limit<=0||limit>4*1024*1024){fail_io(env,"RUNTIME_EVIDENCE_INVALID_ARGUMENTS");return NULL;}
    const char *raw_path=(*env)->GetStringUTFChars(env,path,NULL);
    if(!raw_path)return NULL;
    if(strncmp(raw_path,"/proc/self/",11)!=0){(*env)->ReleaseStringUTFChars(env,path,raw_path);fail_io(env,"RUNTIME_EVIDENCE_PATH_DENIED");return NULL;}
    int fd=(int)syscall(SYS_openat,AT_FDCWD,raw_path,O_RDONLY|O_CLOEXEC,0);
    (*env)->ReleaseStringUTFChars(env,path,raw_path);
    if(fd<0){fail_io(env,"RUNTIME_EVIDENCE_OPEN_FAILED");return NULL;}
    size_t cap=(size_t)limit+1,used=0;
    char *buf=malloc(cap);
    if(!buf){syscall(SYS_close,fd);fail_io(env,"RUNTIME_EVIDENCE_ALLOC_FAILED");return NULL;}
    bool ok=true;
    while(used<cap){
        ssize_t n=(ssize_t)syscall(SYS_read,fd,buf+used,cap-used);
        if(n==0)break;
        if(n<0){ok=false;break;}
        used+=(size_t)n;
    }
    syscall(SYS_close,fd);
    if(!ok){free(buf);fail_io(env,"RUNTIME_EVIDENCE_READ_FAILED");return NULL;}
    if(used>(size_t)limit){free(buf);fail_io(env,"RUNTIME_EVIDENCE_READ_BUDGET");return NULL;}
    jbyteArray out=(*env)->NewByteArray(env,(jsize)used);
    if(out&&used>0)(*env)->SetByteArrayRegion(env,out,0,(jsize)used,(const jbyte *)buf);
    free(buf);
    return out;
}
