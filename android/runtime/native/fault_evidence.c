#define _GNU_SOURCE
#include "kernel_syscall.h"
#include "signal_chain.h"
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <sys/syscall.h>
#include <ucontext.h>

/* Opt-in diagnosis: a fixed ring of code locations and one instruction word,
 * never process data or stacks. ART and the guest retain their signal semantics. */
static int output=-1;
static _Atomic unsigned sequence,stage;
static size_t literal(char *out,size_t at,const char *text){while(*text&&at<510)out[at++]=*text++;return at;}
static size_t number(char *out,size_t at,uint64_t value,unsigned radix){
    char reverse[24];unsigned n=0;do{reverse[n++]="0123456789abcdef"[value%radix];value/=radix;}while(value&&n<sizeof(reverse));
    while(n&&at<510)out[at++]=reverse[--n];return at;
}
static uintptr_t hex(const char **text){
    uintptr_t result=0;for(;;){unsigned char ch=(unsigned char)**text;unsigned digit;
        if(ch>='0'&&ch<='9')digit=ch-'0';else if(ch>='a'&&ch<='f')digit=ch-'a'+10;else break;
        result=(result<<4)|digit;(*text)++;}return result;
}
static size_t mapping(char *out,size_t at,const char *line,uintptr_t address,const char *label){
    const char *cursor=line;uintptr_t first=hex(&cursor);if(*cursor++!='-')return at;uintptr_t last=hex(&cursor);
    if(address<first||address>=last)return at;
    while(*cursor==' ')cursor++;const char *permissions=cursor;while(*cursor&&*cursor!=' ')cursor++;while(*cursor==' ')cursor++;
    uintptr_t offset=hex(&cursor);const char *base=NULL;
    for(const char *p=cursor;*p;p++)if(*p=='/')base=p+1;
    at=literal(out,at,label);at=literal(out,at,"=");
    /* Only an ELF basename is admitted; anonymous/JIT names and paths are private. */
    if(base){const char *end=base;while((*end>='a'&&*end<='z')||(*end>='A'&&*end<='Z')||(*end>='0'&&*end<='9')||*end=='_'||*end=='-'||*end=='.'||*end=='+')end++;
        if(end-base>3&&end[-3]=='.'&&end[-2]=='s'&&end[-1]=='o'){for(const char *p=base;p<end&&at<470;p++)out[at++]=*p;}
        else at=literal(out,at,"mapped");
    }else at=literal(out,at,"anonymous");
    at=literal(out,at,"+0x");at=number(out,at,address-first+offset,16);at=literal(out,at,":");
    for(unsigned i=0;i<4&&permissions[i]&&permissions[i]!=' '&&at<510;i++)out[at++]=permissions[i];return literal(out,at," ");
}
static long capture(long signal,long info_address,long context_address){
    int saved=errno;siginfo_t *info=(void *)info_address;ucontext_t *context=(void *)context_address;
    unsigned seq=atomic_fetch_add_explicit(&sequence,1,memory_order_relaxed);char record[512];for(unsigned i=0;i<sizeof(record);i++)record[i]=' ';
    size_t at=literal(record,0,"TP_FAULT_V1 sequence=");at=number(record,at,seq,10);
    at=literal(record,at," stage=");at=number(record,at,atomic_load_explicit(&stage,memory_order_relaxed),10);
    at=literal(record,at," signal=");at=number(record,at,(unsigned)signal,10);
    at=literal(record,at," code=");if(info&&info->si_code<0){at=literal(record,at,"-");at=number(record,at,(unsigned)-info->si_code,10);}else at=number(record,at,info?(unsigned)info->si_code:0,10);
    at=literal(record,at," tid=");at=number(record,at,(uint64_t)tp_kernel_syscall(SYS_gettid),10);at=literal(record,at," ");
    uintptr_t pc=context->uc_mcontext.pc,lr=context->uc_mcontext.regs[30];
    /* Exactly one instruction at the code location; no data registers or stacks. */
    bool instruction_read=false;
    int memory=(int)tp_kernel_syscall(SYS_openat,AT_FDCWD,"/proc/self/mem",O_RDONLY|O_CLOEXEC,0);
    if(memory>=0){uint32_t instruction=0;if(tp_kernel_syscall(SYS_pread64,memory,&instruction,sizeof(instruction),(long)pc)==sizeof(instruction)){
        at=literal(record,at,"instruction=0x");at=number(record,at,instruction,16);at=literal(record,at," ");instruction_read=true;}tp_kernel_syscall(SYS_close,memory);}
    if(!instruction_read)at=literal(record,at,"instruction=unavailable ");
    int fd=(int)tp_kernel_syscall(SYS_openat,AT_FDCWD,"/proc/self/maps",O_RDONLY|O_CLOEXEC,0);
    if(fd>=0){char buffer[1024],line[4096];size_t used=0;long count;
        while((count=tp_kernel_syscall(SYS_read,fd,buffer,sizeof(buffer)))>0){for(long i=0;i<count;i++){
            if(buffer[i]=='\n'){line[used]=0;at=mapping(record,at,line,pc,"pc");at=mapping(record,at,line,lr,"lr");if(info)at=mapping(record,at,line,(uintptr_t)info->si_addr,"fault");used=0;}
            else if(used<sizeof(line)-1)line[used++]=buffer[i];
        }}
        tp_kernel_syscall(SYS_close,fd);
    }
    record[511]='\n';tp_kernel_syscall(SYS_pwrite64,output,record,sizeof(record),(long)(seq%32)*sizeof(record));errno=saved;return 0;
}
static bool observe(int signal,siginfo_t *info,void *context){
    int saved=errno;if(output>=0)tp_native_stack_call((void *)capture,signal,(long)info,(long)context);errno=saved;return false;
}
JNIEXPORT void JNICALL Java_dev_tripoder_runtime_RuntimeSelfHookStorage_faultEvidenceNative(JNIEnv *env,jclass type,jstring path){
    (void)type;const char *name=(*env)->GetStringUTFChars(env,path,NULL);if(!name)return;
    output=(int)tp_kernel_syscall(SYS_openat,AT_FDCWD,name,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0600);
    (*env)->ReleaseStringUTFChars(env,path,name);int result=output<0?-1:0;
    int signals[]={SIGSEGV,SIGBUS,SIGABRT};for(unsigned i=0;!result&&i<sizeof(signals)/sizeof(signals[0]);i++)result=tp_signal_chain_claim(signals[i],observe);
    if(result)(*env)->ThrowNew(env,(*env)->FindClass(env,"java/io/IOException"),"FAULT_EVIDENCE_INSTALL_FAILED");
}
JNIEXPORT void JNICALL Java_dev_tripoder_runtime_RuntimeSelfHookStorage_faultStageNative(JNIEnv *env,jclass type,jint value){
    (void)env;(void)type;atomic_store_explicit(&stage,(unsigned)value,memory_order_relaxed);
}
