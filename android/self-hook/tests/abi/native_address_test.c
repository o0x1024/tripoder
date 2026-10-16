#include "../../native/abi/native_prefix.h"
#include <assert.h>
#include <stdio.h>
static unsigned checks;
#define CHECK(x) do{checks++;assert(x);}while(0)
int main(void){
    const uintptr_t entry=0x10000000,relay=0x1000,original=0x1010;uintptr_t low,high;
    const uint32_t words[]={0xd503201f,0x10000400,0x90000020,0x58000400,0x14000020,0x54000400,0xb4000400,0x36000400,0x37000400,0xd101c3ff,0xa9be7bfd,0xd65f03c0};
    for(unsigned i=0;i<sizeof(words)/sizeof(*words);i++){
        CHECK(tp_native_address_range(&words[i],1,entry,relay,original,12288,&low,&high)==TP_A64_OK);
        CHECK(!(low&4095)&&!(high&4095)&&low<=high);
        uintptr_t points[]={low,high,((low+(high-low)/2)&~(uintptr_t)4095)};
        for(unsigned j=0;j<3;j++){
            if(points[j]<=entry+8&&points[j]+12288>=entry)continue;
            struct tp_native_prefix plan;CHECK(tp_native_prefix_plan(&words[i],1,entry,points[j]+relay,points[j]+original,&plan)==TP_A64_OK);
        }
        if((words[i]&0x7e000000)==0x36000000)CHECK(high-low<=65536);
    }
    uint32_t invalid=0;low=19;high=23;
    CHECK(tp_native_address_range(&invalid,1,entry,relay,original,12288,&low,&high)==TP_A64_UNSUPPORTED);
    CHECK(low==19&&high==23);
    uint32_t bti[]={TP_A64_BTI_C,0x36000400};
    CHECK(tp_native_address_range(bti,2,entry,relay,original,12288,&low,&high)==TP_A64_OK);
    struct tp_native_prefix plan;CHECK(tp_native_prefix_plan(bti,2,entry,low+relay,low+original,&plan)==TP_A64_OK);
    puts("NATIVE_ADDRESS_PASS");printf("checks=%u\n",checks);return 0;
}
