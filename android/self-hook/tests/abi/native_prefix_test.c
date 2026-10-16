#include "../../native/abi/native_prefix.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
static unsigned checks;
#define CHECK(x) do{checks++;assert(x);}while(0)
static void good(uint32_t word,unsigned frame,unsigned reg1,unsigned offset1,unsigned reg2,unsigned offset2,bool pac){
    struct tp_native_prefix plan={0};
    CHECK(tp_native_prefix_plan(&word,1,0x100000,0x110000,0x120000,&plan)==TP_A64_OK);
    CHECK(plan.frame==frame&&plan.signed_ra==pac&&plan.entry.expected_word==word);
    CHECK(plan.saved[0].present==(reg1!=255)&&(!plan.saved[0].present||(plan.saved[0].reg==reg1&&plan.saved[0].offset==offset1)));
    CHECK(plan.saved[1].present==(reg2!=255)&&(!plan.saved[1].present||(plan.saved[1].reg==reg2&&plan.saved[1].offset==offset2)));
    CHECK(plan.entry.trampoline[0]==TP_A64_BTI_C&&plan.entry.trampoline[1]==word&&plan.entry.patch_address==0x100000);
    CHECK(plan.entry.branch_word==0x14004000&&plan.original[3]==0x17ff7ffe);
}
int main(void){
    good(0xa9be7bfd,32,29,4,30,3,false);good(0xa9be53f3,32,19,4,20,3,false);
    good(0xa9bc7bfd,64,29,8,30,7,false);good(0xa9a07bfd,512,29,64,30,63,false);
    good(0xfc1e0fe8,32,72,4,255,0,false);good(0xfc100fef,256,79,32,255,0,false);
    good(0xf81e0ffe,32,30,4,255,0,false);
    good(0xd101c3ff,112,255,0,255,0,false);good(0xd13fc3ff,4080,255,0,255,0,false);
    good(TP_A64_NOP,0,255,0,255,0,false);
    good(0x91000400,0,255,0,255,0,false);
    uint32_t bti[]={TP_A64_BTI_C,0xd503233f};struct tp_native_prefix plan;
    CHECK(tp_native_prefix_plan(bti,2,0x100000,0x110000,0x120000,&plan)==TP_A64_OK);
    CHECK(plan.signed_ra&&!plan.entry_signed&&plan.entry.patch_address==0x100004&&plan.entry.preserved_prefix_bytes==4&&plan.original[3]==0x17ff7fff);
    uint32_t pac[]={0xd503233f,0xa9be7bfd};
    CHECK(tp_native_prefix_plan(pac,2,0x100000,0x110000,0x120000,&plan)==TP_A64_OK);
    CHECK(plan.entry_signed&&plan.signed_ra&&plan.frame==32&&plan.entry.patch_address==0x100004&&plan.original[1]==0xd503233f&&plan.original[2]==pac[1]);
    pac[1]=TP_A64_NOP;
    CHECK(tp_native_prefix_plan(pac,2,0x100000,0x110000,0x120000,&plan)==TP_A64_OK);
    CHECK(plan.entry_signed&&plan.signed_ra&&!plan.frame&&plan.original[1]==0xd503233f&&plan.original[2]==TP_A64_NOP);
    uint32_t rejected[]={0xa9817bfd,0xa9befbfd,0xfc1f8fe8,0xa8be7bfd,0xd503237f,0x910043ff,0xd100801f,0xd10003ff,0xd50324ff};
    for(unsigned i=0;i<sizeof(rejected)/sizeof(*rejected);i++){
        struct tp_native_prefix untouched;memset(&plan,0x5a,sizeof(plan));untouched=plan;
        CHECK(tp_native_prefix_plan(&rejected[i],1,0x100000,0x110000,0x120000,&plan)==TP_A64_UNSUPPORTED);
        CHECK(!memcmp(&plan,&untouched,sizeof(plan)));
    }
    uint32_t data[]={0x52800540,0x92800000,0xf2a24680,0xaa0103e0,0x0b010000,0xcb010000,0xca010000,
        0x1e6c1000,0x1e612800,0x9e660000,0xf9400820,0x3d800420,0x4f00e420,0xd65f03c0};
    for(unsigned i=0;i<sizeof(data)/sizeof(*data);i++){
        CHECK(tp_native_prefix_supported(&data[i],1,0x100000));
        CHECK(tp_native_prefix_plan(&data[i],1,0x100000,0x110000,0x120000,&plan)==TP_A64_OK);
        CHECK(!plan.frame&&!plan.signed_ra&&plan.original[2]==data[i]);
    }
    uint32_t pc_modifier[]={TP_A64_BTI_C,0xd50324ff};
    CHECK(!tp_native_prefix_supported(pc_modifier,2,0x100000));
    CHECK(tp_native_prefix_plan(pc_modifier,2,0x100000,0x110000,0x120000,&plan)==TP_A64_UNSUPPORTED);
    printf("{\"passed\":true,\"checks\":%u,\"prefixCases\":28,\"rejections\":10}\n",checks);
}
