#!/usr/bin/env python3
"""Execute the real fixed-libc admission functions with deterministic park/page mocks.

This checks rejection/publication ordering and diagnostic budgets on the host.
It does not model Linux signal return, W^X, MREMAP, or prove device progress.
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

HARNESS = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdatomic.h>
#include <string.h>
#include "tripoder_fixed_libc.h"
#include "code_page.h"
#include "libc_fixed_contract.h"
#define SYS_exit_group 94
static unsigned checks;
#define CHECK(c) do { checks++; if(!(c)){fprintf(stderr,"line %d: %s\n",__LINE__,#c);return 1;} } while(0)
static uint64_t tick, step;
static unsigned begins,ends,commits,cancels,finishes,mode;
static bool paused,preimage_ok,pause_ok,clock_zero,clock_backwards;
static uintptr_t pcs[2];
static int failed_context_index;
static enum tp_park_status failed_context_status;
static long tp_park_syscall(long n,long a,long b,long c,long d,long e,long f){
    (void)n;(void)a;(void)b;(void)c;(void)d;(void)e;(void)f;assert(0);return 0;
}
static uint64_t tp_park_now(void){if(clock_zero)return 0;if(clock_backwards&&begins)return 1;return tick;}
static void tp_park_sleep(long n){assert(!paused);tick+=(uint64_t)n;}
enum tp_park_status tp_park_begin(struct tp_park_snapshot *s){
    assert(!paused);begins++;tick+=step;
    *s=(struct tp_park_snapshot){.epoch=begins,.observed=2,.parked=2,.passes=2};
    if(!pause_ok)return TP_PARK_TIMEOUT;
    paused=true;return TP_PARK_OK;
}
enum tp_park_status tp_park_end(unsigned epoch){assert(paused&&epoch==begins);paused=false;ends++;return TP_PARK_OK;}
enum tp_park_status tp_park_context(unsigned epoch,unsigned i,struct tp_park_context *c){
    assert(paused&&epoch==begins&&i<2);
    if((int)i==failed_context_index)return failed_context_status;
    uintptr_t pc=pcs[i];
    if(mode==1)pc=begins==1&&i==0?0x100000+tp_libc_contract[0].offset+12:0x200000;
    if(mode==2)pc=((begins+i)%2)?0x100000+tp_libc_contract[0].offset+12:0x200000;
    *c=(struct tp_park_context){.tid=(int)i+11,.pc=pc};return TP_PARK_OK;
}
int tp_code_page_validate(const struct tp_code_page_update *u){(void)u;assert(paused);return preimage_ok;}
int tp_code_page_commit(struct tp_code_page_update *u){(void)u;assert(paused&&preimage_ok);commits++;return 1;}
int tp_code_page_prepare(struct tp_code_page *p,const uint32_t *e,const uint32_t *r,struct tp_code_page_update *u){
    (void)e;(void)r;assert(!paused);u->owner=p;return 1;
}
int tp_code_page_prepare_restore(struct tp_code_page *p,const uint32_t *e,struct tp_code_page_update *u){
    return tp_code_page_prepare(p,e,e,u);
}
void tp_code_page_finish(struct tp_code_page_update *u){(void)u;assert(!paused);finishes++;}
void tp_code_page_cancel(struct tp_code_page_update *u){(void)u;assert(!paused);cancels++;}
'''
TAIL = r'''
static void reset(void){
    memset(&fixed,0,sizeof(fixed));fixed.report.ready=true;fixed.report.base=0x100000;
    tick=1000000000;step=10000;begins=ends=commits=cancels=finishes=mode=0;
    paused=false;preimage_ok=pause_ok=true;clock_zero=clock_backwards=false;
    pcs[0]=pcs[1]=0x200000;
    failed_context_index=-1;failed_context_status=TP_PARK_INVENTORY;
}
int main(void){
    // Every real pinned entry and both exact window boundaries.
    for(unsigned e=0;e<TP_FIXED_LIBC_ENTRIES;e++)for(unsigned off=0;off<=24;off+=4){
        reset();pcs[0]=fixed.report.base+tp_libc_contract[e].offset+off;
        struct tp_park_snapshot s;CHECK(tp_park_begin(&s)==TP_PARK_OK);
        int changed=fixed_commit_batch(s.epoch,s.observed);
        bool unsafe=off>=8&&off<20;
        CHECK(changed==(unsafe?-1:2));CHECK(commits==(unsafe?0u:2u));CHECK(ends==1&&!paused);
        if(unsafe){CHECK(fixed.report.unsafe_pc_counts[e][(off-8)/4]==1);CHECK(fixed.report.unsafe_tid==11);}
    }
    // Count all unsafe threads; a later frame must not disappear in first-hit reporting.
    reset();pcs[0]=fixed.report.base+tp_libc_contract[0].offset+8;
    pcs[1]=fixed.report.base+tp_libc_contract[9].offset+16;
    struct tp_park_snapshot s;CHECK(tp_park_begin(&s)==TP_PARK_OK);
    CHECK(fixed_commit_batch(s.epoch,s.observed)==-1);
    CHECK(fixed.report.unsafe_pc_counts[0][0]==1&&fixed.report.unsafe_pc_counts[9][2]==1&&commits==0);
    // Fast repeated unsafe snapshots exhaust the existing 64-attempt boundary, not one second.
    reset();pcs[0]=fixed.report.base+tp_libc_contract[0].offset+12;
    CHECK(transition(true)==TP_FIXED_LIBC_IN_FLIGHT);
    CHECK(fixed.report.admission_outcome==TP_FIXED_LIBC_ADMISSION_ATTEMPTS);
    CHECK(begins==64&&ends==64&&commits==0&&cancels==2&&finishes==0);
    CHECK(fixed.report.admission_attempts==64&&fixed.report.admission_elapsed_ns<1000000000);
    CHECK(fixed.report.quiescence_rejections==64&&fixed.report.unsafe_pc_counts[0][1]==64);
    // Slow snapshots instead reach the unchanged one-second boundary.
    reset();step=400000000;pcs[0]=fixed.report.base+tp_libc_contract[0].offset+12;
    CHECK(transition(true)==TP_FIXED_LIBC_IN_FLIGHT);
    CHECK(fixed.report.admission_outcome==TP_FIXED_LIBC_ADMISSION_DEADLINE&&begins==3);
    CHECK(fixed.report.admission_elapsed_ns>=1000000000&&commits==0&&cancels==2);
    // A naturally drained second snapshot commits both pages only after the rejection was resumed.
    reset();mode=1;CHECK(transition(true)==TP_FIXED_LIBC_OK);
    CHECK(begins==2&&ends==2&&commits==2&&finishes==2&&cancels==0);
    CHECK(fixed.report.admission_outcome==TP_FIXED_LIBC_ADMISSION_COMMITTED&&fixed.report.publications==2);
    CHECK(fixed.report.quiescence_rejections==1&&fixed.report.unsafe_pc_counts[0][1]==1);
    // Alternating unsafe workers each progress, yet release-all does not guarantee a common safe snapshot.
    reset();mode=2;CHECK(transition(true)==TP_FIXED_LIBC_IN_FLIGHT);
    CHECK(begins==64&&commits==0&&fixed.report.unsafe_pc_counts[0][1]==64);
    // Restore uses the same guard: a dispatch frame cannot be interpreted as an old SVC.
    reset();fixed.report.enabled=true;fixed.report.publications=2;
    pcs[0]=fixed.report.base+tp_libc_contract[0].offset+8;
    CHECK(transition(false)==TP_FIXED_LIBC_IN_FLIGHT);
    CHECK(!fixed.report.admission_enable&&fixed.report.enabled&&fixed.report.restorations==0&&commits==0);
    reset();preimage_ok=false;CHECK(transition(true)==TP_FIXED_LIBC_PAGE);
    CHECK(begins==1&&ends==1&&commits==0&&cancels==2&&fixed.report.rejected_batches==1);
    CHECK(fixed.report.admission_outcome==TP_FIXED_LIBC_ADMISSION_PREIMAGE);
    // Invalid/missing contexts are a park protocol rejection, never a preimage mismatch.
    for(unsigned status=TP_PARK_NOT_READY;status<=TP_PARK_WRONG_OWNER;status++){
        reset();failed_context_index=0;failed_context_status=(enum tp_park_status)status;
        CHECK(transition(true)==TP_FIXED_LIBC_PAUSE);
        CHECK(fixed.report.admission_outcome==TP_FIXED_LIBC_ADMISSION_CONTEXT);
        CHECK(fixed.report.context_status==(int)status&&fixed.report.context_index==0);
        CHECK(begins==1&&ends==1&&commits==0&&cancels==2&&finishes==0&&!paused);
        CHECK(fixed.report.rejected_batches==0&&fixed.report.quiescence_rejections==0);
        // An earlier unsafe frame is still recorded, but a later unknown context dominates.
        reset();failed_context_index=1;failed_context_status=(enum tp_park_status)status;
        pcs[0]=fixed.report.base+tp_libc_contract[0].offset+12;
        CHECK(transition(true)==TP_FIXED_LIBC_PAUSE);
        CHECK(fixed.report.admission_outcome==TP_FIXED_LIBC_ADMISSION_CONTEXT);
        CHECK(fixed.report.context_status==(int)status&&fixed.report.context_index==1);
        CHECK(fixed.report.unsafe_pc_counts[0][1]==1&&fixed.report.unsafe_tid==11);
        CHECK(begins==1&&ends==1&&commits==0&&cancels==2&&finishes==0&&!paused);
        CHECK(fixed.report.rejected_batches==0&&fixed.report.quiescence_rejections==0);
    }
    // A later explicit attempt revalidates everything and resets prior context diagnostics.
    failed_context_index=-1;pcs[0]=pcs[1]=0x200000;
    CHECK(transition(true)==TP_FIXED_LIBC_OK);
    CHECK(fixed.report.context_status==TP_PARK_OK&&fixed.report.context_index==-1);
    CHECK(fixed.report.unsafe_pc_counts[0][1]==0&&fixed.report.admission_outcome==TP_FIXED_LIBC_ADMISSION_COMMITTED);
    CHECK(commits==2&&finishes==2&&cancels==2&&begins==2&&ends==2&&!paused);
    reset();pause_ok=false;CHECK(transition(true)==TP_FIXED_LIBC_PAUSE);
    CHECK(begins==1&&ends==0&&commits==0&&cancels==2&&fixed.report.admission_outcome==TP_FIXED_LIBC_ADMISSION_PAUSE);
    reset();clock_zero=true;CHECK(transition(true)==TP_FIXED_LIBC_PAGE);
    CHECK(begins==0&&commits==0&&cancels==2&&fixed.report.admission_outcome==TP_FIXED_LIBC_ADMISSION_CLOCK);
    reset();clock_backwards=true;pcs[0]=fixed.report.base+tp_libc_contract[0].offset+12;
    CHECK(transition(true)==TP_FIXED_LIBC_IN_FLIGHT);
    CHECK(begins==1&&commits==0&&fixed.report.admission_outcome==TP_FIXED_LIBC_ADMISSION_CLOCK);
    printf("{\"passed\":true,\"checks\":%u,\"scope\":\"real admission functions with deterministic park/page mocks\"}\n",checks);
    return 0;
}
'''

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root",type=Path,default=Path(__file__).resolve().parents[3])
    parser.add_argument("--native-root",type=Path,help="Native dependencies when testing an isolated source patch")
    parser.add_argument("--cc",default="clang")
    parser.add_argument("--output",type=Path)
    args = parser.parse_args()
    root=args.root.resolve()
    native=(args.native_root or root).resolve()
    source=(root/"android/self-hook/native/fixed_libc.c").read_text()
    state=source[source.index("static struct {"):source.index("#ifdef TP_SELF_HOOK_TESTING")]
    methods=source[source.index("__attribute__((noinline)) static int fixed_commit_batch"):source.index("enum tp_fixed_libc_status tp_fixed_libc_activate")]
    with tempfile.TemporaryDirectory(prefix="fixed-libc-admission-") as directory:
        directory=Path(directory)
        cfile=directory/"admission.c";binary=directory/"admission"
        cfile.write_text(HARNESS+state+methods+TAIL)
        subprocess.run([args.cc,"-std=c11","-Wall","-Wextra","-Werror","-O2",
            "-I",str(root/"android/self-hook/include"),"-I",str(native/"android/self-hook/include"),
            "-I",str(native/"android/self-hook/native"),str(cfile),"-o",str(binary)],check=True)
        result=json.loads(subprocess.check_output([str(binary)],text=True))
    if args.output:args.output.write_text(json.dumps(result,indent=2)+"\n")
    print(json.dumps(result))

if __name__=="__main__":main()
