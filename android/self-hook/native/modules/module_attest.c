#define _GNU_SOURCE
#include "linker_notifier.h"
#include "../abi/native_prefix.h"
#include <jni.h>
#include <link.h>
#include <string.h>
struct attestation {unsigned index;uint32_t generation;uintptr_t rva;unsigned char build[64];unsigned length;bool valid,prefix;};
static bool readable(const struct dl_phdr_info *info,uintptr_t rva,size_t size){
    for(unsigned i=0;i<info->dlpi_phnum;i++){
        const ElfW(Phdr) *p=&info->dlpi_phdr[i];
        if(p->p_type==PT_LOAD&&(p->p_flags&PF_R)&&p->p_memsz>=size&&rva>=p->p_vaddr&&rva-p->p_vaddr<=p->p_memsz-size)return true;
    }return false;
}
static int inspect(struct dl_phdr_info *info,size_t size,void *opaque){
    (void)size;struct attestation *check=opaque;struct tp_module_snapshot current;
    if(!tp_linker_snapshot(check->index,&current)||!current.live||current.ambiguous||current.generation!=check->generation)return 0;
    if(info->dlpi_addr!=current.base)return 0;
    bool executable=check->rva==0;unsigned matches=0,available=0;
    if(info->dlpi_phnum>1024)return 1;
    for(unsigned i=0;i<info->dlpi_phnum;i++){
        const ElfW(Phdr) *p=&info->dlpi_phdr[i];
        if(p->p_type==PT_LOAD&&(p->p_flags&(PF_R|PF_W|PF_X))==(PF_R|PF_X)&&p->p_memsz>=4
            &&check->rva>=p->p_vaddr&&check->rva-p->p_vaddr<=p->p_memsz-4){executable=true;available=p->p_memsz-(check->rva-p->p_vaddr)>=8?2u:1u;}
        if(p->p_type!=PT_NOTE)continue;
        if(p->p_memsz>65536||!readable(info,p->p_vaddr,p->p_memsz))return 1;
        const unsigned char *at=(void *)(info->dlpi_addr+p->p_vaddr);size_t remaining=p->p_memsz;
        while(remaining>=sizeof(ElfW(Nhdr))){
            ElfW(Nhdr) note;memcpy(&note,at,sizeof(note));size_t names=((size_t)note.n_namesz+3)&~(size_t)3,desc=((size_t)note.n_descsz+3)&~(size_t)3;
            if(names>remaining-sizeof(note)||desc>remaining-sizeof(note)-names)return 1;
            if(note.n_type==NT_GNU_BUILD_ID&&note.n_namesz==4&&!memcmp(at+sizeof(note),"GNU",4)){
                if(note.n_descsz!=check->length||memcmp(at+sizeof(note)+names,check->build,check->length))return 1;matches++;
            }
            size_t consumed=sizeof(note)+names+desc;at+=consumed;remaining-=consumed;
        }
    }
    check->valid=matches==1&&executable;
    if(check->valid&&check->prefix)check->valid=check->rva&&!(check->rva&3u)
        &&tp_native_prefix_supported((const void *)(info->dlpi_addr+check->rva),available,info->dlpi_addr+check->rva);
    return 1;
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhook_NativeModules_attest0(JNIEnv *env,jclass type,jint index,jlong generation,jlong rva,jbyteArray expected,jboolean prefix){
    (void)type;if(index<0||generation<=0||(uint64_t)generation>UINT32_MAX||rva<0||!expected)return false;
    jsize length=(*env)->GetArrayLength(env,expected);if(length<4||length>64)return false;
    struct attestation check={.index=(unsigned)index,.generation=(uint32_t)generation,.rva=(uintptr_t)rva,.length=(unsigned)length,.prefix=prefix};
    (*env)->GetByteArrayRegion(env,expected,0,length,(jbyte *)check.build);if((*env)->ExceptionCheck(env))return false;
    /* The real loader lock covers the entire phdr/note read. A concurrent
     * removal before this callback yields no match; no stale base is read and
     * read-only observation does not acquire a fail-stop publication lease. */
    dl_iterate_phdr(inspect,&check);return check.valid;
}
