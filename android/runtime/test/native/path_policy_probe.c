#define _GNU_SOURCE
#include "path_policy.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

int tp_openat(int,const char *,int,unsigned);
int tp_fstatat(int,const char *,struct stat *,int);
int tp_unlinkat(int,const char *,int);
int tp_renameat2(int,const char *,int,const char *,unsigned);
int tp_symlinkat(const char *,int,const char *);
ssize_t tp_readlinkat(int,const char *,char *,size_t);
static int checks,failures;
static void check(const char *name,int okay){printf("{\"name\":\"%s\",\"passed\":%s,\"errno\":%d}\n",name,okay?"true":"false",errno);fflush(stdout);checks++;if(!okay)failures++;}
static void setup(int okay){if(!okay){perror("setup");exit(2);}}
static void write_raw(const char *path,const char *value){int fd=open(path,O_WRONLY|O_CREAT|O_EXCL,0600);setup(fd>=0);setup(write(fd,value,strlen(value))==(ssize_t)strlen(value));setup(close(fd)==0);}
int main(int argc,char **argv){
    if(argc!=2||strncmp(argv[1],"/data/local/tmp/tripoder-path-",28)!=0)return 2;
    char root[PATH_MAX],data[PATH_MAX],artifacts[PATH_MAX],files[PATH_MAX],file[PATH_MAX],link[PATH_MAX];
    setup(snprintf(root,sizeof(root),"%s",argv[1])<(int)sizeof(root));setup(mkdir(root,0700)==0);
    setup(snprintf(data,sizeof(data),"%s/data",root)<(int)sizeof(data));setup(mkdir(data,0700)==0);
    setup(snprintf(artifacts,sizeof(artifacts),"%s/code",root)<(int)sizeof(artifacts));setup(mkdir(artifacts,0700)==0);
    setup(snprintf(files,sizeof(files),"%s/files",data)<(int)sizeof(files));setup(mkdir(files,0700)==0);
    setup(snprintf(file,sizeof(file),"%s/base.apk",artifacts)<(int)sizeof(file));write_raw(file,"owned-artifact");
    setup(tp_configure(data,data,artifacts,artifacts,"dev.tripoder.iofixture")==0);
    struct tp_path gpu;
    int resolved=tp_resolve(AT_FDCWD,"/dev/kgsl-3d0",TP_WRITE,&gpu);
    check("explicit-gpu-device-capability",resolved==0&&!strcmp(gpu.leaf,"/dev/kgsl-3d0")&&!gpu.owned);if(resolved==0)tp_release(&gpu);
    check("gpu-capability-does-not-grant-dev-subtree",tp_resolve(AT_FDCWD,"/dev/block/other",TP_READ,&gpu)==-1&&errno==EACCES);
    check("gpu-capability-does-not-grant-sibling",tp_resolve(AT_FDCWD,"/dev/kgsl-3d00",TP_WRITE,&gpu)==-1&&errno==EACCES);
    const char *logical="/data/data/dev.tripoder.iofixture/files/value";
    int fd=tp_openat(AT_FDCWD,logical,O_RDWR|O_CREAT|O_EXCL|O_CLOEXEC,0600);check("logical-open-real-fd",fd>=0);if(fd<0)return 1;
    check("kernel-write",write(fd,"value",5)==5&&fsync(fd)==0);
    int alias=tp_openat(AT_FDCWD,"/data/user/0/dev.tripoder.iofixture/files/value",O_RDONLY,0);char bytes[8]={0};
    check("aliases-share-actual-bytes",alias>=0&&read(alias,bytes,5)==5&&!strcmp(bytes,"value"));if(alias>=0)close(alias);
    int dir=tp_openat(AT_FDCWD,"/data/data/dev.tripoder.iofixture/files",O_PATH|O_DIRECTORY,0);check("real-directory-fd",dir>=0);
    int relative=tp_openat(dir,"value",O_RDONLY,0);memset(bytes,0,sizeof(bytes));check("relative-dirfd-read",relative>=0&&read(relative,bytes,5)==5&&!strcmp(bytes,"value"));if(relative>=0)close(relative);
    int missing=tp_openat(AT_FDCWD,"/data/data/dev.tripoder.iofixture/files/missing/../value",O_RDONLY,0);check("missing-parent-not-normalized-away",missing==-1&&errno==ENOENT);
    int escaped=tp_openat(dir,"../value",O_CREAT|O_WRONLY,0600);check("dirfd-parent-above-anchor-explicitly-rejected",escaped==-1&&errno==EACCES);
    escaped=tp_openat(AT_FDCWD,"/data/data/dev.tripoder.iofixture/../outside",O_CREAT|O_WRONLY,0600);check("logical-root-escape-rejected",escaped==-1&&errno==EACCES);
    int immutable=tp_openat(AT_FDCWD,file,O_WRONLY|O_TRUNC,0);check("artifact-write-rejected",immutable==-1&&errno==EROFS);
    int unrelated=tp_openat(AT_FDCWD,"/data/data/another.app/private",O_RDONLY,0);check("unknown-private-path-denied",unrelated==-1&&errno==EACCES);
    setup(snprintf(link,sizeof(link),"%s/link",files)<(int)sizeof(link));setup(symlink(file,link)==0);
    int linked=tp_openat(AT_FDCWD,"/data/data/dev.tripoder.iofixture/files/link",O_RDONLY,0);check("leaf-symlink-no-follow",linked==-1&&errno==ELOOP);
    linked=tp_openat(AT_FDCWD,"/data/data/dev.tripoder.iofixture/files/link/",O_RDONLY,0);check("trailing-slash-cannot-follow-link",linked==-1&&(errno==ELOOP||errno==ENOTDIR));
    setup(unlink(link)==0);setup(symlink(files,link)==0);
    char target_buf[PATH_MAX]={0};ssize_t link_len=tp_readlinkat(AT_FDCWD,"/data/data/dev.tripoder.iofixture/files/link",target_buf,sizeof(target_buf)-1);
    check("readlinkat-reverses-physical-to-logical-path",link_len>0&&!strcmp(target_buf,"/data/user/0/dev.tripoder.iofixture/files"));
    setup(unlink(link)==0);setup(symlink(artifacts,link)==0);linked=tp_openat(AT_FDCWD,"/data/data/dev.tripoder.iofixture/files/link/../value",O_RDONLY,0);check("symlink-parent-not-normalized-away",linked==-1&&(errno==ELOOP||errno==ENOTDIR));setup(unlink(link)==0);
    linked=tp_symlinkat(file,dir,"forbidden-link");check("new-symlink-explicitly-unsupported",linked==-1&&errno==EOPNOTSUPP);
    struct stat info;check("logical-stat-real-inode",tp_fstatat(AT_FDCWD,logical,&info,0)==0&&info.st_size==5);
    int dupped=dup(fd);close(fd);check("dup-retains-kernel-description",dupped>=0&&lseek(dupped,0,SEEK_SET)==0);
    check("rename-real-dirfd",tp_renameat2(dir,"value",dir,"renamed",0)==0);
    memset(bytes,0,sizeof(bytes));check("open-fd-survives-rename",read(dupped,bytes,5)==5&&!strcmp(bytes,"value"));
    check("unlink-real-dirfd",tp_unlinkat(dir,"renamed",0)==0);check("unlinked-open-fd-still-valid",fstat(dupped,&info)==0&&info.st_nlink==0&&info.st_size==5);
    void *map=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED,dupped,0);check("mmap-real-file-description",map!=MAP_FAILED);
    close(dupped);if(map!=MAP_FAILED){check("mapping-survives-fd-close",memcmp(map,"value",5)==0);memcpy(map,"after",5);check("shared-mapping-msync",msync(map,4096,MS_SYNC)==0);check("munmap",munmap(map,4096)==0);}
    close(dir);int cwd=tp_openat(AT_FDCWD,"relative",O_CREAT|O_EXCL|O_RDWR,0600);check("cwd-is-guest-data",cwd>=0);if(cwd>=0)close(cwd);
    setup(symlink(file,link)==0);memset(target_buf,0,sizeof(target_buf));link_len=tp_readlinkat(AT_FDCWD,"/data/data/dev.tripoder.iofixture/files/link",target_buf,sizeof(target_buf)-1);
    check("readlinkat-reverses-artifact-to-logical-apk-path",link_len>0&&!strcmp(target_buf,"/data/app/~~tripoder/dev.tripoder.iofixture/base.apk"));setup(unlink(link)==0);
    int logical_apk=tp_openat(AT_FDCWD,"/data/app/~~tripoder/dev.tripoder.iofixture/base.apk",O_RDONLY,0);char apk_bytes[16]={0};
    check("logical-artifact-apk-open",logical_apk>=0&&read(logical_apk,apk_bytes,14)==14&&!strcmp(apk_bytes,"owned-artifact"));if(logical_apk>=0)close(logical_apk);
    int proc_cmd=tp_openat(AT_FDCWD,"/proc/self/cmdline",O_RDONLY,0);char cmd_bytes[64]={0};
    check("proc-self-cmdline-virtualized",proc_cmd>=0&&read(proc_cmd,cmd_bytes,sizeof(cmd_bytes)-1)==(ssize_t)strlen("dev.tripoder.iofixture")+1&&!strcmp(cmd_bytes,"dev.tripoder.iofixture"));if(proc_cmd>=0)close(proc_cmd);
    printf("{\"summary\":true,\"passed\":%s,\"checks\":%d,\"failures\":%d,\"hookInstalled\":false,\"strongIsolationVerified\":false}\n",failures==0?"true":"false",checks,failures);return failures?1:0;
}
