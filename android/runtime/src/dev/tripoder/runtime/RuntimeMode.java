package dev.tripoder.runtime;

/**
 * How a guest process routes the framework subsystems. Chosen for a guest when its slot process is started and fixed for
 * that process's lifetime: hooks are installed once per process, so the mode cannot change while it runs. Nothing here
 * depends on the Android framework, so the parsing rules are host-testable.
 *
 * <p>See docs/thin-shim-transformation.md for the dual-mode design and the step-by-step rollout (this is step S1).
 */
enum RuntimeMode {
    /** The original implementation: sealed artifacts, central broker accounting, deep identity concealment. */
    THICK("thick"),
    /** Minimal interception: rewrite identity arguments and pass through to the real framework. */
    THIN("thin");

    /** Key of the mode in the broker-to-slot load bundle. */
    static final String KEY="mode";
    /** What a guest gets when nothing says otherwise. Switched to THIN after census verification (docs step S10). */
    static final RuntimeMode DEFAULT=THIN;
    final String wire;
    RuntimeMode(String wire){this.wire=wire;}

    /** An absent value is the default (older senders); an unknown value is rejected rather than degraded to another mode. */
    static RuntimeMode parse(String value){
        if(value==null)return DEFAULT;
        for(RuntimeMode mode:values())if(mode.wire.equals(value))return mode;
        throw new IllegalArgumentException("RUNTIME_MODE_UNKNOWN:"+(value.length()>32?value.substring(0,32):value));
    }
    /** Whether this mode is runnable in slot processes (THIN subsystems roll out from S5 onward). */
    boolean implemented(){return this==THICK||this==THIN;}
    /** Fail closed: never run a process labelled with an unimplemented mode. */
    RuntimeMode requireImplemented(){
        if(!implemented())throw new IllegalStateException("RUNTIME_MODE_NOT_IMPLEMENTED:"+wire);
        return this;
    }
    /**
     * Resolves the mode for an installation from the modes of the projects bound to it.
     * With no bound projects, returns {@link #DEFAULT}. With one or more projects that all agree, returns that mode.
     * Conflicting modes on the same installation are rejected rather than arbitrarily picking one.
     */
    static RuntimeMode resolve(Iterable<String> configuredModes){
        RuntimeMode chosen=null;
        for(String raw:configuredModes){
            RuntimeMode mode=parse(raw);
            if(chosen!=null&&chosen!=mode)throw new IllegalStateException("RUNTIME_MODE_CONFLICT:"+chosen.wire+":"+mode.wire);
            chosen=mode;
        }
        return chosen==null?DEFAULT:chosen;
    }
}
