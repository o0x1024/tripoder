#define _GNU_SOURCE
#include "linker_notifier.h"
#include "linker_contract.h"
#include "near_notifier.h"
#include "../code_page.h"
#include "../park_syscall.h"
#include "tripoder_a64.h"
#include "tripoder_thread_park.h"
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <stddef.h>
#include <string.h>

_Static_assert(sizeof(struct r_debug)==40&&offsetof(struct r_debug,r_map)==8
    &&offsetof(struct r_debug,r_brk)==16&&offsetof(struct r_debug,r_state)==24,"NDK r_debug ABI");
_Static_assert(sizeof(struct link_map)==40&&offsetof(struct link_map,l_next)==24
    &&offsetof(struct link_map,l_prev)==32,"NDK link_map ABI");
static struct {
    char paths[TP_MODULE_LIMIT][TP_MODULE_PATH_LIMIT];
    unsigned lengths[TP_MODULE_LIMIT],count;
    struct tp_module_slot slots[TP_MODULE_LIMIT];
    _Atomic bool ambiguous[TP_MODULE_LIMIT];
    /* Serialized notifier scratch belongs to the observer, not a possibly
     * small guest pthread stack. No heap operation occurs in this domain. */
    volatile struct tp_module_seen seen[TP_MODULE_LIMIT],retained[TP_MODULE_LIMIT];
    volatile uintptr_t previous_maps[TP_MODULE_LIMIT];
    volatile unsigned matches[TP_MODULE_LIMIT];
    struct r_debug *debug;
    struct tp_code_page page;
    uint32_t patched[TP_CODE_PAGE_WORDS];
    struct tp_near_notifier relay;
    struct tp_linker_report report;
    uintptr_t dt_debug;
    _Atomic uint64_t notifications,consistent,added,removed;
    _Atomic uint64_t scan_epoch;
    struct { _Atomic uint64_t sequence;_Atomic unsigned index,generation,kind;_Atomic uintptr_t base; } events[TP_MODULE_EVENT_LIMIT];
    _Atomic uint64_t event_serial;
    _Atomic bool installed;
    atomic_flag scanning,installing;
    bool attempted,debug_conflict;
} observer={.scanning=ATOMIC_FLAG_INIT,.installing=ATOMIC_FLAG_INIT};

static __attribute__((noreturn)) void tp_linker_fail_stop(int reason){
    (void)tp_park_syscall(SYS_exit_group,reason,0,0,0,0,0);__builtin_trap();
}
__attribute__((noinline)) static void tp_linker_record(unsigned index,uint32_t generation,uintptr_t base,unsigned kind){
    uint64_t previous=atomic_load_explicit(&observer.event_serial,memory_order_seq_cst);if(previous==UINT64_MAX)tp_linker_fail_stop(199);
    uint64_t number=previous+1;unsigned slot=(unsigned)(previous%TP_MODULE_EVENT_LIMIT);
    /* SC fields and a double-checked commit sequence prevent a reader from
     * assembling a record across ring reuse. The notifier is the sole writer. */
    atomic_store_explicit(&observer.events[slot].sequence,0,memory_order_seq_cst);
    atomic_store_explicit(&observer.events[slot].index,index,memory_order_seq_cst);
    atomic_store_explicit(&observer.events[slot].generation,generation,memory_order_seq_cst);
    atomic_store_explicit(&observer.events[slot].base,base,memory_order_seq_cst);
    atomic_store_explicit(&observer.events[slot].kind,kind,memory_order_seq_cst);
    atomic_store_explicit(&observer.events[slot].sequence,number,memory_order_seq_cst);
    atomic_store_explicit(&observer.event_serial,number,memory_order_seq_cst);
}
/* All access to linker-owned pointers is restricted to its synchronous
 * notifier or a successfully parked initial snapshot. No Java, allocator,
 * stdio, TLS, dl* API or mutex is called anywhere in this execution domain. */
__attribute__((noinline,no_stack_protector)) static bool tp_linker_scan_consistent(void){
    if(observer.debug->r_version!=1||observer.debug->r_state!=RT_CONSISTENT)return false;
    volatile struct tp_module_seen *seen=observer.seen,*retained=observer.retained;
    volatile uintptr_t *previous_maps=observer.previous_maps;volatile unsigned *matches=observer.matches;
    for(unsigned i=0;i<observer.count;i++){
        seen[i].map=0;seen[i].base=0;retained[i].map=0;retained[i].base=0;matches[i]=0;
        previous_maps[i]=atomic_load_explicit(&observer.slots[i].map,memory_order_relaxed);
    }
    struct link_map *previous=NULL,*node=observer.debug->r_map;
    unsigned nodes=0;
    while(node){
        if(++nodes>4096||((uintptr_t)node&7u)||node->l_prev!=previous||!node->l_name)return false;
        const volatile char *name=node->l_name;unsigned length=0;
        while(length<4096&&name[length])length++;
        if(length==4096)return false;
        for(unsigned i=0;i<observer.count;i++){
            if(length!=observer.lengths[i])continue;
            unsigned at=0;while(at<length&&name[at]==observer.paths[i][at])at++;
            if(at!=length)continue;
            if(!node->l_addr||(node->l_addr&4095u))return false;
            matches[i]++;
            if(!seen[i].map){seen[i].map=(uintptr_t)node;seen[i].base=node->l_addr;}
            if((uintptr_t)node==previous_maps[i]){retained[i].map=(uintptr_t)node;retained[i].base=node->l_addr;}
        }
        previous=node;node=node->l_next;
    }
    if(!nodes)return false;
    for(unsigned i=0;i<observer.count;i++){
        struct tp_module_snapshot old;
        if(!tp_module_snapshot(&observer.slots[i],&old))return false;
        struct tp_module_seen value=retained[i].map?(struct tp_module_seen){retained[i].map,retained[i].base}:(struct tp_module_seen){seen[i].map,seen[i].base};
        /* More than one instance makes new selection ambiguous. Keep an
         * existing primary identity while it remains mapped, so an already
         * owned callback cannot silently switch to the other namespace. */
        bool was_ambiguous=atomic_exchange_explicit(&observer.ambiguous[i],matches[i]>1,memory_order_acq_rel);
        if(old.live&&value.map&&value.map!=old.map){
            enum tp_module_change removed=tp_module_reconcile(&observer.slots[i],(struct tp_module_seen){0,0});
            if(removed==TP_MODULE_CHANGE_IN_USE)tp_linker_fail_stop(198);
            if(removed!=TP_MODULE_CHANGE_OK)return false;
            atomic_fetch_add_explicit(&observer.removed,1,memory_order_relaxed);old.live=false;
            tp_linker_record(i,old.generation,old.base,TP_MODULE_REMOVED);
        }
        enum tp_module_change status=tp_module_reconcile(&observer.slots[i],value);
        if(status==TP_MODULE_CHANGE_IN_USE)tp_linker_fail_stop(198);
        if(status!=TP_MODULE_CHANGE_OK)return false;
        if(!old.live&&value.map){atomic_fetch_add_explicit(&observer.added,1,memory_order_relaxed);tp_linker_record(i,old.generation+1,value.base,TP_MODULE_ADDED);}
        if(old.live&&!value.map){atomic_fetch_add_explicit(&observer.removed,1,memory_order_relaxed);tp_linker_record(i,old.generation,old.base,TP_MODULE_REMOVED);}
        if(value.map&&was_ambiguous!=(matches[i]>1))tp_linker_record(i,old.live?old.generation:old.generation+1,value.base,matches[i]>1?TP_MODULE_AMBIGUOUS:TP_MODULE_UNIQUE);
    }
    return true;
}
__attribute__((noinline,no_stack_protector)) static void tp_linker_notify(void){
    if(atomic_flag_test_and_set_explicit(&observer.scanning,memory_order_acquire))tp_linker_fail_stop(199);
    uint64_t epoch=atomic_fetch_add_explicit(&observer.scan_epoch,1,memory_order_acq_rel);
    if((epoch&1)||epoch>=UINT64_MAX-1)tp_linker_fail_stop(199);
    atomic_fetch_add_explicit(&observer.notifications,1,memory_order_relaxed);
    int state=observer.debug->r_state;
    if(state==RT_CONSISTENT){
        if(!tp_linker_scan_consistent())tp_linker_fail_stop(200);
        atomic_fetch_add_explicit(&observer.consistent,1,memory_order_relaxed);
    }else if(state!=RT_ADD&&state!=RT_DELETE)tp_linker_fail_stop(201);
    atomic_fetch_add_explicit(&observer.scan_epoch,1,memory_order_release);
    atomic_flag_clear_explicit(&observer.scanning,memory_order_release);
}
static bool build_id(const struct dl_phdr_info *info,const ElfW(Phdr) *header){
    if(header->p_type!=PT_NOTE||header->p_memsz>65536)return false;
    const unsigned char *at=(const void *)(info->dlpi_addr+header->p_vaddr);size_t remaining=header->p_memsz;
    while(remaining>=sizeof(ElfW(Nhdr))){
        ElfW(Nhdr) note;memcpy(&note,at,sizeof(note));
        size_t names=((size_t)note.n_namesz+3)&~(size_t)3,desc=((size_t)note.n_descsz+3)&~(size_t)3;
        if(names>remaining-sizeof(note)||desc>remaining-sizeof(note)-names)return false;
        if(note.n_type==NT_GNU_BUILD_ID&&note.n_namesz==4&&note.n_descsz==sizeof(tp_linker_build_id)
            &&!memcmp(at+sizeof(note),"GNU",4)&&!memcmp(at+sizeof(note)+names,tp_linker_build_id,sizeof(tp_linker_build_id)))return true;
        size_t used=sizeof(note)+names+desc;at+=used;remaining-=used;
    }
    return false;
}
static int find_image(struct dl_phdr_info *info,size_t size,void *unused){
    (void)size;(void)unused;bool match=!strcmp(info->dlpi_name,TP_LINKER_LOADED_NAME),identity=false,code=false,data=false;
    if(match){size_t n=strnlen(info->dlpi_name,sizeof(observer.report.loader_name)-1);memcpy(observer.report.loader_name,info->dlpi_name,n);observer.report.loader_name[n]=0;}
    if(info->dlpi_phnum>1024)return 1;
    for(unsigned i=0;i<info->dlpi_phnum;i++){
        const ElfW(Phdr) *p=&info->dlpi_phdr[i];
        if(match){
            identity|=build_id(info,p);
            if(p->p_type==PT_LOAD&&p->p_vaddr<=TP_LINKER_PAGE_RVA&&p->p_memsz>=4096
                &&TP_LINKER_PAGE_RVA-p->p_vaddr<=p->p_memsz-4096&&(p->p_flags&(PF_R|PF_W|PF_X))==(PF_R|PF_X))code=true;
            if(p->p_type==PT_LOAD&&p->p_vaddr<=TP_LINKER_DEBUG_RVA&&p->p_memsz>=sizeof(struct r_debug)
                &&TP_LINKER_DEBUG_RVA-p->p_vaddr<=p->p_memsz-sizeof(struct r_debug)&&(p->p_flags&(PF_R|PF_W|PF_X))==(PF_R|PF_W))data=true;
        }
        /* Locate the executable's real DT_DEBUG. The first phdr item may be
         * the linker itself on this platform; enumeration position is not an
         * executable identity. All nonzero DT_DEBUG values must agree. */
        if(p->p_type==PT_DYNAMIC&&p->p_memsz<=65536){
            const ElfW(Dyn) *dynamic=(const void *)(info->dlpi_addr+p->p_vaddr);
            for(size_t n=0;n<p->p_memsz/sizeof(*dynamic)&&dynamic[n].d_tag!=DT_NULL;n++){
                if(dynamic[n].d_tag!=DT_DEBUG||!dynamic[n].d_un.d_ptr)continue;
                uintptr_t value=dynamic[n].d_un.d_ptr;
                if(observer.dt_debug&&observer.dt_debug!=value)observer.debug_conflict=true;
                observer.dt_debug=value;
            }
        }
    }
    if(match&&identity&&code&&data)observer.report.base=info->dlpi_addr;
    if(match)observer.report.identity_flags=(identity?1u:0u)|(code?2u:0u)|(data?4u:0u)|8u;
    return 0;
}
__attribute__((noinline)) static bool tp_linker_install_parked(struct tp_code_page_update *update,unsigned epoch){
    bool ok=observer.debug->r_brk==observer.report.notifier&&tp_linker_scan_consistent();
    if(ok)ok=tp_code_page_commit(update)==1;
    if(ok)atomic_store_explicit(&observer.installed,true,memory_order_release);
    if(tp_park_end(epoch)!=TP_PARK_OK)tp_linker_fail_stop(202);
    return ok;
}
bool tp_linker_install(const char *const *paths,unsigned count){
    if(atomic_flag_test_and_set_explicit(&observer.installing,memory_order_acquire))return false;
    bool ok=false;observer.report.stage="ALLOWLIST";
    if(observer.attempted||(count&&!paths)||count>TP_MODULE_LIMIT)goto done;observer.attempted=true;
    for(unsigned i=0;i<count;i++){
        if(!paths[i]||paths[i][0]!='/')goto done;size_t length=strnlen(paths[i],TP_MODULE_PATH_LIMIT);
        if(!length||length==TP_MODULE_PATH_LIMIT)goto done;
        for(unsigned j=0;j<i;j++)if(!strcmp(paths[i],paths[j]))goto done;
        memcpy(observer.paths[i],paths[i],length+1);observer.lengths[i]=(unsigned)length;
    }
    observer.count=count;observer.report.count=count;observer.report.stage="LINKER_IDENTITY";
    dl_iterate_phdr(find_image,NULL);uintptr_t base=observer.report.base;
    if(!base||observer.debug_conflict||observer.dt_debug!=base+TP_LINKER_DEBUG_RVA)goto done;
    observer.debug=(void *)observer.dt_debug;observer.report.debug=observer.dt_debug;observer.report.notifier=base+TP_LINKER_NOTIFIER_RVA;
    if(memcmp((void *)(base+TP_LINKER_PAGE_RVA),tp_linker_page,sizeof(tp_linker_page)))goto done;
    if(tp_park_prepare_process()!=TP_PARK_OK)goto done;
    observer.report.stage="NOTIFIER_RELAY";
    if(!tp_near_notifier_prepare(observer.report.notifier,tp_linker_notify,&observer.relay)){
        observer.report.stage=observer.relay.stage;observer.report.error=observer.relay.error;goto done;}
    observer.report.relay=observer.relay.entry;observer.report.seals=observer.relay.seals;observer.report.fde_pcs=observer.relay.fde_pcs;
    observer.report.stage="NOTIFIER_PAGE";
    if(!tp_code_page_claim(&observer.page,(void *)(base+TP_LINKER_PAGE_RVA),TP_LINKER_PATH,tp_linker_page))goto done;
    memcpy(observer.patched,tp_linker_page,sizeof(observer.patched));
    unsigned word=(TP_LINKER_NOTIFIER_RVA-TP_LINKER_PAGE_RVA+4)/4;
    if(tp_a64_branch(observer.report.notifier+4,observer.relay.entry,&observer.patched[word])!=TP_A64_OK)goto done;
    struct tp_code_page_update update={0};
    if(tp_code_page_prepare(&observer.page,tp_linker_page,observer.patched,&update)!=1)goto done;
    observer.report.stage="INITIAL_CONSISTENT_SNAPSHOT_AND_PUBLICATION";struct tp_park_snapshot parked;
    if(tp_park_begin(&parked)!=TP_PARK_OK){tp_code_page_cancel(&update);goto done;}
    ok=tp_linker_install_parked(&update,parked.epoch);
    if(ok){tp_code_page_finish(&update);observer.report.parked=parked.parked;observer.report.stage="INSTALLED";}
    else tp_code_page_cancel(&update);
done:
    atomic_flag_clear_explicit(&observer.installing,memory_order_release);return ok;
}
bool tp_linker_snapshot(unsigned index,struct tp_module_snapshot *out){
    uint64_t before=atomic_load_explicit(&observer.scan_epoch,memory_order_acquire);if(before&1)return false;
    if(!atomic_load_explicit(&observer.installed,memory_order_acquire)||index>=observer.count||!tp_module_snapshot(&observer.slots[index],out))return false;
    out->ambiguous=atomic_load_explicit(&observer.ambiguous[index],memory_order_acquire);
    return before==atomic_load_explicit(&observer.scan_epoch,memory_order_acquire);
}
bool tp_linker_publication_ready(unsigned index,uint32_t generation){
    struct tp_module_snapshot snapshot;
    return atomic_load_explicit(&observer.installed,memory_order_acquire)
        &&((const volatile struct r_debug *)observer.debug)->r_state==RT_CONSISTENT
        &&tp_linker_snapshot(index,&snapshot)&&snapshot.live&&!snapshot.ambiguous&&snapshot.generation==generation;
}
bool tp_linker_acquire(unsigned index,uint32_t generation){
    return atomic_load_explicit(&observer.installed,memory_order_acquire)&&index<observer.count&&tp_module_acquire(&observer.slots[index],generation);
}
_Atomic uint64_t *tp_linker_admission_state(unsigned index,uint32_t generation){
    struct tp_module_snapshot snapshot;
    if(!tp_linker_snapshot(index,&snapshot)||!snapshot.live||snapshot.generation!=generation)return NULL;
    return &observer.slots[index].state;
}
bool tp_linker_release(unsigned index,uint32_t generation){
    return atomic_load_explicit(&observer.installed,memory_order_acquire)&&index<observer.count&&tp_module_release(&observer.slots[index],generation);
}
void tp_linker_report(struct tp_linker_report *out){
    if(!out)return;*out=observer.report;out->installed=atomic_load_explicit(&observer.installed,memory_order_acquire);
    out->notifications=atomic_load_explicit(&observer.notifications,memory_order_relaxed);
    out->consistent=atomic_load_explicit(&observer.consistent,memory_order_relaxed);
    out->added=atomic_load_explicit(&observer.added,memory_order_relaxed);out->removed=atomic_load_explicit(&observer.removed,memory_order_relaxed);
}
bool tp_linker_events(struct tp_module_event output[static TP_MODULE_EVENT_LIMIT],unsigned *count){
    if(!output||!count||!atomic_load_explicit(&observer.installed,memory_order_acquire))return false;
    uint64_t end=atomic_load_explicit(&observer.event_serial,memory_order_seq_cst),start=end>=TP_MODULE_EVENT_LIMIT?end-TP_MODULE_EVENT_LIMIT+1:1;
    unsigned copied=0;
    for(uint64_t number=start;number<=end&&number;number++){
        unsigned slot=(unsigned)((number-1)%TP_MODULE_EVENT_LIMIT);
        if(atomic_load_explicit(&observer.events[slot].sequence,memory_order_seq_cst)!=number)return false;
        struct tp_module_event value={.sequence=number,
            .index=atomic_load_explicit(&observer.events[slot].index,memory_order_seq_cst),
            .generation=atomic_load_explicit(&observer.events[slot].generation,memory_order_seq_cst),
            .base=atomic_load_explicit(&observer.events[slot].base,memory_order_seq_cst),
            .kind=atomic_load_explicit(&observer.events[slot].kind,memory_order_seq_cst)};
        if(atomic_load_explicit(&observer.events[slot].sequence,memory_order_seq_cst)!=number)return false;output[copied++]=value;
    }
    *count=copied;return true;
}
