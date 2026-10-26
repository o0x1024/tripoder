package dev.tripoder.runtime;

/** An operation's durable ownership cannot be replaced with a current UI target. */
public final class RuntimeRecoveryIdentity {
    public static void requireScope(String recorded,String space,String pkg){
        if(!("space/"+space+"/package/"+pkg).equals(recorded))throw new SecurityException("LAUNCH_RECOVERY_BINDING_STALE");
    }
    private RuntimeRecoveryIdentity(){}
}
