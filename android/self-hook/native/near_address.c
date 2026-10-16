#define _GNU_SOURCE
#include "near_address.h"
#include <errno.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>

#define CANDIDATES 128u
static void offer(uintptr_t low,uintptr_t high,uintptr_t preferred,uintptr_t values[CANDIDATES],unsigned *count){
    if(low>UINTPTR_MAX-4095)return;
    low=(low+4095)&~(uintptr_t)4095;high&=~(uintptr_t)4095;
    if(low>high||*count==CANDIDATES)return;
    uintptr_t at=preferred&~(uintptr_t)4095;if(at<low)at=low;if(at>high)at=high;
    uintptr_t choices[3]={at,low,high};
    for(unsigned i=0;i<3&&*count<CANDIDATES;i++){
        bool duplicate=false;for(unsigned j=0;j<*count;j++)if(values[j]==choices[i])duplicate=true;
        if(!duplicate)values[(*count)++]=choices[i];
    }
}
void *tp_near_address_reserve_range(uintptr_t low,uintptr_t high,uintptr_t preferred,size_t bytes,int protection){
    if(!bytes||(bytes&4095)||bytes>65536||low>high||high>UINTPTR_MAX-bytes||
        (protection!=PROT_NONE&&protection!=(PROT_READ|PROT_WRITE))){errno=EINVAL;return MAP_FAILED;}
    uintptr_t candidates[CANDIDATES],cursor=low;unsigned count=0,lines=0;bool valid=true;
    FILE *file=fopen("/proc/self/maps","r");if(!file)return MAP_FAILED;
    char line[2048];unsigned long begin,end;
    while(fgets(line,sizeof(line),file)){
        if(++lines>65536||!strchr(line,'\n')||sscanf(line,"%lx-%lx",&begin,&end)!=2||begin>=end){valid=false;break;}
        if(end<=low)continue;
        if(begin>=bytes&&begin-bytes>=cursor){
            uintptr_t last=begin-bytes;if(last>high)last=high;
            offer(cursor,last,preferred,candidates,&count);
        }
        if(begin>high+bytes)break;
        if(end>cursor)cursor=end;if(cursor>high)break;
    }
    if(ferror(file))valid=false;
    int saved=errno;if(fclose(file))return MAP_FAILED;
    if(!valid){errno=saved?saved:EINVAL;return MAP_FAILED;}
    if(cursor<=high)offer(cursor,high,preferred,candidates,&count);
    for(unsigned i=0;i<count;i++)for(unsigned j=i+1;j<count;j++){
        uintptr_t a=candidates[i]>preferred?candidates[i]-preferred:preferred-candidates[i];
        uintptr_t b=candidates[j]>preferred?candidates[j]-preferred:preferred-candidates[j];
        if(b<a){uintptr_t swap=candidates[i];candidates[i]=candidates[j];candidates[j]=swap;}
    }
    for(unsigned i=0;i<count;i++){
        void *address=mmap((void *)candidates[i],bytes,protection,MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED_NOREPLACE,-1,0);
        if(address==MAP_FAILED){if(errno==EEXIST)continue;return MAP_FAILED;}
        if((uintptr_t)address!=candidates[i]){munmap(address,bytes);errno=EINVAL;return MAP_FAILED;}
        return address;
    }
    errno=ENOMEM;return MAP_FAILED;
}
void *tp_near_address_reserve(uintptr_t target,size_t bytes,int protection){
    if(target<UINT64_C(0x8000000)||target>UINTPTR_MAX-UINT64_C(0x8000000)){
        errno=EINVAL;return MAP_FAILED;
    }
    return tp_near_address_reserve_range(target-UINT64_C(0x7f00000),target+UINT64_C(0x7f00000),target,bytes,protection);
}
