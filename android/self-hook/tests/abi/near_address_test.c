#define _GNU_SOURCE
#include <stdio.h>
#include <stdint.h>
#include <stdbool.h>
#include <errno.h>
#include <sys/mman.h>
#include <assert.h>
#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif
static unsigned attempts,failures;static int failure_errno;static bool many;
static FILE *maps(const char *name,const char *mode){
    (void)name;(void)mode;FILE *f=tmpfile();assert(f);
    if(many){for(unsigned i=0;i<200;i++)fprintf(f,"%lx-%lx r-xp 0 00:00 0\n",0x10000000ul+i*8192,0x10001000ul+i*8192);}
    else fputs("10000000-10001000 r-xp 0 00:00 0\n10003000-10004000 r-xp 0 00:00 0\n10007000-10008000 r-xp 0 00:00 0\n10010000-10011000 r-xp 0 00:00 0\n",f);
    rewind(f);return f;
}
static void *claim(void *at,size_t size,int protection,int flags,int fd,off_t offset){
    assert(size==4096&&protection==PROT_NONE&&(flags&MAP_FIXED_NOREPLACE)&&fd==-1&&offset==0);attempts++;
    if(failures){failures--;errno=failure_errno;return MAP_FAILED;}return at;
}
#define fopen maps
#define mmap claim
#include "../../native/near_address.c"
#undef fopen
#undef mmap
int main(void){
    failures=1;failure_errno=EEXIST;
    void *result=tp_near_address_reserve_range(0x10000000,0x10010000,0x10004000,4096,PROT_NONE);
    assert(result!=MAP_FAILED&&result!=(void *)0x10004000&&attempts==2);
    attempts=0;failures=1;failure_errno=EACCES;
    assert(tp_near_address_reserve_range(0x10000000,0x10010000,0x10004000,4096,PROT_NONE)==MAP_FAILED&&errno==EACCES&&attempts==1);
    attempts=0;failures=0;many=true;uintptr_t preferred=0x10000000+199*8192+4096;
    assert(tp_near_address_reserve_range(0x10000000,preferred,preferred,4096,PROT_NONE)==(void *)preferred&&attempts==1);
    attempts=0;
    assert(tp_near_address_reserve_range(2,1,0,4096,PROT_NONE)==MAP_FAILED&&errno==EINVAL&&attempts==0);
    assert(tp_near_address_reserve_range(0,UINTPTR_MAX,0,4096,PROT_NONE)==MAP_FAILED&&errno==EINVAL);
    assert(tp_near_address_reserve_range(0,4096,0,4096,PROT_READ|PROT_WRITE|PROT_EXEC)==MAP_FAILED&&errno==EINVAL);
    puts("NEAR_ADDRESS_PASS checks=6");return 0;
}
