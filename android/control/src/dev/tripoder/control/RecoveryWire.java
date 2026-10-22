package dev.tripoder.control;

/** Lifecycle-only protocol. No business mutation, request queue, or replay endpoint. */
public final class RecoveryWire {
    public static final String SERVICE="dev.tripoder.runtime.EngineRecoveryService";
    public static final String LIFECYCLE="dev.tripoder.RECOVERY_LIFECYCLE";
    public static final int STATUS=1,ACTIVATE=2,RETRY_ONCE=3,STOP=4,INITIALIZE=5,ABORT=6,HEALTH=7;
    private RecoveryWire(){}
}
