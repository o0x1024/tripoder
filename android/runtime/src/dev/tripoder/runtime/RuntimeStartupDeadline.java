package dev.tripoder.runtime;

/** One monotonic startup budget, created by the authority at slot reservation. */
final class RuntimeStartupDeadline {
    static final long TIMEOUT_MS=30000;
    static long after(long now){return Math.addExact(now,TIMEOUT_MS);}
    static long remaining(long deadline,long now){
        if(deadline<=0||deadline>after(now))throw new SecurityException("PROCESS_START_DEADLINE_INVALID");
        return Math.max(0,deadline-now);
    }
    private RuntimeStartupDeadline(){}
}
