#include "native_prefix.h"
#include "../a64_internal.h"
#include <limits.h>
/* Pure intersection of encoding constraints; the mapping variable is its base. */
static bool intersect(__int128 low,__int128 high,uintptr_t *first,uintptr_t *last){
    if(low<0)low=0;if(high>UINTPTR_MAX)high=UINTPTR_MAX;
    if(low>high||low>UINTPTR_MAX||high<0)return false;
    if((uintptr_t)low>*first)*first=(uintptr_t)low;
    if((uintptr_t)high<*last)*last=(uintptr_t)high;
    return *first<=*last;
}
static bool from_range(uintptr_t target,unsigned bits,unsigned scale,uintptr_t offset,uintptr_t *low,uintptr_t *high){
    __int128 reach=(__int128)1<<(bits-1);
    return intersect((__int128)target-(reach-1)*scale-offset,
                     (__int128)target+reach*scale-offset,low,high);
}
enum tp_a64_status tp_native_address_range(const uint32_t *input,size_t available,uintptr_t entry,
    uintptr_t relay_offset,uintptr_t original_offset,size_t bytes,uintptr_t *out_low,uintptr_t *out_high){
    if(!out_low||!out_high||bytes<4096||bytes>65536||(bytes&4095)||relay_offset>bytes-4||original_offset>bytes-16)
        return TP_A64_ARGUMENT;
    if(!tp_native_prefix_supported(input,available,entry))return TP_A64_UNSUPPORTED;
    unsigned prefix=input[0]==TP_A64_BTI_C||input[0]==TP_A64_BTI_JC||input[0]==0xd503233fu?4u:0u;
    if(entry>UINTPTR_MAX-prefix-4)return TP_A64_OVERFLOW;
    uintptr_t pc=entry+prefix,low=0,high=UINTPTR_MAX-bytes;
    if(!intersect((__int128)pc-((__int128)1<<27)-relay_offset,
                  (__int128)pc+(((__int128)1<<27)-4)-relay_offset,&low,&high)
       ||!from_range(pc+4,26,4,original_offset+12,&low,&high))return TP_A64_RANGE;
    uint32_t word=input[prefix/4];struct tp_reference ref;
    enum tp_a64_status status=tp_a64_reference(word,pc,&ref);if(status!=TP_A64_OK)return status;
    if(ref.kind==TP_REF_BRANCH&&ref.address==pc){/* relocated self-branch */}
    else if(ref.kind!=TP_REF_NONE){
        unsigned bits=19,scale=4;
        if((word&0xfc000000u)==0x14000000u)bits=26;
        else if((word&0x7e000000u)==0x36000000u)bits=14;
        else if(ref.kind==TP_REF_ADDRESS){bits=21;scale=1;}
        if(!from_range(ref.address,bits,scale,original_offset+8,&low,&high))return TP_A64_RANGE;
    }else if((word&0x9f000000u)==0x90000000u){
        uint32_t encoded=(((word>>5)&0x7ffffu)<<2)|((word>>29)&3u);
        int64_t displacement=(int64_t)(encoded^0x100000u)-0x100000;
        __int128 target=(__int128)(pc&~(uintptr_t)4095)+displacement*4096;
        if(target<0||target>UINTPTR_MAX)return TP_A64_OVERFLOW;
        if(!from_range((uintptr_t)target,21,4096,(original_offset+8)&~(uintptr_t)4095,&low,&high))return TP_A64_RANGE;
    }
    if(low>UINTPTR_MAX-4095)return TP_A64_RANGE;
    low=(low+4095)&~(uintptr_t)4095;high&=~(uintptr_t)4095;
    if(low>high)return TP_A64_RANGE;
    *out_low=low;*out_high=high;return TP_A64_OK;
}
