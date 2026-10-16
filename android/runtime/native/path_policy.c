#define _GNU_SOURCE
#include "path_policy.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

/* Independent, process-immutable namespace policy. Direct internal syscalls avoid
 * recursively entering the intercepted libc wrappers. No privileged operations. */
static char data_paths[4][PATH_MAX], artifact_paths[2][PATH_MAX];
static int data_fd=-1, artifact_fd=-1;
static bool configured;

static bool prefix(const char *path,const char *root) {
    size_t n=strlen(root);return strncmp(path,root,n)==0&&(path[n]=='/'||path[n]=='\0');
}
static int fail(int value){errno=value;return -1;}
static int copy(char *out,const char *in){if(in==NULL)return fail(EFAULT);size_t n=strnlen(in,PATH_MAX);if(n>=PATH_MAX)return fail(ENAMETOOLONG);memcpy(out,in,n+1);return 0;}
static int fd_path(int fd,char *out){
    if(fd==AT_FDCWD){long n=syscall(SYS_getcwd,out,PATH_MAX);return n<0?-1:0;}
    char name[64];int length=snprintf(name,sizeof(name),"/proc/self/fd/%d",fd);if(length<=0||(size_t)length>=sizeof(name))return fail(EBADF);
    long n=syscall(SYS_readlinkat,AT_FDCWD,name,out,PATH_MAX-1);if(n<0)return -1;out[n]='\0';return 0;
}
static int duplicate(int fd){return (int)syscall(SYS_fcntl,fd,F_DUPFD_CLOEXEC,0);}

int tp_configure(const char *data,const char *data_canonical,const char *artifacts,const char *artifacts_canonical,const char *package){
    if(configured||data_fd>=0||artifact_fd>=0)return fail(EALREADY);
    if(!package||!*package||strchr(package,'/')||strlen(package)>200)return fail(EINVAL);
    if(copy(data_paths[0],data)<0||copy(data_paths[1],data_canonical)<0||copy(artifact_paths[0],artifacts)<0||copy(artifact_paths[1],artifacts_canonical)<0)return -1;
    for(int i=0;i<2;i++)if(data_paths[i][0]!='/'||artifact_paths[i][0]!='/')return fail(EINVAL);
    snprintf(data_paths[2],PATH_MAX,"/data/data/%s",package);snprintf(data_paths[3],PATH_MAX,"/data/user/0/%s",package);
    data_fd=(int)syscall(SYS_openat,AT_FDCWD,data,O_PATH|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW,0);if(data_fd<0)return -1;
    artifact_fd=(int)syscall(SYS_openat,AT_FDCWD,artifacts,O_PATH|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW,0);if(artifact_fd<0)return -1;
    // Relative guest paths start in the immutable data incarnation, not the host cwd.
    if(syscall(SYS_fchdir,data_fd)<0)return -1;configured=true;return 0;
}

/* Walk every parent before processing '..': a missing or symlinked component
 * must not disappear through lexical normalization. Hold real directory FDs. */
static int anchored(int anchor,const char *relative,struct tp_path *out){
    int fds[256],depth=0;fds[0]=duplicate(anchor);if(fds[0]<0)return -1;
    const char *at=relative;char part[NAME_MAX+1];int saved;
    for(;;){
        while(*at=='/')at++;const char *end=at;while(*end&&*end!='/')end++;size_t count=(size_t)(end-at);
        if(count>NAME_MAX){saved=ENAMETOOLONG;goto error;}
        memcpy(part,at,count);part[count]='\0';const char *next=end;while(*next=='/')next++;bool final=*next=='\0';
        if(!count||!strcmp(part,".")){if(final){strcpy(out->leaf,".");break;}at=next;continue;}
        if(!strcmp(part,"..")){
            if(depth==0){saved=EACCES;goto error;}syscall(SYS_close,fds[depth--]);
            if(final){strcpy(out->leaf,".");break;}at=next;continue;
        }
        if(final){strcpy(out->leaf,part);out->trailing_slash=*end=='/';break;}
        if(depth==255){saved=ENAMETOOLONG;goto error;}
        int child=(int)syscall(SYS_openat,fds[depth],part,O_PATH|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW,0);
        if(child<0){saved=errno;goto error;}fds[++depth]=child;at=next;
    }
    out->parent=fds[depth];out->close_parent=true;out->owned=true;
    while(depth>0)syscall(SYS_close,fds[--depth]);return 0;
error:
    for(int i=0;i<=depth;i++)syscall(SYS_close,fds[i]);return fail(saved);
}
static bool platform_read(const char *path){
    const char *roots[]={"/system","/system_ext","/apex","/vendor","/product","/odm","/proc","/sys","/data/app","/dev/__properties__"};
    for(size_t i=0;i<sizeof(roots)/sizeof(roots[0]);i++)if(prefix(path,roots[i]))return true;return false;
}
static bool device_node(const char *path){
    // A real accelerated Android window needs the GPU character device. This is
    // an explicit platform capability; Android UID/SELinux checks still apply.
    // Never grant the /dev subtree or substitute a software renderer on denial.
    return !strcmp(path,"/dev/null")||!strcmp(path,"/dev/zero")||!strcmp(path,"/dev/urandom")||!strcmp(path,"/dev/random")||!strcmp(path,"/dev/kgsl-3d0");
}
static bool metadata_ancestor(const char *path){
    if(!strcmp(path,"/"))return true;
    for(int i=0;i<2;i++)if(prefix(data_paths[i],path)||prefix(artifact_paths[i],path))return true;return false;
}
static bool dot_component(const char *path){
    const char *p=path;while(*p){while(*p=='/')p++;const char *end=p;while(*end&&*end!='/')end++;size_t n=(size_t)(end-p);if((n==1&&p[0]=='.')||(n==2&&p[0]=='.'&&p[1]=='.'))return true;p=end;}return false;
}
int tp_resolve(int dirfd,const char *path,enum tp_access access,struct tp_path *out){
    memset(out,0,sizeof(*out));out->parent=AT_FDCWD;
    if(!configured)return fail(ENOSYS);if(!path)return fail(EFAULT);size_t size=strnlen(path,PATH_MAX);if(size>=PATH_MAX)return fail(ENAMETOOLONG);if(size==0)return fail(ENOENT);
    if(path[0]=='/'){
        for(int i=0;i<4;i++)if(prefix(path,data_paths[i]))return anchored(data_fd,path+strlen(data_paths[i]),out);
        for(int i=0;i<2;i++)if(prefix(path,artifact_paths[i])){if(access==TP_WRITE)return fail(EROFS);return anchored(artifact_fd,path+strlen(artifact_paths[i]),out);}
        // Platform paths are explicit read-only capabilities, not a general host passthrough.
        if(dot_component(path))return fail(EACCES);
        // /proc is also a namespace of magic links. Content opens through a process
        // root/cwd/fd alias require the later descriptor-capability layer, not this grant.
        if(access==TP_READ&&prefix(path,"/proc"))for(const char *p=path;*p;p++)if(*p=='/'&&(prefix(p,"/root")||prefix(p,"/cwd")||prefix(p,"/fd")))return fail(EACCES);
        if(device_node(path)||(access!=TP_WRITE&&platform_read(path))||(access==TP_METADATA&&metadata_ancestor(path))){if(copy(out->leaf,path)<0)return -1;return 0;}
        return fail(EACCES);
    }
    char directory[PATH_MAX];if(fd_path(dirfd,directory)<0)return -1;
    for(int i=0;i<2;i++)if(prefix(directory,data_paths[i])){
        int anchor=dirfd==AT_FDCWD?(int)syscall(SYS_openat,AT_FDCWD,".",O_PATH|O_DIRECTORY|O_CLOEXEC,0):dirfd;if(anchor<0)return -1;
        int result=anchored(anchor,path,out),saved=errno;if(dirfd==AT_FDCWD)syscall(SYS_close,anchor);errno=saved;return result;
    }
    for(int i=0;i<2;i++)if(prefix(directory,artifact_paths[i])){
        if(access==TP_WRITE)return fail(EROFS);if(dirfd==AT_FDCWD)return fail(EACCES);return anchored(dirfd,path,out);
    }
    if(access==TP_WRITE||dot_component(path)||!platform_read(directory))return fail(EACCES);
    out->parent=dirfd;return copy(out->leaf,path);
}
void tp_release(struct tp_path *path){int saved=errno;if(path->close_parent)syscall(SYS_close,path->parent);errno=saved;}
