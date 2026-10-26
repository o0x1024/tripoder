/* Own address space only. Compile the exact production implementation; no VM
 * entry points, guest code, hook installation or other process is accessed. */
#include "art_access.c"
#include <sys/mman.h>

int main(void){
    long page=sysconf(_SC_PAGESIZE);
    if(page<=0)return 2;
    const unsigned readable_pages=4097;
    size_t length=(size_t)readable_pages*2*(size_t)page;
    char *region=mmap(NULL,length,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    if(region==MAP_FAILED)return 3;
    for(unsigned i=0;i<readable_pages;i++){
        if(mprotect(region+(size_t)i*2*(size_t)page,(size_t)page,PROT_READ))return 4;
    }
    uintptr_t target=(uintptr_t)(region+(size_t)(readable_pages-1)*2*(size_t)page);
    int kernel_map_readable=mapped_inner(target,32,0,NULL);
    int observed=tp_art_readable(target,32);
    unsigned cached=readable_count;
    int gap_rejected=!tp_art_readable(target+(uintptr_t)page,32);
    int overflow_rejected=!tp_art_readable(UINTPTR_MAX-1,32);
    int passed=kernel_map_readable&&observed&&cached==2048&&gap_rejected&&overflow_rejected;
    printf("{\"passed\":%s,\"checks\":5,\"ownedReadablePages\":%u,\"cachedRanges\":%u,\"kernelMapReadable\":%s,\"productionReadable\":%s,\"noAccessGapRejected\":%s,\"overflowRejected\":%s,\"otherProcessAccessed\":false}\n",
        passed?"true":"false",readable_pages,cached,kernel_map_readable?"true":"false",observed?"true":"false",gap_rejected?"true":"false",overflow_rejected?"true":"false");
    munmap(region,length);
    return passed?0:1;
}
