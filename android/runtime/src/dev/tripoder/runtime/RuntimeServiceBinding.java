package dev.tripoder.runtime;

import android.content.Context;

/** Platform scheduling flags that retain the same ordinary bound Service identity. */
final class RuntimeServiceBinding {
    private static final int FLAGS=Context.BIND_AUTO_CREATE|Context.BIND_DEBUG_UNBIND|
        Context.BIND_NOT_FOREGROUND|Context.BIND_ABOVE_CLIENT|Context.BIND_ALLOW_OOM_MANAGEMENT|
        Context.BIND_WAIVE_PRIORITY|Context.BIND_IMPORTANT|Context.BIND_ADJUST_WITH_ACTIVITY|
        Context.BIND_NOT_PERCEPTIBLE;
    static void validate(int flags){
        // Non-creating binds use a durable PASSIVE component owner, without a session.
        // Capability delegation and external/isolated Services are separate contracts.
        if((flags&~FLAGS)!=0)
            throw new IllegalArgumentException("SERVICE_BIND_FLAGS_UNSUPPORTED:0x"+Integer.toHexString(flags));
    }
    private RuntimeServiceBinding(){}
}
