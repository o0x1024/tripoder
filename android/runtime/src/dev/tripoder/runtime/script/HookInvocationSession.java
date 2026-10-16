package dev.tripoder.runtime.script;

import java.util.*;
import static dev.tripoder.runtime.script.ScriptFault.require;

/** Worker-local invocation lease. Broker remains the sole operation/approval/journal authority. */
public final class HookInvocationSession {
    public enum State { PREPARED, INSTALLING, ACTIVE, DRAINING, DISABLED, FAILED }
    public record Snapshot(State state,String generation,String revision,int parameterVersion,
                           Map<String,Object> parameters,int inFlight,long hits,long errors) {}
    @FunctionalInterface public interface Dispatch { Object call(String symbol,List<Object> args,HookRule.Original original)throws Throwable; }
    /** Supplied only after target/evidence/coverage validation. It must not publish during prepare. */
    public interface PreparedBackend {
        /** Publish every entry and flush before returning. Throwing makes ownership uncertain. */
        void publish(Dispatch dispatch)throws Exception;
        /** Restore entries, retaining late-call roots. False is a known
         * unchanged BUSY result: keep DRAINING. */
        boolean retire()throws Exception;
        /** Must invalidate the process on partial publish/retire. Never recover by replaying. */
        void failStop(Throwable cause);
        /** Native script failures must terminate; Java script failures may throw HOOK_EXECUTION. */
        boolean nativeSymbol(String symbol);
    }
    private final String generation,revision;
    private final Map<String,HookRule> rules;
    private final Map<String,ParameterSpec> specs;
    private final PreparedBackend backend;
    private State state=State.PREPARED;
    private Map<String,Object> parameters;
    private int parameterVersion=1,inFlight;
    private long hits,errors;
    private boolean controlInFlight;
    private final ThreadLocal<Integer> depth=ThreadLocal.withInitial(()->0);

    public HookInvocationSession(String generation,String revision,List<HookRule> ruleList,
                                 Map<String,ParameterSpec> specs,Map<String,Object> input,PreparedBackend backend) {
        require(generation!=null&&!generation.isBlank()&&revision!=null&&!revision.isBlank(),"HOOK_IDENTITY");
        require(!ruleList.isEmpty()&&ruleList.size()<=32&&backend!=null,"HOOK_RULE_BUDGET");
        Map<String,HookRule> selected=new LinkedHashMap<>();
        for(HookRule rule:ruleList)require(selected.put(rule.symbol,rule)==null,"HOOK_CONFLICT");
        this.generation=generation;this.revision=revision;this.rules=Collections.unmodifiableMap(selected);
        this.specs=Map.copyOf(specs);this.parameters=ParameterSpec.resolve(this.specs,input);this.backend=backend;
    }
    /** Called by the existing approved activate command, never by a script. */
    public void activate(String expectedGeneration,String expectedRevision) {
        synchronized(this) {identity(expectedGeneration,expectedRevision);require(state==State.PREPARED&&!controlInFlight,"HOOK_CONTEXT_IN_USE");controlInFlight=true;state=State.INSTALLING;}
        try {
            // Callbacks may start as publication proceeds. No activation ACK is returned until all entries flush.
            backend.publish(this::invoke);
            synchronized(this) {require(state==State.INSTALLING,"HOOK_ACTIVATION_STATE");state=State.ACTIVE;}
        } catch(HookPublicationRejected failure) {
            synchronized(this){state=State.FAILED;}
            throw new IllegalStateException(failure.getMessage(),failure);
        } catch(Throwable failure) {fatal(failure);}
        finally {synchronized(this){controlInFlight=false;}}
    }
    public synchronized Snapshot update(String expectedGeneration,String expectedRevision,int expectedVersion,Map<String,Object> updates) {
        identity(expectedGeneration,expectedRevision);
        require(!controlInFlight&&state==State.ACTIVE&&expectedVersion==parameterVersion,"HOOK_PARAMETER_VERSION_STALE");
        require(parameterVersion<10000,"HOOK_PARAMETER_BUDGET");
        Map<String,Object> merged=new LinkedHashMap<>(parameters);merged.putAll(updates);
        Map<String,Object> candidate=ParameterSpec.resolve(specs,merged);
        parameters=candidate;parameterVersion++;return snapshot();
    }
    public Snapshot disable(String expectedGeneration,String expectedRevision) {
        synchronized(this) {
            identity(expectedGeneration,expectedRevision);require(!controlInFlight&&(state==State.ACTIVE||state==State.DRAINING),"HOOK_REVISION_STALE");
            state=State.DRAINING;
        }
        return status();
    }
    /** Retirement is control-thread work, not work performed under a callback or invocation lock. */
    public Snapshot status() {
        boolean retire;
        synchronized(this) {retire=state==State.DRAINING&&inFlight==0&&!controlInFlight;if(retire)controlInFlight=true;}
        if(retire) {
            try {if(backend.retire())synchronized(this){state=State.DISABLED;}}
            catch(Throwable failure){fatal(failure);}
            finally{synchronized(this){controlInFlight=false;}}
        }
        synchronized(this){return snapshot();}
    }
    private Object invoke(String symbol,List<Object> args,HookRule.Original original)throws Throwable {
        HookRule rule=rules.get(symbol);require(rule!=null,"HOOK_SYMBOL_STALE");
        boolean enabled,withinBudget;Map<String,Object> captured;int nesting=depth.get();
        synchronized(this) {
            require(state==State.INSTALLING||state==State.ACTIVE||state==State.DRAINING||state==State.DISABLED,"HOOK_CALLBACK_STATE");
            enabled=state==State.INSTALLING||state==State.ACTIVE;captured=parameters;inFlight++;withinBudget=inFlight<=64;if(enabled)hits++;
        }
        depth.set(nesting+1);
        try {
            if(!enabled)return original.call(args);
            // Capture original Throwable separately even when the guest throws ScriptFault itself.
            final Throwable[] originalFailure={null};
            try {
                if(nesting>=64||!withinBudget)throw new ScriptFault("HOOK_REENTRY_BUDGET");
                return rule.execute(captured,args,values->{try{return original.call(values);}catch(Throwable t){originalFailure[0]=t;throw t;}});
            }catch(Throwable failure) {
                if(originalFailure[0]==failure)throw failure;
                if(failure instanceof HookBackendUncertain)throw failure;
                synchronized(this){errors++;}
                if(backend.nativeSymbol(symbol))fatal(failure);
                throw new IllegalStateException("HOOK_EXECUTION:"+failure.getMessage(),failure);
            }
        } finally {
            if(nesting==0)depth.remove();else depth.set(nesting);
            synchronized(this){inFlight--;}
        }
    }
    private void fatal(Throwable failure) {
        synchronized(this){state=State.FAILED;}
        try {backend.failStop(failure);}
        catch(Throwable terminationFailure) {throw new HookBackendUncertain(terminationFailure);}
        // A test backend may return or throw; neither permits a definitive command ACK.
        throw new HookBackendUncertain(failure);
    }
    void invalidateTarget(Throwable cause){fatal(cause);}
    private void identity(String expectedGeneration,String expectedRevision) {
        require(generation.equals(expectedGeneration),"HOOK_PROCESS_STALE");require(revision.equals(expectedRevision),"HOOK_REVISION_STALE");
    }
    private Snapshot snapshot() {return new Snapshot(state,generation,revision,parameterVersion,parameters,inFlight,hits,errors);}
}
