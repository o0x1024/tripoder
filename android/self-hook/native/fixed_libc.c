#define _GNU_SOURCE
#include "tripoder_fixed_libc.h"
#include "tripoder_thread_park.h"
#include "tripoder_a64.h"
#include "libc_fixed_contract.h"
#include "code_page.h"
#include "park_syscall.h"
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <link.h>
#include <stdatomic.h>
#include <string.h>
#include <sys/mman.h>

static struct {
    _Atomic bool busy;
    bool attempted;
    void *lease;
    struct tp_code_page pages[2];
    uint32_t original[2][TP_CODE_PAGE_WORDS], patched[2][TP_CODE_PAGE_WORDS];
    struct tp_code_page_update updates[2];
    struct tp_fixed_libc_report report;
} fixed;
#ifdef TP_SELF_HOOK_TESTING
static bool reject_next_batch;
void tp_fixed_libc_test_reject_next_batch(void) { reject_next_batch=true; }
#endif

static int find_libc(struct dl_phdr_info *info, size_t size, void *unused) {
    (void)size; (void)unused;
    if (strcmp(info->dlpi_name, TP_FIXED_LIBC_PATH)) return 0;
    bool identity = false, executable[2] = {false, false};
    for (unsigned i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) *header = &info->dlpi_phdr[i];
        if (header->p_type == PT_LOAD && (header->p_flags & (PF_R|PF_X|PF_W)) == (PF_R|PF_X)) {
            for (unsigned page = 0; page < 2; page++) {
                uintptr_t offset = 0xe0000 + page * 4096;
                if (header->p_vaddr <= offset && header->p_memsz >= 4096 &&
                    offset - header->p_vaddr <= header->p_memsz - 4096) executable[page] = true;
            }
        }
        if (header->p_type != PT_NOTE || header->p_memsz > 65536) continue;
        const unsigned char *at = (void *)(info->dlpi_addr + header->p_vaddr);
        size_t remaining = header->p_memsz;
        while (remaining >= sizeof(ElfW(Nhdr))) {
            ElfW(Nhdr) note;
            memcpy(&note, at, sizeof(note));
            size_t names = ((size_t)note.n_namesz + 3) & ~(size_t)3;
            size_t desc = ((size_t)note.n_descsz + 3) & ~(size_t)3;
            if (names > remaining - sizeof(note) || desc > remaining - sizeof(note) - names) break;
            if (note.n_type == NT_GNU_BUILD_ID && note.n_namesz == 4 && note.n_descsz == sizeof(tp_libc_build_id) &&
                !memcmp(at + sizeof(note), "GNU", 4) &&
                !memcmp(at + sizeof(note) + names, tp_libc_build_id, sizeof(tp_libc_build_id))) identity = true;
            size_t consumed = sizeof(note) + names + desc;
            at += consumed; remaining -= consumed;
        }
    }
    if (identity && executable[0] && executable[1]) fixed.report.base = info->dlpi_addr;
    return 1; /* exact loaded module only; no search for another build */
}


enum tp_fixed_libc_status tp_fixed_libc_prepare(const struct tp_fixed_libc_replacements *replacements) {
    bool expected = false;
    if (!atomic_compare_exchange_strong(&fixed.busy,&expected,true)) return TP_FIXED_LIBC_STATE;
    enum tp_fixed_libc_status status = TP_FIXED_LIBC_STATE;
    if (fixed.attempted || !replacements) goto done;
    fixed.attempted = true;
    uintptr_t callbacks[TP_FIXED_LIBC_ENTRIES] = {
        (uintptr_t)replacements->openat,(uintptr_t)replacements->fstatat,(uintptr_t)replacements->unlinkat,
        (uintptr_t)replacements->mkdirat,(uintptr_t)replacements->renameat2,(uintptr_t)replacements->faccessat,
        (uintptr_t)replacements->readlinkat,(uintptr_t)replacements->chdir,
        (uintptr_t)replacements->symlinkat,(uintptr_t)replacements->linkat};
    for (unsigned i=0;i<TP_FIXED_LIBC_ENTRIES;i++) if (!callbacks[i] || (callbacks[i]&3)) goto done;
    status = TP_FIXED_LIBC_IDENTITY;
    fixed.lease = dlopen(TP_FIXED_LIBC_PATH,RTLD_NOW|RTLD_LOCAL|RTLD_NOLOAD);
    if (!fixed.lease) goto done;
    dl_iterate_phdr(find_libc,NULL);
    if (!fixed.report.base) goto failed;
    for (unsigned p=0;p<2;p++) {
        uintptr_t address = fixed.report.base + 0xe0000 + p*4096;
        fixed.report.pages[p] = address;
        memcpy(fixed.original[p],(void *)address,4096);
        memcpy(fixed.patched[p],fixed.original[p],4096);
        if (!tp_code_page_claim(&fixed.pages[p],(void *)address,TP_FIXED_LIBC_PATH,fixed.original[p]) ||
            !fixed.pages[p].source.bti) goto failed;
    }
    fixed.report.source_bti = true;
    for (unsigned i=0;i<TP_FIXED_LIBC_ENTRIES;i++) {
        const struct tp_libc_entry_contract *entry=&tp_libc_contract[i];
        if (memcmp((void *)(fixed.report.base+entry->offset),entry->words,sizeof(entry->words))) goto failed;
        fixed.report.validated_entries++;
    }
    /* Fixed leaf syscall wrappers have no original-call adapter. Preserve
     * BTI c and tail-dispatch directly through AAPCS64 scratch x16. The pinned
     * host audit proves the entire 16-byte patch window has no other entry,
     * relocation or PC-relative reader. No near-address resource is needed. */
    status = TP_FIXED_LIBC_DETOUR;
    for (unsigned i=0;i<TP_FIXED_LIBC_ENTRIES;i++) {
        uintptr_t offset=tp_libc_contract[i].offset;
        unsigned page=(unsigned)((offset-0xe0000)/4096), word=(unsigned)((offset&4095)/4)+1;
        if(page>=2||word>TP_CODE_PAGE_WORDS-4)goto failed;
        fixed.patched[page][word]=UINT32_C(0x58000050); /* LDR x16, PC+8 */
        fixed.patched[page][word+1]=UINT32_C(0xd61f0200); /* BR x16 */
        memcpy(&fixed.patched[page][word+2],&callbacks[i],sizeof(uintptr_t));
        fixed.report.targets[i]=callbacks[i];fixed.report.absolute_entries++;
    }
    for (unsigned p=0;p<2;p++) for (unsigned i=0;i<TP_CODE_PAGE_WORDS;i++)
        if (fixed.original[p][i]!=fixed.patched[p][i]) fixed.report.changed_words++;
    if (fixed.report.changed_words!=TP_FIXED_LIBC_ENTRIES*4) goto failed;
    fixed.report.ready=true; fixed.report.lease_held=true;
    status=TP_FIXED_LIBC_OK;
    goto done;
failed:
    for (unsigned p=0;p<2;p++) tp_code_page_release(&fixed.pages[p],fixed.original[p],fixed.original[p]);
    if (fixed.lease) { dlclose(fixed.lease); fixed.lease=NULL; }
done:
    atomic_store(&fixed.busy,false);
    return status;
}

/* A single closed thread set protects all changed pages. All preimages are
 * checked before the first mutation. Any later uncertainty stops this process. */
__attribute__((noinline)) static int fixed_commit_batch(unsigned epoch,unsigned observed) {
    int changed=0;
    if (tp_code_page_validate(&fixed.updates[0]) && tp_code_page_validate(&fixed.updates[1])) {
        bool unsafe=false;
        for(unsigned thread=0;thread<observed;thread++){
            struct tp_park_context context;
            int context_status=tp_park_context(epoch,thread,&context);
            if(context_status!=TP_PARK_OK){
                fixed.report.context_status=context_status;fixed.report.context_index=(int)thread;
                changed=-2;goto resumed;
            }
            for(unsigned entry=0;entry<TP_FIXED_LIBC_ENTRIES;entry++){
                uintptr_t start=fixed.report.base+tp_libc_contract[entry].offset;
                // Entry+4 is safe in both images; its instruction has not yet
                // executed. Entry+8..+16 may hold a syscall continuation or a
                // loaded dispatch register. Never reinterpret that frame.
                if(context.pc>=start+8&&context.pc<start+20){
                    unsafe=true;
                    fixed.report.unsafe_tid=context.tid;fixed.report.unsafe_pc=context.pc;
                    fixed.report.unsafe_pc_counts[entry][(context.pc-start-8)/4]++;
                }
            }
        }
        if(unsafe){changed=-1;goto resumed;}
        for (unsigned i=0;i<2;i++) {
            if (!tp_code_page_commit(&fixed.updates[i])) {
                (void)tp_park_syscall(SYS_exit_group,195,0,0,0,0,0); __builtin_trap();
            }
            changed++;
        }
    }
resumed:
    if (tp_park_end(epoch)!=TP_PARK_OK) {
        (void)tp_park_syscall(SYS_exit_group,196,0,0,0,0,0); __builtin_trap();
    }
    return changed;
}

static enum tp_fixed_libc_status transition(bool enable) {
    bool expected=false;
    if (!atomic_compare_exchange_strong(&fixed.busy,&expected,true)) return TP_FIXED_LIBC_STATE;
    enum tp_fixed_libc_status status=TP_FIXED_LIBC_STATE;
    if (!fixed.report.ready || fixed.report.enabled==enable || (!enable && fixed.report.restorations) ||
        (enable && fixed.report.publications)) goto done;
    fixed.report.admission_attempts=0;fixed.report.admission_elapsed_ns=0;
    fixed.report.admission_outcome=TP_FIXED_LIBC_ADMISSION_NONE;
    fixed.report.context_status=TP_PARK_OK;fixed.report.context_index=-1;
    fixed.report.admission_enable=enable;
    fixed.report.unsafe_tid=0;fixed.report.unsafe_pc=0;
    memset(fixed.report.unsafe_pc_counts,0,sizeof(fixed.report.unsafe_pc_counts));
    unsigned prepared=0;
    status=TP_FIXED_LIBC_PAGE;
    for (;prepared<2;prepared++) {
        int result=enable ? tp_code_page_prepare(&fixed.pages[prepared],fixed.original[prepared],
                                                fixed.patched[prepared],&fixed.updates[prepared]) :
            tp_code_page_prepare_restore(&fixed.pages[prepared],fixed.patched[prepared],&fixed.updates[prepared]);
        if (result!=1) goto cancelled;
    }
#ifdef TP_SELF_HOOK_TESTING
    if (enable&&reject_next_batch) {
        reject_next_batch=false;
        fixed.updates[1].expected[0]^=1;
    }
#endif
    uint64_t start=tp_park_now();bool committed=false;
    if(!start){fixed.report.admission_outcome=TP_FIXED_LIBC_ADMISSION_CLOCK;goto cancelled;}
    for(unsigned attempt=0;attempt<64;attempt++){
        fixed.report.admission_attempts++;
        struct tp_park_snapshot snapshot={0};
        fixed.report.pause_status=tp_park_begin(&snapshot);fixed.report.pause=snapshot;
        if(fixed.report.pause_status!=TP_PARK_OK){
            uint64_t now=tp_park_now();
            if(now>=start)fixed.report.admission_elapsed_ns=now-start;
            fixed.report.admission_outcome=TP_FIXED_LIBC_ADMISSION_PAUSE;
            status=TP_FIXED_LIBC_PAUSE;goto cancelled;
        }
        int changed=fixed_commit_batch(snapshot.epoch,snapshot.observed);
        uint64_t now=tp_park_now();
        if(now>=start)fixed.report.admission_elapsed_ns=now-start;
        if(changed==2){fixed.report.admission_outcome=TP_FIXED_LIBC_ADMISSION_COMMITTED;committed=true;break;}
        if(changed==-2){fixed.report.admission_outcome=TP_FIXED_LIBC_ADMISSION_CONTEXT;status=TP_FIXED_LIBC_PAUSE;goto cancelled;}
        if(changed==0){fixed.report.admission_outcome=TP_FIXED_LIBC_ADMISSION_PREIMAGE;fixed.report.rejected_batches++;goto cancelled;}
        // No mutation occurred. Let old calls finish naturally before taking
        // another closed snapshot; no call is replayed and no PC is rewritten.
        fixed.report.quiescence_rejections++;status=TP_FIXED_LIBC_IN_FLIGHT;
        if(!now||now<start){fixed.report.admission_outcome=TP_FIXED_LIBC_ADMISSION_CLOCK;break;}
        if(now-start>=UINT64_C(1000000000)){fixed.report.admission_outcome=TP_FIXED_LIBC_ADMISSION_DEADLINE;break;}
        fixed.report.admission_outcome=TP_FIXED_LIBC_ADMISSION_ATTEMPTS;
        tp_park_sleep(100000);
    }
    if(!committed)goto cancelled;
    for (unsigned p=0;p<2;p++) tp_code_page_finish(&fixed.updates[p]);
    fixed.report.enabled=enable;
    if (enable) fixed.report.publications+=2; else fixed.report.restorations+=2;
    status=TP_FIXED_LIBC_OK;
    goto done;
cancelled:
    for (unsigned p=0;p<prepared;p++) tp_code_page_cancel(&fixed.updates[p]);
done:
    atomic_store(&fixed.busy,false);
    return status;
}
enum tp_fixed_libc_status tp_fixed_libc_activate(void) { return transition(true); }
enum tp_fixed_libc_status tp_fixed_libc_deactivate(void) { return transition(false); }
void tp_fixed_libc_report(struct tp_fixed_libc_report *report) { if (report) *report=fixed.report; }
