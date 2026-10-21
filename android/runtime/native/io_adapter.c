#define _GNU_SOURCE
#include "path_policy.h"
#include "io_adapter.h"
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

static void native_log(const char *op, const char *path, int res) {
    if (path && (strstr(path, "base.apk") || strstr(path, "cloudmusic") || strstr(path, "tripoder") || strstr(path, "nesec") || strstr(path, "data/app"))) {
        void *liblog = dlopen("liblog.so", RTLD_NOW);
        if (liblog) {
            int (*log_print)(int, const char*, const char*, ...) = (int (*)(int, const char*, const char*, ...))dlsym(liblog, "__android_log_print");
            if (log_print) log_print(4, "TripoderNativeIO", "%s: %s -> %d (errno=%d)", op, path, res, errno);
        }
    }
}

/* Only pinned kernel-entry libc wrappers are replaced. Kernel ABI/errno and real
 * descriptors are retained; no fake FD values, JS filesystem calls or replay. */
static int inspect_leaf(struct tp_path *path,bool nofollow,bool missing_allowed){
    if(!path->owned)return 0;
    struct stat st;int result=(int)syscall(SYS_newfstatat,path->parent,path->leaf,&st,AT_SYMLINK_NOFOLLOW);
    if(result<0){if(errno==ENOENT&&missing_allowed&&!path->trailing_slash)return 0;return -1;}
    if(!nofollow&&S_ISLNK(st.st_mode)){errno=ELOOP;return -1;}
    if(path->trailing_slash&&!S_ISDIR(st.st_mode)){errno=ENOTDIR;return -1;}return 0;
}
int tp_openat(int dirfd,const char *name,int flags,unsigned mode){
    enum tp_access access=((flags&O_ACCMODE)!=O_RDONLY||(flags&(O_CREAT|O_TRUNC|O_APPEND))||((flags&O_TMPFILE)==O_TMPFILE))?TP_WRITE:TP_READ;
    struct tp_path path;if(tp_resolve(dirfd,name,access,&path)<0){native_log("tp_openat_fail_resolve", name, -1);return -1;}int result=-1;
    if(access==TP_READ&&!path.owned&&path.parent==AT_FDCWD&&!path.trailing_slash&&path.leaf[0]=='/'&&path.leaf[1]=='p'&&path.leaf[2]=='r'&&path.leaf[3]=='o'&&path.leaf[4]=='c'&&path.leaf[5]=='/'){
        result=tp_open_proc(path.leaf,flags);
        if(result>=0||errno!=ENOSYS){tp_release(&path);native_log("tp_openat_proc", name, result);return result;}
    }
    if(inspect_leaf(&path,false,(flags&O_CREAT)!=0)==0)result=(int)syscall(SYS_openat,path.parent,path.leaf,flags|(path.owned?O_NOFOLLOW:0)|(path.trailing_slash?O_DIRECTORY:0),mode);
    native_log("tp_openat", name, result);
    tp_release(&path);return result;
}
int tp_fstatat(int dirfd,const char *name,struct stat *out,int flags){
    if(name&&name[0]=='\0'&&(flags&AT_EMPTY_PATH))return (int)syscall(SYS_newfstatat,dirfd,name,out,flags);
    struct tp_path path;if(tp_resolve(dirfd,name,TP_METADATA,&path)<0){native_log("tp_fstatat_fail_resolve", name, -1);return -1;}int result=-1;
    result=(int)syscall(SYS_newfstatat,path.parent,path.leaf,out,flags|(path.owned?AT_SYMLINK_NOFOLLOW:0));
    if(result==0&&path.owned){
        if(!(flags&AT_SYMLINK_NOFOLLOW)&&S_ISLNK(out->st_mode)){errno=ELOOP;result=-1;}
        else if(path.trailing_slash&&!S_ISDIR(out->st_mode)){errno=ENOTDIR;result=-1;}
    }
    native_log("tp_fstatat", name, result);
    tp_release(&path);return result;
}
int tp_unlinkat(int dirfd,const char *name,int flags){
    struct tp_path path;if(tp_resolve(dirfd,name,TP_WRITE,&path)<0)return -1;int result=-1;
    if(inspect_leaf(&path,true,false)==0)result=(int)syscall(SYS_unlinkat,path.parent,path.leaf,flags);tp_release(&path);return result;
}
int tp_mkdirat(int dirfd,const char *name,unsigned mode){
    struct tp_path path;if(tp_resolve(dirfd,name,TP_WRITE,&path)<0)return -1;
    int result=(int)syscall(SYS_mkdirat,path.parent,path.leaf,mode);tp_release(&path);return result;
}
int tp_renameat2(int oldfd,const char *oldname,int newfd,const char *newname,unsigned flags){
    struct tp_path oldpath,newpath;if(tp_resolve(oldfd,oldname,TP_WRITE,&oldpath)<0)return -1;
    if(tp_resolve(newfd,newname,TP_WRITE,&newpath)<0){tp_release(&oldpath);return -1;}int result=-1;
    if(inspect_leaf(&oldpath,true,false)==0&&inspect_leaf(&newpath,true,true)==0)result=(int)syscall(SYS_renameat2,oldpath.parent,oldpath.leaf,newpath.parent,newpath.leaf,flags);
    tp_release(&oldpath);tp_release(&newpath);return result;
}
int tp_faccessat(int dirfd,const char *name,int mode){
    struct tp_path path;if(tp_resolve(dirfd,name,(mode&W_OK)?TP_WRITE:TP_METADATA,&path)<0)return -1;int result=-1;
    if(inspect_leaf(&path,false,false)==0)result=(int)syscall(SYS_faccessat,path.parent,path.leaf,mode);tp_release(&path);return result;
}
ssize_t tp_readlinkat(int dirfd,const char *name,char *out,size_t size){
    struct tp_path path;if(tp_resolve(dirfd,name,TP_METADATA,&path)<0)return -1;ssize_t result=-1;
    char raw[PATH_MAX];
    if(inspect_leaf(&path,true,false)==0){
        ssize_t n=(ssize_t)syscall(SYS_readlinkat,path.parent,path.leaf,raw,sizeof(raw)-1);
        if(n>=0)result=tp_unresolve(raw,(size_t)n,out,size);
    }
    tp_release(&path);return result;
}
int tp_chdir(const char *name){
    int fd=tp_openat(AT_FDCWD,name,O_PATH|O_DIRECTORY|O_CLOEXEC,0);if(fd<0)return -1;
    int result=(int)syscall(SYS_fchdir,fd),saved=errno;syscall(SYS_close,fd);errno=saved;return result;
}
int tp_symlinkat(const char *target,int dirfd,const char *name){
    (void)target;struct tp_path path;if(tp_resolve(dirfd,name,TP_WRITE,&path)<0)return -1;tp_release(&path);errno=EOPNOTSUPP;return -1;
}
int tp_linkat(int oldfd,const char *oldname,int newfd,const char *newname,int flags){
    (void)oldfd;(void)oldname;(void)flags;struct tp_path path;if(tp_resolve(newfd,newname,TP_WRITE,&path)<0)return -1;tp_release(&path);errno=EOPNOTSUPP;return -1;
}
