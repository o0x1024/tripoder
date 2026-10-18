package dev.tripoder.runtime;

/** Android mutability rules are evaluated against the verified guest SDK, not the host. */
public final class PendingIntentPolicyTest {
    private static int checks;
    private static void check(boolean ok){checks++;if(!ok)throw new AssertionError("pending-policy-"+checks);}
    private static void reject(int sdk,int flags,Class<? extends Throwable> type){try{RuntimePendingIntentPolicy.validate(sdk,flags);throw new AssertionError("accepted invalid flags");}catch(Throwable failure){check(type.isInstance(failure));}}
    public static void main(String[] args){
        int immutable=0x04000000,mutable=0x02000000;
        for(int operations:new int[]{0,0x20000000,0x10000000,0x08000000,0x30000000,0x18000000}){
            RuntimePendingIntentPolicy.validate(30,operations);check(RuntimePendingIntentPolicy.identity(operations)==0);
            check(RuntimePendingIntentPolicy.physical(operations)==(operations|mutable));
            reject(31,operations,IllegalArgumentException.class);
            for(int flag:new int[]{immutable,mutable,immutable|0x40000000,mutable|0x40000000}){
                RuntimePendingIntentPolicy.validate(31,operations|flag);check(RuntimePendingIntentPolicy.identity(operations|flag)==flag);
                check(RuntimePendingIntentPolicy.physical(operations|flag)==(operations|flag));
            }
            reject(30,operations|immutable|mutable,IllegalArgumentException.class);
        }
        for(int bit=0;bit<24;bit++)reject(30,mutable|(1<<bit),UnsupportedOperationException.class);
        reject(31,mutable|Integer.MIN_VALUE,UnsupportedOperationException.class);
        check(RuntimePendingIntentPolicy.immutable(immutable));check(!RuntimePendingIntentPolicy.immutable(0));check(!RuntimePendingIntentPolicy.immutable(mutable));
        for(int sdk:new int[]{30,31,33,34})for(int flags:new int[]{immutable,mutable,mutable|0x01000000,mutable|0x20000000})for(int destination=0;destination<3;destination++){
            boolean shouldReject=sdk>=34&&flags==mutable&&destination==0;
            try{RuntimePendingIntentPolicy.validateDestination(sdk,flags,destination==1,destination==2);check(!shouldReject);}catch(IllegalArgumentException rejected){check(shouldReject);}
        }
        System.out.println("PendingIntent policy: "+checks+" checks passed");
    }
}
