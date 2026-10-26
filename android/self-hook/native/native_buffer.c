#define _GNU_SOURCE
#include <jni.h>
#include <stdint.h>
#include <errno.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include "kernel_syscall.h"

/* Same kernel entry as code_snapshots.c. Unmapped/partial reads are explicit
 * unavailable, never direct pointer dereferences or signal-handler recovery. */
JNIEXPORT jbyteArray JNICALL Java_dev_tripoder_selfhook_NativeHook_snapshot0(JNIEnv *env,jclass type,jlong raw,jint length){
    (void)type;int saved=errno;jbyteArray result=NULL;
    uintptr_t address=(uintptr_t)raw&UINT64_C(0x00ffffffffffffff);
    if(length<1||length>1024||address<4096||address>UINTPTR_MAX-(size_t)length)goto done;
    unsigned char bytes[1024];
    struct iovec local={bytes,(size_t)length},remote={(void *)address,(size_t)length};
    long count=tp_kernel_syscall(SYS_process_vm_readv,tp_kernel_syscall(SYS_getpid),&local,1,&remote,1,0);
    if(count!=length)goto done;
    result=(*env)->NewByteArray(env,length);
    if(result)(*env)->SetByteArrayRegion(env,result,0,length,(const jbyte *)bytes);
done:
    errno=saved;return result;
}
