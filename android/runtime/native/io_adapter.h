#ifndef TRIPODER_IO_ADAPTER_H
#define TRIPODER_IO_ADAPTER_H
#include <sys/stat.h>
#include <sys/types.h>
int tp_openat(int,const char *,int,unsigned);
int tp_fstatat(int,const char *,struct stat *,int);
int tp_unlinkat(int,const char *,int);
int tp_mkdirat(int,const char *,unsigned);
int tp_renameat2(int,const char *,int,const char *,unsigned);
int tp_faccessat(int,const char *,int);
ssize_t tp_readlinkat(int,const char *,char *,size_t);
int tp_chdir(const char *);
int tp_symlinkat(const char *,int,const char *);
int tp_linkat(int,const char *,int,const char *,int);
#endif
