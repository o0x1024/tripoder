package dev.tripoder.runtime;

/** A fresh admitted request does not replay an old component's Hook execution. */
final class ServiceRecoveryPolicy {
    enum Admission { NEW_REQUEST, SYSTEM_RECOVERY }
    static void require(boolean closed,boolean dead,boolean hooked,Admission admission){
        if(closed)throw new IllegalStateException("PROCESS_CLOSING");
        if(admission==Admission.SYSTEM_RECOVERY&&hooked)throw new SecurityException("HOOK_COMPONENT_RECOVERY_REQUIRES_EXPLICIT_START");
        if(!dead)throw new SecurityException("SERVICE_PREVIOUS_PROCESS_NOT_DEAD");
    }
    private ServiceRecoveryPolicy(){}
}
