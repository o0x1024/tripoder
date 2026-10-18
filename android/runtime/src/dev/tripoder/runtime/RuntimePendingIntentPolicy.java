package dev.tripoder.runtime;

/** Logical flags are part of token identity; platform normalization is a separate operation. */
final class RuntimePendingIntentPolicy {
    static final int ONE_SHOT=0x40000000,NO_CREATE=0x20000000,CANCEL_CURRENT=0x10000000,
        UPDATE_CURRENT=0x08000000,IMMUTABLE=0x04000000,MUTABLE=0x02000000,ALLOW_UNSAFE_IMPLICIT=0x01000000;
    static final int OPERATIONS=NO_CREATE|CANCEL_CURRENT|UPDATE_CURRENT;
    private static final int SUPPORTED=OPERATIONS|ONE_SHOT|IMMUTABLE|MUTABLE|ALLOW_UNSAFE_IMPLICIT;
    static void validate(int targetSdk,int flags){
        if((flags&~SUPPORTED)!=0)throw new UnsupportedOperationException("PENDING_FILL_IN_FLAGS_UNSUPPORTED");
        if((flags&(IMMUTABLE|MUTABLE))==(IMMUTABLE|MUTABLE))throw new IllegalArgumentException("PENDING_MUTABILITY_CONFLICT");
        if(targetSdk>=31&&(flags&(IMMUTABLE|MUTABLE))==0)throw new IllegalArgumentException("PENDING_EXPLICIT_MUTABILITY_REQUIRED");
    }
    static int identity(int flags){return flags&~OPERATIONS;}
    static void validateDestination(int targetSdk,int flags,boolean component,boolean pkg){
        validate(targetSdk,flags);
        if(!component&&!pkg&&targetSdk>=34&&!immutable(flags)&&(flags&(ALLOW_UNSAFE_IMPLICIT|NO_CREATE))==0)throw new IllegalArgumentException("PENDING_UNSAFE_IMPLICIT_MUTABLE");
    }
    static int physical(int flags){
        // A target-30 token without an explicit bit is mutable. This bit is only
        // for the host's system token; it never changes the guest identity key.
        return (flags&(IMMUTABLE|MUTABLE))==0?flags|MUTABLE:flags;
    }
    static boolean immutable(int flags){return (flags&IMMUTABLE)!=0;}
    private RuntimePendingIntentPolicy(){}
}
