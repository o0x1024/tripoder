#define _GNU_SOURCE
#include "probe_libc_cases.h"
#include "tripoder_fixed_libc.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

static void check(struct tp_libc_case_result *r,int condition,const char *name) {
    r->checks++;
    if (!condition) { r->failures++; if (!r->first) r->first=name; }
}
void tp_probe_libc_cases(uintptr_t base,const char *logical_directory,const char *artifact_directory,
                         const char *foreign_owned_path,struct tp_libc_case_result *r) {
    struct tp_fixed_libc_replacements entry={
        .openat=(void *)(base+0xe0a40), .fstatat=(void *)(base+0xe0b40), .unlinkat=(void *)(base+0xe0d00),
        .mkdirat=(void *)(base+0xe0bc0), .renameat2=(void *)(base+0xe0c80), .faccessat=(void *)(base+0xe0a80),
        .readlinkat=(void *)(base+0xe0c40), .chdir=(void *)(base+0xe1040),
        .symlinkat=(void *)(base+0xe0cc0), .linkat=(void *)(base+0xe0b80),
        .fchmodat=(void *)(base+0xe0ac0)};
    char file[PATH_MAX],renamed[PATH_MAX],directory[PATH_MAX],link[PATH_MAX],attempt[PATH_MAX],artifact[PATH_MAX];
    int lengths[]={snprintf(file,sizeof(file),"%s/value",logical_directory),
        snprintf(renamed,sizeof(renamed),"%s/renamed",logical_directory),
        snprintf(directory,sizeof(directory),"%s/subdir",logical_directory),
        snprintf(link,sizeof(link),"%s/link",logical_directory),
        snprintf(attempt,sizeof(attempt),"%s/new-link",logical_directory),
        snprintf(artifact,sizeof(artifact),"%s/seed.dat",artifact_directory)};
    for (unsigned i=0;i<sizeof(lengths)/sizeof(lengths[0]);i++) if (lengths[i]<0 || lengths[i]>=PATH_MAX) {
        check(r,0,"CASE_PATH_LIMIT"); return;
    }
    int fd=entry.openat(AT_FDCWD,file,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0600);
    check(r,fd>=0,"REAL_OPENAT_CREATE");
    if (fd<0) return;
    check(r,syscall(SYS_write,fd,"test",4)==4,"REAL_DESCRIPTOR_WRITE");
    check(r,syscall(SYS_close,fd)==0,"REAL_DESCRIPTOR_CLOSE");
    struct stat stat;
    check(r,entry.fstatat(AT_FDCWD,file,&stat,0)==0 && stat.st_size==4,"REAL_FSTATAT");
    check(r,entry.faccessat(AT_FDCWD,file,R_OK)==0,"REAL_FACCESSAT");
    check(r,entry.mkdirat(AT_FDCWD,directory,0700)==0,"REAL_MKDIRAT");
    check(r,entry.renameat2(AT_FDCWD,file,AT_FDCWD,renamed,1)==0,"REAL_RENAMEAT2_NOREPLACE");
    errno=0;
    check(r,entry.openat(AT_FDCWD,file,O_RDONLY|O_CLOEXEC,0)==-1 && errno==ENOENT,"ORIGINAL_ENOENT");
    int (*unmodified_fchownat)(int,const char *,uid_t,gid_t,int)=(void *)(base+0xe0b00);
    check(r,unmodified_fchownat(AT_FDCWD,renamed,-1,-1,0)==0,"UNMODIFIED_SHARED_PAGE_FUNCTION");
    check(r,syscall(SYS_symlinkat,"renamed",AT_FDCWD,link)==0,"OWN_SYMLINK_FIXTURE");
    char buffer[64];
    ssize_t size=entry.readlinkat(AT_FDCWD,link,buffer,sizeof(buffer));
    check(r,size==7 && memcmp(buffer,"renamed",7)==0,"REAL_READLINKAT");
    errno=0;
    check(r,entry.openat(AT_FDCWD,link,O_RDONLY|O_CLOEXEC,0)==-1 && errno==ELOOP,"OWNED_SYMLINK_OPEN_REJECTED");
    errno=0;
    check(r,entry.symlinkat("renamed",AT_FDCWD,attempt)==-1 && errno==EOPNOTSUPP,"SYMLINK_ADAPTER_DENIAL");
    errno=0;
    check(r,entry.linkat(AT_FDCWD,renamed,AT_FDCWD,attempt,0)==-1 && errno==EOPNOTSUPP,"HARDLINK_ADAPTER_DENIAL");
    check(r,entry.chdir(logical_directory)==0,"REAL_CHDIR");
    fd=entry.openat(AT_FDCWD,"renamed",O_RDONLY|O_CLOEXEC,0);
    check(r,fd>=0,"RELATIVE_PATH_AFTER_CHDIR");
    if (fd>=0) {
        check(r,syscall(SYS_read,fd,buffer,4)==4 && !memcmp(buffer,"test",4),"RELATIVE_REAL_CONTENT");
        syscall(SYS_close,fd);
    }
    fd=entry.openat(AT_FDCWD,artifact,O_RDONLY|O_CLOEXEC,0);
    check(r,fd>=0,"READ_ONLY_ARTIFACT_READ");
    if (fd>=0) syscall(SYS_close,fd);
    errno=0;
    check(r,entry.openat(AT_FDCWD,artifact,O_WRONLY|O_CLOEXEC,0)==-1 && errno==EROFS,"READ_ONLY_ARTIFACT_WRITE_REJECTED");
    errno=0;
    check(r,entry.openat(AT_FDCWD,foreign_owned_path,O_CREAT|O_WRONLY|O_CLOEXEC,0600)==-1 && errno==EACCES,
          "OWN_EXTERNAL_TEST_PATH_OUTSIDE_POLICY_REJECTED");
    char invalid[PATH_MAX];
    int n=snprintf(invalid,sizeof(invalid),"%s/missing/../must-not-exist",logical_directory);
    errno=0;
    check(r,n>0 && n<PATH_MAX && entry.openat(AT_FDCWD,invalid,O_CREAT|O_WRONLY|O_CLOEXEC,0600)==-1 && errno==ENOENT,
          "MISSING_PARENT_NOT_LEXICALLY_REMOVED");
    check(r,entry.unlinkat(AT_FDCWD,renamed,0)==0,"REAL_UNLINKAT_FILE");
    check(r,entry.unlinkat(AT_FDCWD,link,0)==0,"REAL_UNLINKAT_SYMLINK");
    check(r,entry.unlinkat(AT_FDCWD,directory,AT_REMOVEDIR)==0,"REAL_UNLINKAT_DIRECTORY");
}
