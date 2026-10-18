#define _GNU_SOURCE
#include "tripoder_native_hook.h"
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <time.h>
#include <sched.h>
#include <errno.h>
#include <sys/resource.h>
#include <stdio.h>
static int (*bench_target)(int,int);
static int benchmark_cpu=-1;
static void touch_samples(jlong *samples,size_t count){for(size_t i=0;i<count;i+=4096/sizeof(*samples))((volatile jlong *)samples)[i]=0;}
JNIEXPORT jlongArray JNICALL Java_dev_tripoder_selfhookprobe_NativeBenchmark_measureState(JNIEnv *env,jclass cls){
    (void)cls;struct timespec wall,cpu;struct rusage usage;clock_gettime(CLOCK_MONOTONIC,&wall);clock_gettime(CLOCK_THREAD_CPUTIME_ID,&cpu);getrusage(RUSAGE_THREAD,&usage);
    char path[128];snprintf(path,sizeof(path),"/sys/devices/system/cpu/cpu%d/cpufreq/scaling_cur_freq",benchmark_cpu);errno=0;FILE *stream=fopen(path,"r");long frequency=-1;int error=errno;
    if(stream){if(fscanf(stream,"%ld",&frequency)!=1){frequency=-1;error=errno;}fclose(stream);}else if(!error)error=EIO;
    jlong values[]={(jlong)wall.tv_sec*1000000000+wall.tv_nsec,(jlong)cpu.tv_sec*1000000000+cpu.tv_nsec,usage.ru_nvcsw,usage.ru_nivcsw,usage.ru_minflt,sched_getcpu(),frequency,error};
    jlongArray out=(*env)->NewLongArray(env,8);if(out)(*env)->SetLongArrayRegion(env,out,0,8,values);return out;
}
static bool affinity_valid(void){cpu_set_t mask;CPU_ZERO(&mask);return benchmark_cpu>=0&&!sched_getaffinity(0,sizeof(mask),&mask)&&CPU_COUNT(&mask)==1&&CPU_ISSET(benchmark_cpu,&mask)&&sched_getcpu()==benchmark_cpu;}
static uint64_t now(void){struct timespec t;if(clock_gettime(CLOCK_MONOTONIC,&t))abort();return (uint64_t)t.tv_sec*1000000000u+t.tv_nsec;}
static bool forward(void *context,uint64_t token,const struct tp_scalar_value *args,unsigned count,int incoming,struct tp_scalar_result *result){
    (void)context;return tp_native_original(token,args,count,incoming,result);
}
static bool measure(jlong *samples,unsigned count,unsigned batch){
    if(!affinity_valid())return false;
    uint64_t warm=now();do{if(bench_target(3,4)!=7)return false;}while(now()-warm<UINT64_C(500000000));
    for(unsigned i=0;i<count;i++){
        uint64_t start=now();int total=0;for(unsigned j=0;j<batch;j++)total+=bench_target(3,4);
        samples[i]=(jlong)(now()-start);if(total!=(int)(7*batch))return false;
    }return affinity_valid();
}
JNIEXPORT jint JNICALL Java_dev_tripoder_selfhookprobe_NativeBenchmark_pinThread(JNIEnv *env,jclass type,jint cpu){
    (void)env;(void)type;cpu_set_t allowed,chosen;CPU_ZERO(&allowed);CPU_ZERO(&chosen);
    if(sched_getaffinity(0,sizeof(allowed),&allowed))return -1000-errno;
    if(cpu<0||cpu>=CPU_SETSIZE||!CPU_ISSET(cpu,&allowed))return -2000;CPU_SET(cpu,&chosen);
    if(sched_setaffinity(0,sizeof(chosen),&chosen))return -3000-errno;
    /* Android may complete the affinity request before migration is visible.
     * Wait for the requested CPU; never benchmark on another CPU silently. */
    uint64_t deadline=now()+UINT64_C(500000000);int actual;
    do{actual=sched_getcpu();if(actual==cpu)break;struct timespec pause={0,1000000};nanosleep(&pause,NULL);}while(now()<deadline);
    if(actual!=cpu)return -400000-cpu*100-actual;
    CPU_ZERO(&allowed);if(sched_getaffinity(0,sizeof(allowed),&allowed))return -5000-errno;
    if(CPU_COUNT(&allowed)!=1||!CPU_ISSET(cpu,&allowed))return -6000;
    benchmark_cpu=cpu;return cpu;
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhookprobe_NativeBenchmark_verifyThread(JNIEnv *env,jclass type,jint cpu){
    (void)env;(void)type;cpu_set_t mask;CPU_ZERO(&mask);
    return cpu>=0&&cpu<CPU_SETSIZE&&!sched_getaffinity(0,sizeof(mask),&mask)&&CPU_COUNT(&mask)==1&&CPU_ISSET(cpu,&mask)&&sched_getcpu()==cpu;
}
JNIEXPORT jlongArray JNICALL Java_dev_tripoder_selfhookprobe_NativeBenchmark_nativeSamples(JNIEnv *env,jclass type,jstring library,jlong generation,jlong rva,jint count){
    (void)type;if(count<1000||count>100000)return NULL;
    const char *path=(*env)->GetStringUTFChars(env,library,NULL);if(!path)return NULL;
    void *module=dlopen(path,RTLD_NOW|RTLD_LOCAL|RTLD_NOLOAD);if(!module){(*env)->ReleaseStringUTFChars(env,library,path);return NULL;}
    bench_target=dlsym(module,"tripoder_hook_add");jlong *samples=calloc((size_t)count*2+3,sizeof(jlong));jlongArray output=NULL;unsigned handle;
    if(!bench_target||!samples)goto failed;touch_samples(samples,(size_t)count*2+3);
    if(!measure(samples,(unsigned)count,64))goto failed;
    const struct tp_scalar_signature signature={.count=2,.result=TP_SCALAR_I32,.arguments={TP_SCALAR_I32,TP_SCALAR_I32}};
    uint64_t start=now();
    if(!tp_native_prepare(0,(uint32_t)generation,(uintptr_t)rva,path,&signature,forward,NULL,&handle))goto failed;
    samples[count*2]=(jlong)(now()-start);start=now();
    if(!tp_native_publish(&handle,1))goto failed;
    samples[count*2+1]=(jlong)(now()-start);
    if(!measure(samples+count,(unsigned)count,64))goto failed;
    start=now();if(!tp_native_retire(&handle,1))goto failed;samples[count*2+2]=(jlong)(now()-start);
    output=(*env)->NewLongArray(env,count*2+3);if(output)(*env)->SetLongArrayRegion(env,output,0,count*2+3,samples);
    dlclose(module);
failed:
    free(samples);(*env)->ReleaseStringUTFChars(env,library,path);return output;
}
struct foreign {jlong *values;unsigned count;bool ok;};
static void *foreign(void *data){struct foreign *job=data;job->ok=measure(job->values,job->count,1);return NULL;}
JNIEXPORT jlongArray JNICALL Java_dev_tripoder_selfhookprobe_NativeBenchmark_foreignSamples(JNIEnv *env,jclass type,jint count){
    (void)type;if(!bench_target||count<1000||count>100000)return NULL;
    struct foreign job={.values=calloc((size_t)count,sizeof(jlong)),.count=(unsigned)count};if(!job.values)return NULL;
    touch_samples(job.values,(size_t)count);
    pthread_t thread;jlongArray output=NULL;
    if(pthread_create(&thread,NULL,foreign,&job))goto done;
    if(pthread_join(thread,NULL))abort();
    if(job.ok){output=(*env)->NewLongArray(env,count);if(output)(*env)->SetLongArrayRegion(env,output,0,count,job.values);}
done:free(job.values);return output;
}
