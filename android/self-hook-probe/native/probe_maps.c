#define _GNU_SOURCE
#include "probe_maps.h"
#include <stdio.h>
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>

/* Independent harness evidence uses only our run directory and fixed proc files. */
static int save(const char *directory, const char *stage, const char *kind, const char *source) {
    char path[2048];
    int length=snprintf(path,sizeof(path),"%s/%s-%s.txt",directory,kind,stage);
    if (length<0 || (size_t)length>=sizeof(path)) return 0;
    int input=(int)syscall(SYS_openat,AT_FDCWD,source,O_RDONLY|O_CLOEXEC,0);
    if (input<0) return 0;
    int output=(int)syscall(SYS_openat,AT_FDCWD,path,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0644);
    if (output<0) { syscall(SYS_close,input); return 0; }
    char buffer[16384];
    int ok=1;
    for (;;) {
        long count=syscall(SYS_read,input,buffer,sizeof(buffer));
        if (count==0) break;
        if (count<0) { ok=0; break; }
        long written=0;
        while (written<count) {
            long n=syscall(SYS_write,output,buffer+written,(size_t)(count-written));
            if (n<=0) { ok=0; break; }
            written+=n;
        }
        if (!ok) break;
    }
    if (syscall(SYS_fsync,output)) ok=0;
    if (syscall(SYS_close,output)) ok=0;
    if (syscall(SYS_close,input)) ok=0;
    return ok;
}
int tp_probe_maps_save(const char *directory,const char *stage) {
    return save(directory,stage,"maps","/proc/self/maps");
}
int tp_probe_smaps_save(const char *directory,const char *stage) {
    return save(directory,stage,"smaps","/proc/self/smaps");
}
