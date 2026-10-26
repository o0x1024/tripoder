package dev.tripoder.runtime;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Process-local installation admission. This does not own Broker operations or guest state. */
final class RuntimeSelfHookBootstrap {
    static final RuntimeSelfHookBootstrap PROCESS=new RuntimeSelfHookBootstrap();
    /** Fixed order: no guest code may run until every required installer returns. */
    enum Capability {
        PROCESS_EXECUTION_POLICY,
        FRAMEWORK_ACCESS,
        APPLICATION_OWNERSHIP,
        REFLECTION_VIEW,
        ACTIVITY_CREATION,
        ACTIVITY_DELIVERY,
        ACTIVITY_ROUTING,
        SERVICES,
        PROVIDERS,
        RECEIVERS,
        WIDGETS,
        PACKAGES,
        STORAGE,
        ACTIVITY_MANAGER,
        CONNECTIVITY,
        WIFI_P2P,
        NETWORK_STATS,
        TELEPHONY,
        AUDIO,
        BATTERY,
        POWER,
        NOTIFICATIONS,
        SHORTCUTS,
        JOBS,
        LOCATION,
        PENDING_INTENTS,
        UI_EFFECTS,
        FAILURE_DIAGNOSTICS,
        HOOK_CONTROL
    }

    @FunctionalInterface interface Installer { void install() throws Exception; }

    private enum Phase { NEW, INSTALLING, COMPLETE, READY, FAILED }
    private Phase phase=Phase.NEW;
    private Thread installerThread;
    private Throwable failure;
    private Capability installing;
    private int installed;

    /** Validate the entire fixed plan before its first external side effect. */
    void install(Map<Capability,Installer> supplied, Runnable publishReady) throws Exception {
        synchronized(this) {
            if(phase!=Phase.NEW)throw new IllegalStateException("SELF_HOOK_BOOTSTRAP_ALREADY_ATTEMPTED:"+phase,failure);
            installerThread=Thread.currentThread();
            phase=Phase.INSTALLING;
        }
        try {
            Objects.requireNonNull(supplied,"SELF_HOOK_INSTALLERS_REQUIRED");
            Objects.requireNonNull(publishReady,"SELF_HOOK_READY_PUBLICATION_REQUIRED");
            EnumMap<Capability,Installer> plan=new EnumMap<>(Capability.class);
            plan.putAll(supplied);
            for(Capability capability:Capability.values())
                if(plan.get(capability)==null)throw new IllegalStateException("SELF_HOOK_INSTALLER_MISSING:"+capability);
            if(plan.size()!=Capability.values().length)throw new IllegalStateException("SELF_HOOK_INSTALLER_SET_MISMATCH");
            for(Capability capability:Capability.values()) {
                synchronized(this){installing=capability;}
                plan.get(capability).install();
                synchronized(this){installed++;}
            }
            synchronized(this){installing=null;phase=Phase.COMPLETE;}
            publishReady.run();
            synchronized(this){phase=Phase.READY;}
        } catch(Exception|Error error) {
            synchronized(this){failure=error;phase=Phase.FAILED;}
            throw error;
        }
    }

    /** Called only by the final publication callback, on the installation thread. */
    synchronized void requireComplete() {
        if(phase!=Phase.COMPLETE||Thread.currentThread()!=installerThread||installed!=Capability.values().length)
            throw new IllegalStateException("SELF_HOOK_BOOTSTRAP_NOT_COMPLETE:"+phase+":"+installing,failure);
    }

    synchronized String status() {
        return phase+":"+installed+"/"+Capability.values().length+(installing==null?"":":"+installing);
    }
}
