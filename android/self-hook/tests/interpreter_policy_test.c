#include "art_interpreter_policy.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned checks,validations,reject_validation,deopts;
static int current_level,next_level;
static void *const identity=(void *)(uintptr_t)0x200000;
static void check(bool ok){checks++;if(!ok){fprintf(stderr,"policy check %u failed\n",checks);abort();}}
int tp_art_validate_code(const struct tp_art_access *access,uintptr_t rva,const void *bytes,unsigned size){
    check(access->base==0x100000&&rva&&bytes&&size);validations++;return validations!=reject_validation;
}
static int level(void *value){check(value==identity);return current_level;}
static void deopt(void *value,const char *client){
    check(value==identity&&strcmp(client,"tripoder-managed-process-interpreter")==0);deopts++;current_level=next_level;
}
static struct tp_art_interpreter_policy fixture(void){
    struct tp_art_interpreter_policy policy={.bound=true,.instrumentation=identity,.level=level,.deopt_all=deopt};
    atomic_init(&policy.claimed,false);atomic_init(&policy.observed_level,-1);atomic_init(&policy.claims,0);return policy;
}
int main(void){
    struct tp_art_deopt_access art={.managed.access.base=0x100000,.instrumentation=identity};
    struct tp_art_interpreter_policy unbound={0};
    check(!tp_art_interpreter_claim_locked(NULL));check(!tp_art_interpreter_claim_locked(&unbound));
    check(!tp_art_interpreter_bind(NULL,&unbound));check(!tp_art_interpreter_bind(&art,NULL));
    reject_validation=2;check(!tp_art_interpreter_bind(&art,&unbound));check(!unbound.bound&&deopts==0);
    reject_validation=0;validations=0;check(tp_art_interpreter_bind(&art,&unbound));
    check(validations==3&&unbound.bound&&!unbound.claimed&&unbound.observed_level==-1&&unbound.claims==0&&deopts==0);
    check(!tp_art_interpreter_bind(&art,&unbound));
    for(int foreign=1;foreign<=2;foreign++){
        struct tp_art_interpreter_policy policy=fixture();current_level=foreign;deopts=0;
        check(!tp_art_interpreter_claim_locked(&policy));check(!policy.claimed&&policy.claims==0&&deopts==0&&policy.observed_level==foreign);
    }
    struct tp_art_interpreter_policy failed=fixture();current_level=0;next_level=1;deopts=0;
    check(!tp_art_interpreter_claim_locked(&failed));check(!failed.claimed&&failed.claims==0&&deopts==1&&failed.observed_level==1);
    struct tp_art_interpreter_policy owned=fixture();current_level=0;next_level=2;deopts=0;
    check(tp_art_interpreter_claim_locked(&owned));check(owned.claimed&&owned.claims==1&&owned.observed_level==2&&deopts==1);
    for(unsigned i=0;i<256;i++)check(tp_art_interpreter_claim_locked(&owned));
    check(owned.claimed&&owned.claims==1&&deopts==1);
    current_level=0;check(!tp_art_interpreter_claim_locked(&owned));check(owned.claimed&&owned.claims==1&&deopts==1);
    puts("{\"passed\":true,\"claims\":1,\"repeatValidations\":256,\"foreignOwnersRejected\":2}");
    return 0;
}
