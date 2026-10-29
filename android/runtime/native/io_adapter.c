#define _GNU_SOURCE
#include "kernel_syscall.h"
#include "path_policy.h"
#include "io_adapter.h"
#include "syscall_namespace.h"
#include "user_memory.h"
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#define syscall tp_kernel_syscall
#define SAFE_NAME(value,buffer) char buffer[PATH_MAX];if(tp_user_string(value,buffer,sizeof(buffer))<0)return -1;value=buffer

/* Only pinned kernel-entry libc wrappers are replaced. Kernel ABI/errno and real
 * descriptors are retained; no fake FD values, JS filesystem calls or replay. */
static int inspect_leaf(struct tp_path *path,bool nofollow,bool missing_allowed){
    if(!path->owned)return 0;
    struct stat st;int result=(int)syscall(SYS_newfstatat,path->parent,path->leaf,&st,AT_SYMLINK_NOFOLLOW);
    if(result<0){if(errno==ENOENT&&missing_allowed&&!path->trailing_slash)return 0;return -1;}
    if(!nofollow&&S_ISLNK(st.st_mode)){errno=ELOOP;return -1;}
    if(path->trailing_slash&&!S_ISDIR(st.st_mode)){errno=ENOTDIR;return -1;}return 0;
}
static int openat_impl(int dirfd,const char *name,int flags,unsigned mode){
    SAFE_NAME(name,safe_name);
    enum tp_access access=((flags&O_ACCMODE)!=O_RDONLY||(flags&(O_CREAT|O_TRUNC|O_APPEND))||((flags&O_TMPFILE)==O_TMPFILE))?TP_WRITE:TP_READ;
    struct tp_path path;if(tp_resolve(dirfd,name,access,&path)<0){return -1;}int result=-1;
    /* Procfs maps is virtualized to prevent host path leaks and unbreak guest unpackers/loaders. */
    if(access==TP_READ&&tp_is_proc_maps(&path)){
        result=tp_open_virtual_maps();
    }else if(inspect_leaf(&path,false,(flags&O_CREAT)!=0)==0){
        result=(int)syscall(SYS_openat,path.parent,path.leaf,flags|(path.owned?O_NOFOLLOW:0)|(path.trailing_slash?O_DIRECTORY:0),mode);
    }

    tp_release(&path);return result;
}
static int fstatat_impl(int dirfd,const char *name,struct stat *out,int flags){
    SAFE_NAME(name,safe_name);
    if(name&&name[0]=='\0'&&(flags&AT_EMPTY_PATH))return (int)syscall(SYS_newfstatat,dirfd,name,out,flags);
    struct tp_path path;if(tp_resolve(dirfd,name,TP_METADATA,&path)<0){return -1;}int result=-1;
    result=(int)syscall(SYS_newfstatat,path.parent,path.leaf,out,flags|(path.owned?AT_SYMLINK_NOFOLLOW:0));
    if(result==0&&path.owned){
        if(!(flags&AT_SYMLINK_NOFOLLOW)&&S_ISLNK(out->st_mode)){errno=ELOOP;result=-1;}
        else if(path.trailing_slash&&!S_ISDIR(out->st_mode)){errno=ENOTDIR;result=-1;}
    }

    tp_release(&path);return result;
}
static int unlinkat_impl(int dirfd,const char *name,int flags){
    SAFE_NAME(name,safe_name);
    struct tp_path path;if(tp_resolve(dirfd,name,TP_WRITE,&path)<0)return -1;int result=-1;
    if(inspect_leaf(&path,true,false)==0)result=(int)syscall(SYS_unlinkat,path.parent,path.leaf,flags);tp_release(&path);return result;
}
static int mkdirat_impl(int dirfd,const char *name,unsigned mode){
    SAFE_NAME(name,safe_name);
    struct tp_path path;if(tp_resolve(dirfd,name,TP_WRITE,&path)<0)return -1;
    int result=(int)syscall(SYS_mkdirat,path.parent,path.leaf,mode);tp_release(&path);return result;
}
static int renameat2_impl(int oldfd,const char *oldname,int newfd,const char *newname,unsigned flags){
    SAFE_NAME(oldname,safe_old);SAFE_NAME(newname,safe_new);
    struct tp_path oldpath,newpath;if(tp_resolve(oldfd,oldname,TP_WRITE,&oldpath)<0)return -1;
    if(tp_resolve(newfd,newname,TP_WRITE,&newpath)<0){tp_release(&oldpath);return -1;}int result=-1;
    if(inspect_leaf(&oldpath,true,false)==0&&inspect_leaf(&newpath,true,true)==0)result=(int)syscall(SYS_renameat2,oldpath.parent,oldpath.leaf,newpath.parent,newpath.leaf,flags);
    tp_release(&oldpath);tp_release(&newpath);return result;
}
static int faccessat_impl(int dirfd,const char *name,int mode){
    SAFE_NAME(name,safe_name);
    struct tp_path path;if(tp_resolve(dirfd,name,(mode&W_OK)?TP_WRITE:TP_METADATA,&path)<0)return -1;int result=-1;
    if(inspect_leaf(&path,false,false)==0)result=(int)syscall(SYS_faccessat,path.parent,path.leaf,mode);tp_release(&path);return result;
}
static int fchmodat_impl(int dirfd,const char *name,unsigned mode){
    SAFE_NAME(name,safe_name);
    struct tp_path path;if(tp_resolve(dirfd,name,TP_WRITE,&path)<0)return -1;int result=-1;
    if(inspect_leaf(&path,false,false)==0)result=(int)syscall(SYS_fchmodat,path.parent,path.leaf,mode);
    tp_release(&path);return result;
}
static ssize_t readlinkat_impl(int dirfd,const char *name,char *out,size_t size){
    if(!size){errno=EINVAL;return -1;}SAFE_NAME(name,safe_name);
    struct tp_path path;if(tp_resolve(dirfd,name,TP_METADATA,&path)<0)return -1;ssize_t result=-1;
    char raw[PATH_MAX];
    if(inspect_leaf(&path,true,false)==0){
        ssize_t n=(ssize_t)syscall(SYS_readlinkat,path.parent,path.leaf,raw,sizeof(raw)-1);
        if(n>=0){char visible[PATH_MAX];size_t capacity=size<sizeof(visible)?size:sizeof(visible);result=tp_unresolve(raw,(size_t)n,visible,capacity);if(result>=0&&tp_user_write(out,visible,(size_t)result)<0)result=-1;}
    }
    tp_release(&path);return result;
}
static int chdir_impl(const char *name){
    int fd=openat_impl(AT_FDCWD,name,O_PATH|O_DIRECTORY|O_CLOEXEC,0);if(fd<0)return -1;
    int result=(int)syscall(SYS_fchdir,fd),saved=errno;syscall(SYS_close,fd);errno=saved;return result;
}
static int symlinkat_impl(const char *target,int dirfd,const char *name){
    SAFE_NAME(name,safe_name);
    (void)target;struct tp_path path;if(tp_resolve(dirfd,name,TP_WRITE,&path)<0)return -1;tp_release(&path);errno=EOPNOTSUPP;return -1;
}
static int linkat_impl(int oldfd,const char *oldname,int newfd,const char *newname,int flags){
    SAFE_NAME(newname,safe_new);
    (void)oldfd;(void)oldname;(void)flags;struct tp_path path;if(tp_resolve(newfd,newname,TP_WRITE,&path)<0)return -1;tp_release(&path);errno=EOPNOTSUPP;return -1;
}

/* The libc entry points can run on equally small native stacks, before a SVC is
 * reached. Keep caller frames small and share the same bounded execution stack. */
struct io_call {unsigned kind;long args[5];};
static long io_dispatch(long payload,long unused1,long unused2){
    (void)unused1;(void)unused2;const struct io_call *call=(const struct io_call *)payload;const long *a=call->args;
    long res=-1;
    switch(call->kind){
        case 0:res=openat_impl((int)a[0],(const char *)a[1],(int)a[2],(unsigned)a[3]);break;
        case 1:res=fstatat_impl((int)a[0],(const char *)a[1],(struct stat *)a[2],(int)a[3]);break;
        case 2:res=unlinkat_impl((int)a[0],(const char *)a[1],(int)a[2]);break;
        case 3:res=mkdirat_impl((int)a[0],(const char *)a[1],(unsigned)a[2]);break;
        case 4:res=renameat2_impl((int)a[0],(const char *)a[1],(int)a[2],(const char *)a[3],(unsigned)a[4]);break;
        case 5:res=faccessat_impl((int)a[0],(const char *)a[1],(int)a[2]);break;
        case 6:res=readlinkat_impl((int)a[0],(const char *)a[1],(char *)a[2],(size_t)a[3]);break;
        case 7:res=chdir_impl((const char *)a[0]);break;
        case 8:res=symlinkat_impl((const char *)a[0],(int)a[1],(const char *)a[2]);break;
        case 9:res=linkat_impl((int)a[0],(const char *)a[1],(int)a[2],(const char *)a[3],(int)a[4]);break;
        case 10:res=fchmodat_impl((int)a[0],(const char *)a[1],(unsigned)a[2]);break;
        default:errno=EINVAL;res=-1;break;
    }
    int saved=errno;
    int fd=tp_syscall_namespace_get_audit_fd();
    if(fd>=0){
        const char *raw_path=(const char *)((call->kind==7||call->kind==8)?a[0]:a[1]);
        char safe_path[256];safe_path[0]=0;
        if(raw_path){if(tp_user_string(raw_path,safe_path,sizeof(safe_path))<0)safe_path[sizeof(safe_path)-1]=0;}
        char extra[256];extra[0]=0;
        if(call->kind==6&&res>0&&a[2]){
            size_t copy_len=(size_t)res<sizeof(extra)-1?(size_t)res:sizeof(extra)-1;
            if(tp_user_read((const void *)a[2],extra,copy_len)==0)extra[copy_len]=0;
        }
        char line[600];
        int n=snprintf(line,sizeof(line),"io kind=%u pid=%ld tid=%ld res=%ld err=%d path=%s target=%s\n",
            call->kind,syscall(SYS_getpid),syscall(SYS_gettid),res,res<0?saved:0,safe_path,extra);
        if(n>0&&(size_t)n<sizeof(line))syscall(SYS_write,fd,line,(size_t)n);
    }
    errno=saved;
    return res;
}
#define CALL_IO(kind,...) struct io_call call={kind,{__VA_ARGS__}};return tp_native_stack_call((void *)io_dispatch,(long)&call,0,0)
int tp_openat(int dirfd,const char *name,int flags,unsigned mode){CALL_IO(0,dirfd,(long)name,flags,mode);}
int tp_fstatat(int dirfd,const char *name,struct stat *out,int flags){CALL_IO(1,dirfd,(long)name,(long)out,flags);}
int tp_unlinkat(int dirfd,const char *name,int flags){CALL_IO(2,dirfd,(long)name,flags);}
int tp_mkdirat(int dirfd,const char *name,unsigned mode){CALL_IO(3,dirfd,(long)name,mode);}
int tp_renameat2(int oldfd,const char *oldname,int newfd,const char *newname,unsigned flags){CALL_IO(4,oldfd,(long)oldname,newfd,(long)newname,flags);}
int tp_faccessat(int dirfd,const char *name,int mode){CALL_IO(5,dirfd,(long)name,mode);}
int tp_fchmodat(int dirfd,const char *name,unsigned mode){CALL_IO(10,dirfd,(long)name,mode);}
ssize_t tp_readlinkat(int dirfd,const char *name,char *out,size_t size){CALL_IO(6,dirfd,(long)name,(long)out,(long)size);}
int tp_chdir(const char *name){CALL_IO(7,(long)name);}
int tp_symlinkat(const char *target,int dirfd,const char *name){CALL_IO(8,(long)target,dirfd,(long)name);}
int tp_linkat(int oldfd,const char *oldname,int newfd,const char *newname,int flags){CALL_IO(9,oldfd,(long)oldname,newfd,(long)newname,flags);}
