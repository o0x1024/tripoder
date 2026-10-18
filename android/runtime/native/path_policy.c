#define _GNU_SOURCE
#include "path_policy.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

/* Independent, process-immutable namespace policy. Direct internal syscalls avoid
 * recursively entering the intercepted libc wrappers. No privileged operations. */
static char data_paths[4][PATH_MAX], artifact_paths[3][PATH_MAX];
static char guest_process[256], guest_comm[16];
static int data_fd=-1, artifact_fd=-1;
static bool configured;

static bool prefix(const char *path,const char *root) {
    size_t n=strlen(root);return strncmp(path,root,n)==0&&(path[n]=='/'||path[n]=='\0');
}
static int fail(int value){errno=value;return -1;}
static int copy(char *out,const char *in){if(in==NULL)return fail(EFAULT);size_t n=strnlen(in,PATH_MAX);if(n>=PATH_MAX)return fail(ENAMETOOLONG);memcpy(out,in,n+1);return 0;}
static void set_comm(const char *name){
    size_t n=strlen(name);const char *tail=n>15?name+(n-15):name;
    size_t copy_len=n>15?15:n;memcpy(guest_comm,tail,copy_len);guest_comm[copy_len]='\0';
}
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
    snprintf(artifact_paths[2],PATH_MAX,"/data/app/~~tripoder/%s",package);
    snprintf(guest_process,sizeof(guest_process),"%s",package);set_comm(package);
    data_fd=(int)syscall(SYS_openat,AT_FDCWD,data,O_PATH|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW,0);if(data_fd<0)return -1;
    artifact_fd=(int)syscall(SYS_openat,AT_FDCWD,artifacts,O_PATH|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW,0);if(artifact_fd<0)return -1;
    // Relative guest paths start in the immutable data incarnation, not the host cwd.
    if(syscall(SYS_fchdir,data_fd)<0)return -1;configured=true;return 0;
}

int tp_configure_process(const char *process_name){
    if(!configured)return fail(ENOSYS);
    if(!process_name||!*process_name||strlen(process_name)>=sizeof(guest_process))return fail(EINVAL);
    snprintf(guest_process,sizeof(guest_process),"%s",process_name);set_comm(process_name);
    syscall(SYS_prctl,PR_SET_NAME,(unsigned long)guest_comm,0,0,0);
    return 0;
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
        for(int i=0;i<3;i++)if(prefix(path,artifact_paths[i])){if(access==TP_WRITE)return fail(EROFS);return anchored(artifact_fd,path+strlen(artifact_paths[i]),out);}
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

enum tp_proc_kind { TP_PROC_NONE, TP_PROC_CMDLINE, TP_PROC_COMM, TP_PROC_STATUS, TP_PROC_MAPS, TP_PROC_MOUNTS };
static enum tp_proc_kind classify_proc(const char *path){
    if(!strcmp(path,"/proc/mounts"))return TP_PROC_MOUNTS;
    if(strncmp(path,"/proc/",6)!=0)return TP_PROC_NONE;
    const char *rest=path+6;
    if(!strncmp(rest,"self/",5))rest+=5;
    else if(!strncmp(rest,"thread-self/",12))rest+=12;
    else{
        char *end=NULL;unsigned long pid=strtoul(rest,&end,10);
        if(end==rest||*end!='/'||pid!=(unsigned long)syscall(SYS_getpid))return TP_PROC_NONE;
        rest=end+1;
    }
    if(!strncmp(rest,"task/",5)){
        rest+=5;while(*rest>='0'&&*rest<='9')rest++;
        if(*rest!='/')return TP_PROC_NONE;
        rest++;
    }
    if(!strcmp(rest,"cmdline"))return TP_PROC_CMDLINE;
    if(!strcmp(rest,"comm"))return TP_PROC_COMM;
    if(!strcmp(rest,"status"))return TP_PROC_STATUS;
    if(!strcmp(rest,"maps")||!strcmp(rest,"smaps"))return TP_PROC_MAPS;
    if(!strcmp(rest,"mountinfo")||!strcmp(rest,"mounts"))return TP_PROC_MOUNTS;
    return TP_PROC_NONE;
}
static bool write_all(int fd,const char *buf,size_t len){
    size_t off=0;
    while(off<len){
        ssize_t n=(ssize_t)syscall(SYS_write,fd,buf+off,len-off);
        if(n<=0)return false;
        off+=(size_t)n;
    }
    return true;
}
static bool write_replaced(int fd,const char *line,size_t len,const char *match,const char *replacement){
    const char *pos=strstr(line,match);if(!pos)return write_all(fd,line,len);
    size_t prefix_len=(size_t)(pos-line),match_len=strlen(match),rep_len=strlen(replacement);
    return write_all(fd,line,prefix_len)&&write_all(fd,replacement,rep_len)&&write_all(fd,pos+match_len,len-prefix_len-match_len);
}
static bool is_vma_header(const char *line,size_t len){
    size_t i=0;
    while(i<len&&((line[i]>='0'&&line[i]<='9')||(line[i]>='a'&&line[i]<='f')))i++;
    if(i==0||i>=len||line[i]!='-')return false;
    size_t j=++i;
    while(i<len&&((line[i]>='0'&&line[i]<='9')||(line[i]>='a'&&line[i]<='f')))i++;
    return i>j&&i<len&&line[i]==' ';
}
static bool transform_proc_line(enum tp_proc_kind kind,const char *line,size_t len,bool *skip_vma,int out_fd){
    if(kind==TP_PROC_STATUS){
        if(len>=6&&!memcmp(line,"Name:\t",6)){
            char buf[32];int n=snprintf(buf,sizeof(buf),"Name:\t%s\n",guest_comm);
            return n>0&&write_all(out_fd,buf,(size_t)n);
        }
        if(len>=11&&!memcmp(line,"TracerPid:\t",11))return write_all(out_fd,"TracerPid:\t0\n",13);
        return write_all(out_fd,line,len);
    }
    if(kind==TP_PROC_MAPS){
        if(is_vma_header(line,len)){
            for(int i=0;i<2;i++)if(strstr(line,artifact_paths[i])){*skip_vma=false;return write_replaced(out_fd,line,len,artifact_paths[i],artifact_paths[2]);}
            for(int i=0;i<2;i++)if(strstr(line,data_paths[i])){*skip_vma=false;return write_replaced(out_fd,line,len,data_paths[i],data_paths[3]);}
            const char *dex=strstr(line,"/memfd:tripoder-dex");
            if(dex){*skip_vma=false;return write_all(out_fd,line,(size_t)(dex-line))&&write_all(out_fd,"[anon:dalvik-classes.dex]\n",26);}
            if(strstr(line,"libtripoderselfhook.so")||strstr(line,"libtripoderio.so")||strstr(line,"frida")||strstr(line,"gadget")||strstr(line,"dev.tripoder.engine")||strstr(line,"dev.tripoder.runtimeprobe")){*skip_vma=true;return true;}
            *skip_vma=false;return write_all(out_fd,line,len);
        }
        return *skip_vma?true:write_all(out_fd,line,len);
    }
    if(kind==TP_PROC_MOUNTS){
        for(int i=0;i<2;i++)if(strstr(line,artifact_paths[i]))return write_replaced(out_fd,line,len,artifact_paths[i],artifact_paths[2]);
        for(int i=0;i<2;i++)if(strstr(line,data_paths[i]))return write_replaced(out_fd,line,len,data_paths[i],data_paths[3]);
        if(strstr(line,"dev.tripoder.engine")||strstr(line,"dev.tripoder.runtimeprobe"))return true;
        return write_all(out_fd,line,len);
    }
    return write_all(out_fd,line,len);
}
int tp_open_proc(const char *path,int flags){
    if(!configured)return fail(ENOSYS);
    enum tp_proc_kind kind=classify_proc(path);
    if(kind==TP_PROC_NONE)return fail(ENOSYS);
    int mfd=(int)syscall(SYS_memfd_create,"procfs",MFD_CLOEXEC);
    if(mfd<0)return -1;
    bool ok=false;int saved=EIO;
    if(kind==TP_PROC_CMDLINE){
        ok=write_all(mfd,guest_process,strlen(guest_process)+1);
    }else if(kind==TP_PROC_COMM){
        char buf[24];int n=snprintf(buf,sizeof(buf),"%s\n",guest_comm);
        ok=n>0&&write_all(mfd,buf,(size_t)n);
    }else{
        int raw=(int)syscall(SYS_openat,AT_FDCWD,path,O_RDONLY|O_CLOEXEC,0);
        if(raw<0){saved=errno;syscall(SYS_close,mfd);return fail(saved);}
        char chunk[8192],line[4096];size_t line_len=0;bool skip_vma=false;ok=true;
        for(;;){
            ssize_t n=(ssize_t)syscall(SYS_read,raw,chunk,sizeof(chunk));
            if(n==0)break;
            if(n<0){ok=false;saved=errno;break;}
            for(ssize_t i=0;i<n;i++){
                if(line_len<sizeof(line)-1)line[line_len++]=chunk[i];
                if(chunk[i]=='\n'){
                    line[line_len]='\0';
                    if(!transform_proc_line(kind,line,line_len,&skip_vma,mfd)){ok=false;saved=errno;break;}
                    line_len=0;
                }
            }
            if(!ok)break;
        }
        if(ok&&line_len>0){
            line[line_len]='\0';
            if(!transform_proc_line(kind,line,line_len,&skip_vma,mfd)){ok=false;saved=errno;}
        }
        syscall(SYS_close,raw);
    }
    if(!ok||syscall(SYS_lseek,mfd,0,SEEK_SET)<0){syscall(SYS_close,mfd);return fail(saved);}
    if(!(flags&O_CLOEXEC))syscall(SYS_fcntl,mfd,F_SETFD,0);
    return mfd;
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
                const char *rel=raw+strlen(artifact_paths[i]);size_t rlen=strlen(rel);
                if(rlen>4&&!strcmp(rel+rlen-4,".apk")&&rel[0]=='/'&&!strchr(rel+1,'/')){
                    char rewritten[PATH_MAX];
                    int n=snprintf(rewritten,sizeof(rewritten),"%s%s",artifact_paths[2],rel);
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
