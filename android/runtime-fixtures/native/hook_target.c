#include <stdint.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <stdlib.h>

static _Atomic int calls[4];
static char audit[4096];
void tripoder_hook_audit_path(const char *path) { if (strlen(path)>=sizeof(audit)) abort();strcpy(audit,path); }
static void write_audit(int value) { FILE *file=fopen(audit,"w");if(!file)abort();if(fprintf(file,"%d",value)<0||fflush(file)||fsync(fileno(file)))abort();fclose(file); }
void tripoder_hook_reset(void) { for(int i=0;i<4;i++)atomic_store(&calls[i],0);write_audit(0); }
int32_t tripoder_hook_count(int32_t index) { if(index<0||index>3)abort();return atomic_load(&calls[index]); }
__attribute__((noinline)) int32_t tripoder_hook_add(int32_t a,int32_t b) { atomic_fetch_add(&calls[0],1);return a+b; }
__attribute__((noinline)) uint64_t tripoder_hook_exact(uint64_t value) { atomic_fetch_add(&calls[1],1);return value; }
__attribute__((noinline)) double tripoder_hook_fraction(double value) { atomic_fetch_add(&calls[2],1);return value+0.5; }
__attribute__((noinline)) int32_t tripoder_hook_error(int32_t value) { int count=atomic_fetch_add(&calls[3],1)+1;write_audit(count);return value; }
