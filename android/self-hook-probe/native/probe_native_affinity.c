#define _GNU_SOURCE
#include <jni.h>
#include <sched.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

static unsigned long long bits(const cpu_set_t *set){unsigned long long value=0;memcpy(&value,set,sizeof(value));return value;}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_NativeBenchmark_affinityReport(JNIEnv *env,jclass cls){
    (void)cls;cpu_set_t initial;CPU_ZERO(&initial);int get=sched_getaffinity(0,sizeof(initial),&initial),saved=errno;
    char output[16384];int at=snprintf(output,sizeof(output),"{\"tid\":%d,\"getResult\":%d,\"getErrno\":%d,\"initialMask\":\"%llx\",\"initialCount\":%d,\"experiments\":[",gettid(),get,get?saved:0,bits(&initial),CPU_COUNT(&initial));
    if(get)return (*env)->NewStringUTF(env,"{\"error\":\"INITIAL_AFFINITY\"}");
    unsigned experiment=0;
    for(int cpu=0;cpu<64;cpu++)if(CPU_ISSET(cpu,&initial))for(unsigned explicit_tid=0;explicit_tid<2;explicit_tid++){
        cpu_set_t desired,after,raw;CPU_ZERO(&desired);CPU_ZERO(&after);CPU_ZERO(&raw);CPU_SET(cpu,&desired);
        pid_t tid=explicit_tid?gettid():0;errno=0;int set=sched_setaffinity(tid,sizeof(desired),&desired),set_error=errno;
        int read=sched_getaffinity(0,sizeof(after),&after),actual=sched_getcpu();
        long raw_size=syscall(__NR_sched_getaffinity,0,sizeof(raw),&raw);
        struct timespec pause={0,10000000};nanosleep(&pause,NULL);cpu_set_t later;CPU_ZERO(&later);int late=sched_getaffinity(0,sizeof(later),&later);
        at+=snprintf(output+at,sizeof(output)-(size_t)at,"%s{\"cpu\":%d,\"explicitTid\":%s,\"setResult\":%d,\"setErrno\":%d,\"getResult\":%d,\"mask\":\"%llx\",\"count\":%d,\"actualCpu\":%d,\"rawBytes\":%ld,\"rawMask\":\"%llx\",\"lateResult\":%d,\"lateMask\":\"%llx\",\"lateCpu\":%d}",experiment++?",":"",cpu,explicit_tid?"true":"false",set,set?set_error:0,read,bits(&after),CPU_COUNT(&after),actual,raw_size,bits(&raw),late,bits(&later),sched_getcpu());
        if(at<0||(size_t)at>sizeof(output)-1024)return (*env)->NewStringUTF(env,"{\"error\":\"REPORT_CAPACITY\"}");
    }
    int restored=sched_setaffinity(0,sizeof(initial),&initial);cpu_set_t final;CPU_ZERO(&final);int read=sched_getaffinity(0,sizeof(final),&final);
    snprintf(output+at,sizeof(output)-(size_t)at,"],\"restoreResult\":%d,\"finalGetResult\":%d,\"finalMask\":\"%llx\"}",restored,read,bits(&final));
    return (*env)->NewStringUTF(env,output);
}

