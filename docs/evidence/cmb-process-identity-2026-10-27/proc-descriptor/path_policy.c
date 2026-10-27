#define _GNU_SOURCE
#include "kernel_syscall.h"
#include "path_policy.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>
#define syscall tp_kernel_syscall

/* Independent, process-immutable namespace policy. Direct internal syscalls avoid
 * recursively entering the intercepted libc wrappers. No privileged operations. */
static char data_paths[4][PATH_MAX], artifact_paths[4][PATH_MAX];
static size_t data_path_lens[4], artifact_path_lens[4];
static char host_storage_roots[3][PATH_MAX];
static bool host_storage_configured;
static int data_fd=-1, artifact_fd=-1;
static pid_t configured_pid=-1;
static bool configured;
static bool owned_proc_pid(unsigned long pid){
    return pid==(unsigned long)syscall(SYS_getpid)||(configured_pid>0&&pid==(unsigned long)configured_pid);
}

static bool prefix_len(const char *path,const char *root,size_t n) {
    return strncmp(path,root,n)==0&&(path[n]=='/'||path[n]=='\0');
}
static bool prefix(const char *path,const char *root) {
    return prefix_len(path,root,strlen(root));
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
    for(int i=0;i<2;i++)if(data_paths[i][0]!='/'||artifact_paths[i][0]!='/'
        ||!(data_path_lens[i]=strlen(data_paths[i]))||!(artifact_path_lens[i]=strlen(artifact_paths[i])))return fail(EINVAL);
    snprintf(data_paths[2],PATH_MAX,"/data/data/%s",package);snprintf(data_paths[3],PATH_MAX,"/data/user/0/%s",package);
    const char *incarnation=strrchr(artifacts_canonical,'/');incarnation=incarnation?incarnation+1:artifacts_canonical;
    if(!*incarnation||strlen(incarnation)>80)return fail(EINVAL);
    if(snprintf(artifact_paths[2],PATH_MAX,"/data/app/~~%s/%s-%s",incarnation,package,incarnation)>=PATH_MAX)return fail(ENAMETOOLONG);
    snprintf(artifact_paths[3],PATH_MAX,"/data/app/~~tripoder/%s",package);
    data_path_lens[2]=strlen(data_paths[2]);data_path_lens[3]=strlen(data_paths[3]);
    artifact_path_lens[2]=strlen(artifact_paths[2]);artifact_path_lens[3]=strlen(artifact_paths[3]);
    data_fd=(int)syscall(SYS_openat,AT_FDCWD,data,O_PATH|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW,0);if(data_fd<0)return -1;
    artifact_fd=(int)syscall(SYS_openat,AT_FDCWD,artifacts,O_PATH|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW,0);if(artifact_fd<0)return -1;
    // Relative guest paths start in the immutable data incarnation, not the host cwd.
    if(syscall(SYS_fchdir,data_fd)<0)return -1;configured_pid=(pid_t)syscall(SYS_getpid);configured=true;return 0;
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
    const char *roots[]={"/system","/system_ext","/apex","/vendor","/product","/odm","/proc","/sys","/data/app","/dev/__properties__","/etc"};
    for(size_t i=0;i<sizeof(roots)/sizeof(roots[0]);i++)if(prefix(path,roots[i]))return true;return false;
}
static bool is_proc_self_fd_link(const char *path){
    if(strncmp(path,"/proc/",6)!=0)return false;
    const char *rest=path+6;
    if(!strncmp(rest,"self/fd/",8))rest+=8;
    else if(!strncmp(rest,"thread-self/fd/",15))rest+=15;
    else{
        char *end=NULL;unsigned long pid=strtoul(rest,&end,10);
        if(end==rest||strncmp(end,"/fd/",4)!=0||!owned_proc_pid(pid))return false;
        rest=end+4;
    }
    if(*rest<'0'||*rest>'9')return false;
    while(*rest>='0'&&*rest<='9')rest++;
    return *rest=='\0';
}
static bool is_ashmem_node(const char *path){
    if(strncmp(path,"/dev/ashmem",11)!=0)return false;
    for(const char *p=path+11;*p;p++){
        char c=*p;
        if(!((c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='-'||c=='_'))return false;
    }
    return true;
}
static bool dma_heap_node(const char *path){
    static const char root[]="/dev/dma_heap/";
    if(strncmp(path,root,sizeof(root)-1)!=0)return false;
    const char *name=path+sizeof(root)-1;if(!*name||!strcmp(name,".")||!strcmp(name,".."))return false;
    for(const char *p=name;*p;p++)if(!((*p>='a'&&*p<='z')||(*p>='A'&&*p<='Z')||(*p>='0'&&*p<='9')||*p=='_'||*p=='-'||*p=='.'))return false;
    return true;
}
static bool device_node(const char *path,enum tp_access access){
    // MediaCodec discovers the kernel DMA heaps through this read-only directory.
    // The actual node open and every ioctl retain Android UID/SELinux enforcement.
    if(!strcmp(path,"/dev/dma_heap"))return access!=TP_WRITE;

    // A real accelerated Android window needs the GPU character device. This is
    // an explicit platform capability; Android UID/SELinux checks still apply.
    // Never grant the /dev subtree or substitute a software renderer on denial.
    return !strcmp(path,"/dev/null")||!strcmp(path,"/dev/zero")||!strcmp(path,"/dev/urandom")||!strcmp(path,"/dev/random")||!strcmp(path,"/dev/kgsl-3d0")||dma_heap_node(path)||is_ashmem_node(path);
}
static bool metadata_ancestor(const char *path){
    if(!strcmp(path,"/"))return true;
    for(int i=0;i<2;i++)if(prefix(data_paths[i],path)||prefix(artifact_paths[i],path))return true;return false;
}
static bool dot_component(const char *path){
    const char *p=path;while(*p){while(*p=='/')p++;const char *end=p;while(*end&&*end!='/')end++;size_t n=(size_t)(end-p);if((n==1&&p[0]=='.')||(n==2&&p[0]=='.'&&p[1]=='.'))return true;p=end;}return false;
}
int tp_configure_host_storage(const char *data,const char *canonical,const char *device){
    if(!configured||host_storage_configured)return fail(EALREADY);
    const char *roots[]={data,canonical,device};for(unsigned i=0;i<3;i++)if(!roots[i]||roots[i][0]!='/'||dot_component(roots[i])||copy(host_storage_roots[i],roots[i])<0)return fail(EINVAL);
    host_storage_configured=true;return 0;
}
static bool host_runtime_subpath(const char *path,const char **out_rel){
    if(!host_storage_configured)return false;
    for(unsigned i=0;i<3;i++){
        size_t length=strlen(host_storage_roots[i]);if(!prefix_len(path,host_storage_roots[i],length))continue;
        const char *sub=path+length;if(*sub=='/')sub++;
        bool webview=!strncmp(sub,"app_webview",11)&&(sub[11]==0||sub[11]=='/'||sub[11]=='_');
        if(webview||prefix(sub,"shared_prefs")||prefix(sub,"cache")||prefix(sub,"code_cache")||prefix(sub,"oat")){*out_rel=path+length;return true;}
    }return false;
}
static int normalize_rel(const char *rel,char *out,size_t out_size){
    char *stack[128];
    int depth=0;
    char buf[PATH_MAX];
    if(strlen(rel)>=sizeof(buf))return fail(ENAMETOOLONG);
    strcpy(buf,rel);
    char *saveptr=NULL;
    char *token=strtok_r(buf,"/",&saveptr);
    while(token){
        if(!strcmp(token,".")){}
        else if(!strcmp(token,"..")){
            if(depth>0)depth--;
        }else{
            if(depth>=128)return fail(ENAMETOOLONG);
            stack[depth++]=token;
        }
        token=strtok_r(NULL,"/",&saveptr);
    }
    size_t pos=0;
    if(depth==0){
        if(out_size<2)return fail(ENAMETOOLONG);
        out[0]='/';out[1]='\0';return 0;
    }
    for(int i=0;i<depth;i++){
        size_t len=strlen(stack[i]);
        if(pos+1+len+1>out_size)return fail(ENAMETOOLONG);
        out[pos++]='/';
        memcpy(out+pos,stack[i],len);
        pos+=len;
    }
    out[pos]='\0';
    return 0;
}
static int fast_anchor(int anchor,const char *relative,struct tp_path *out){
    while(*relative=='/')relative++;
    if(!*relative){
        out->parent=anchor;strcpy(out->leaf,".");out->close_parent=false;out->owned=true;out->trailing_slash=false;return 0;
    }
    size_t len=strlen(relative);
    if(len>=sizeof(out->leaf))return fail(ENAMETOOLONG);
    bool trailing=(relative[len-1]=='/');
    memcpy(out->leaf,relative,len+1);
    if(trailing&&len>1){
        while(len>0&&out->leaf[len-1]=='/'){out->leaf[--len]='\0';}
    }
    out->parent=anchor;out->close_parent=false;out->owned=true;out->trailing_slash=trailing;return 0;
}
int tp_resolve(int dirfd,const char *path,enum tp_access access,struct tp_path *out){
    memset(out,0,sizeof(*out));out->parent=AT_FDCWD;
    if(!configured)return fail(ENOSYS);if(!path)return fail(EFAULT);size_t size=strnlen(path,PATH_MAX);if(size>=PATH_MAX)return fail(ENAMETOOLONG);if(size==0)return fail(ENOENT);
    if(path[0]=='/'){
        for(int i=0;i<4;i++)if(prefix_len(path,data_paths[i],data_path_lens[i])){
            const char *rel=path+data_path_lens[i];
            if(!dot_component(rel))return fast_anchor(data_fd,rel,out);
            return anchored(data_fd,rel,out);
        }
        const char *host_rel = NULL;
        if(host_runtime_subpath(path, &host_rel)){
            if(!dot_component(host_rel))return fast_anchor(data_fd,host_rel,out);
            return anchored(data_fd,host_rel,out);
        }
        for(int i=0;i<4;i++)if(prefix_len(path,artifact_paths[i],artifact_path_lens[i])){
            if(access==TP_WRITE)return fail(EROFS);
            const char *raw_rel=path+artifact_path_lens[i];
            char rel[PATH_MAX];
            if(dot_component(raw_rel)){
                if(normalize_rel(raw_rel,rel,sizeof(rel))<0)return -1;
            }else{
                if(copy(rel,raw_rel)<0)return -1;
            }
            if(prefix_len(rel,"/lib/arm64",10)){
                char alias[PATH_MAX];
                int n=snprintf(alias,sizeof(alias),"/lib%s",rel+10);
                if(n<=0||(size_t)n>=sizeof(alias))return fail(ENAMETOOLONG);
                return fast_anchor(artifact_fd,alias,out);
            }
            if(prefix_len(rel,"/lib/arm64-v8a",14)){
                char alias[PATH_MAX];
                int n=snprintf(alias,sizeof(alias),"/lib%s",rel+14);
                if(n<=0||(size_t)n>=sizeof(alias))return fail(ENAMETOOLONG);
                return fast_anchor(artifact_fd,alias,out);
            }
            return fast_anchor(artifact_fd,rel,out);
        }
        // Platform paths are explicit read-only capabilities, not a general host passthrough.
        if(dot_component(path))return fail(EACCES);
        if(access!=TP_METADATA&&is_proc_self_fd_link(path)){
            char target[PATH_MAX];
            long n=syscall(SYS_readlinkat,AT_FDCWD,path,target,sizeof(target)-1);
            if(n<=0||target[0]!='/'||is_proc_self_fd_link(target))return fail(EACCES);
            target[n]='\0';
            if((strstr(target,"dev.tripoder.engine")||strstr(target,"dev.tripoder.runtimeprobe"))
                &&!prefix(target,data_paths[0])&&!prefix(target,data_paths[1])
                &&!prefix(target,artifact_paths[0])&&!prefix(target,artifact_paths[1]))return fail(EACCES);
            return tp_resolve(AT_FDCWD,target,access,out);
        }
        // /proc is also a namespace of magic links. Content opens through a process
        // root/cwd/fd alias require the descriptor-capability check above, not this grant.
        if(access==TP_READ&&prefix_len(path,"/proc",5))for(const char *p=path;*p;p++)if(*p=='/'&&(prefix_len(p,"/root",5)||prefix_len(p,"/cwd",4)||prefix_len(p,"/fd",3)))return fail(EACCES);
        if(device_node(path,access)||(access!=TP_WRITE&&platform_read(path))||(access==TP_METADATA&&metadata_ancestor(path))){if(copy(out->leaf,path)<0)return -1;return 0;}
        return fail(EACCES);
    }
    if(dirfd==AT_FDCWD&&!dot_component(path))return fast_anchor(data_fd,path,out);
    char directory[PATH_MAX];if(fd_path(dirfd,directory)<0)return -1;
    for(int i=0;i<2;i++)if(prefix_len(directory,data_paths[i],data_path_lens[i])){
        if(dirfd!=AT_FDCWD&&!dot_component(path))return fast_anchor(dirfd,path,out);
        const char *rel_dir=directory+data_path_lens[i];
        char combined[PATH_MAX];
        int n=snprintf(combined,sizeof(combined),"%s/%s",rel_dir,path);
        if(n<=0||(size_t)n>=sizeof(combined))return fail(ENAMETOOLONG);
        char norm[PATH_MAX];
        if(normalize_rel(combined,norm,sizeof(norm))<0)return -1;
        return fast_anchor(data_fd,norm,out);
    }
    for(int i=0;i<2;i++)if(prefix_len(directory,artifact_paths[i],artifact_path_lens[i])){
        if(access==TP_WRITE)return fail(EROFS);if(dirfd==AT_FDCWD)return fail(EACCES);
        if(!dot_component(path))return fast_anchor(dirfd,path,out);
        const char *rel_dir=directory+artifact_path_lens[i];
        char combined[PATH_MAX];
        int n=snprintf(combined,sizeof(combined),"%s/%s",rel_dir,path);
        if(n<=0||(size_t)n>=sizeof(combined))return fail(ENAMETOOLONG);
        char norm[PATH_MAX];
        if(normalize_rel(combined,norm,sizeof(norm))<0)return -1;
        if(prefix_len(norm,"/lib/arm64",10)){
            char alias[PATH_MAX];
            int an=snprintf(alias,sizeof(alias),"/lib%s",norm+10);
            if(an<=0||(size_t)an>=sizeof(alias))return fail(ENAMETOOLONG);
            return fast_anchor(artifact_fd,alias,out);
        }
        if(prefix_len(norm,"/lib/arm64-v8a",14)){
            char alias[PATH_MAX];
            int an=snprintf(alias,sizeof(alias),"/lib%s",norm+14);
            if(an<=0||(size_t)an>=sizeof(alias))return fail(ENAMETOOLONG);
            return fast_anchor(artifact_fd,alias,out);
        }
        return fast_anchor(artifact_fd,norm,out);
    }
    if(!strcmp(directory,"/dev/dma_heap")){
        char combined[PATH_MAX];int n=snprintf(combined,sizeof(combined),"%s/%s",directory,path);
        if(n<=0||(size_t)n>=sizeof(combined))return fail(ENAMETOOLONG);
        return tp_resolve(AT_FDCWD,combined,access,out);
    }
    if(access==TP_WRITE||dot_component(path)||!platform_read(directory))return fail(EACCES);
    if(prefix(directory,"/proc")){
        char combined[PATH_MAX];
        int n=snprintf(combined,sizeof(combined),"%s/%s",directory,path);
        if(n<=0||(size_t)n>=sizeof(combined))return fail(ENAMETOOLONG);
        out->parent=AT_FDCWD;return copy(out->leaf,combined);
    }
    out->parent=dirfd;return copy(out->leaf,path);
}

ssize_t tp_unresolve(const char *physical,size_t physical_len,char *out,size_t size){
    if(!physical||(size>0&&!out))return fail(EFAULT);
    if(configured&&physical_len>0&&physical[0]=='/'){
        char raw[PATH_MAX];
        if(physical_len<sizeof(raw)){
            memcpy(raw,physical,physical_len);raw[physical_len]='\0';
            for(int i=0;i<2;i++)if(prefix(raw,data_paths[i])){
                char rewritten[PATH_MAX];
                int n=snprintf(rewritten,sizeof(rewritten),"%s%s",data_paths[3],raw+strlen(data_paths[i]));
                if(n>0&&(size_t)n<sizeof(rewritten)){
                    size_t copied=(size_t)n<size?(size_t)n:size;
                    if(copied>0)memcpy(out,rewritten,copied);
                    return (ssize_t)copied;
                }
            }
            for(int i=0;i<2;i++)if(prefix(raw,artifact_paths[i])){
                const char *rel=raw+strlen(artifact_paths[i]);
                if(rel[0]=='/'||rel[0]=='\0'){
                    char rewritten[PATH_MAX];
                    int n=prefix(rel,"/lib")?snprintf(rewritten,sizeof(rewritten),"%s/lib/arm64%s",artifact_paths[2],rel+4):snprintf(rewritten,sizeof(rewritten),"%s%s",artifact_paths[2],rel);
                    if(n>0&&(size_t)n<sizeof(rewritten)){
                        size_t copied=(size_t)n<size?(size_t)n:size;
                        if(copied>0)memcpy(out,rewritten,copied);
                        return (ssize_t)copied;
                    }
                }
            }
        }
    }
    size_t copied=physical_len<size?physical_len:size;
    if(copied>0)memcpy(out,physical,copied);
    return (ssize_t)copied;
}
void tp_release(struct tp_path *path){int saved=errno;if(path->close_parent)syscall(SYS_close,path->parent);errno=saved;}
