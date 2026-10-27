package dev.tripoder.runtime.script;

import java.util.*;
import static dev.tripoder.runtime.script.ScriptFault.require;

/** Worker-local invocation lease. Broker remains the sole operation/approval/journal authority. */
public final class HookInvocationSession {
    public enum State { PREPARED, INSTALLING, ACTIVE, DRAINING, DISABLED, FAILED }
    public record Snapshot(State state,String generation,String revision,int parameterVersion,
                           Map<String,Object> parameters,int inFlight,long hits,long errors) {}
    public record EventSnapshot(List<Map<String,Object>> events,long droppedEvents,long nextCursor) {}
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
    private record ActiveContext(HookInvocationSession session,String symbol) {}
    private static final ThreadLocal<Deque<ActiveContext>> ACTIVE_CONTEXT=ThreadLocal.withInitial(ArrayDeque::new);
    private final String generation,revision,sourceSha256;
    private final Map<String,HookRule> rules;
    private final Map<String,ParameterSpec> specs;
    private final PreparedBackend backend;
    private final java.util.function.Supplier<Map<String,Object>> invocationContext;
    private Map<String,Object> firstInvocation=Map.of();
    private State state=State.PREPARED;
    private Map<String,Object> parameters;
    private int parameterVersion=1,inFlight;
    private long hits,errors,droppedEvents,eventSeq;
    private boolean controlInFlight;
    private final ThreadLocal<Integer> depth=ThreadLocal.withInitial(()->0);
    private final ArrayDeque<Map<String,Object>> events=new ArrayDeque<>();
    private final LinkedHashMap<String,Object> sessionState=new LinkedHashMap<>();

    public HookInvocationSession(String generation,String revision,List<HookRule> ruleList,
                                 Map<String,ParameterSpec> specs,Map<String,Object> input,PreparedBackend backend) {
        this(generation,revision,ruleList,specs,input,backend,Map::of);
    }
    public HookInvocationSession(String generation,String revision,List<HookRule> ruleList,
                                 Map<String,ParameterSpec> specs,Map<String,Object> input,PreparedBackend backend,
                                 java.util.function.Supplier<Map<String,Object>> invocationContext) {
        this(generation,revision,ruleList,specs,input,backend,invocationContext,"");
    }
    public HookInvocationSession(String generation,String revision,List<HookRule> ruleList,Map<String,ParameterSpec> specs,Map<String,Object> input,PreparedBackend backend,java.util.function.Supplier<Map<String,Object>> invocationContext,String sourceSha256) {
        this.sourceSha256=sourceSha256;
        require(generation!=null&&!generation.isBlank()&&revision!=null&&!revision.isBlank(),"HOOK_IDENTITY");
        require(!ruleList.isEmpty()&&ruleList.size()<=32&&backend!=null,"HOOK_RULE_BUDGET");
        Map<String,HookRule> selected=new LinkedHashMap<>();
        for(HookRule rule:ruleList)require(selected.put(rule.symbol,rule)==null,"HOOK_CONFLICT");
        this.generation=generation;this.revision=revision;this.rules=Collections.unmodifiableMap(selected);
        this.specs=Map.copyOf(specs);this.parameters=ParameterSpec.resolve(this.specs,input);this.backend=backend;
        this.invocationContext=Objects.requireNonNull(invocationContext);
    }
    private java.util.function.Consumer<Map<String,Object>> eventObserver=event->{};
    public synchronized void observeEvents(java.util.function.Consumer<Map<String,Object>> observer){require(state==State.PREPARED,"HOOK_OBSERVER_STATE");eventObserver=Objects.requireNonNull(observer);}
    public synchronized Map<String,Object> firstInvocation(){return firstInvocation;}
    public static Map<String,Object> evidenceContext(){ActiveContext current=ACTIVE_CONTEXT.get().peek();require(current!=null,"HOOK_EMIT_OUTSIDE_INVOCATION");return Map.of("processGeneration",current.session.generation,"revision",current.session.revision,"sourceSha256",current.session.sourceSha256,"symbol",current.symbol,"monotonicNanos",Long.toString(System.nanoTime()));}
    static void recordEvent(String tag,Object payload) {
        ActiveContext current=ACTIVE_CONTEXT.get().peek();
        if(current==null)return;
        Object jsonSafe=toJsonSafe(payload);
        String encoded=HookWire.encode(Map.of("tag",tag,"payload",jsonSafe));
        require(encoded.length()<=4096,"HOOK_EVENT_SIZE");
        current.session.appendEvent(current.symbol,tag,jsonSafe);
    }
    static List<String> captureStack(int maxDepth) {
        ActiveContext current=ACTIVE_CONTEXT.get().peek();
        if(current==null)return List.of();
        List<String> frames=new ArrayList<>(maxDepth);
        for(StackTraceElement element:Thread.currentThread().getStackTrace()){
            String cls=element.getClassName();
            if(cls.equals("java.lang.Thread")||cls.startsWith("dev.tripoder.runtime.script.")||cls.startsWith("dev.tripoder.selfhook.")
                ||cls.startsWith("dev.tripoder.runtime.RuntimeJavaHookBackend")||cls.startsWith("dev.tripoder.runtime.RuntimeNativeHookBackend"))continue;
            frames.add(cls+"."+element.getMethodName()+"("+(element.getFileName()==null?"Unknown":element.getFileName()+":"+element.getLineNumber())+")");
            if(frames.size()>=maxDepth)break;
        }
        if(frames.isEmpty())frames.add(current.symbol);
        return List.copyOf(frames);
    }
    static boolean nativeObservationSymbol(String symbol){
        ActiveContext current=ACTIVE_CONTEXT.get().peek();
        return current!=null&&current.symbol.equals(symbol)&&current.session.backend.nativeSymbol(symbol);
    }
    static String callerModuleOffset() {
        ActiveContext current=ACTIVE_CONTEXT.get().peek();
        if(current==null)return "unknown+0x0";
        return current.symbol+"+0x0";
    }
    static Object stateGet(String key) {
        ActiveContext current=ACTIVE_CONTEXT.get().peek();
        require(current!=null,"HOOK_STATE_OUTSIDE_INVOCATION");
        return current.session.getStateValue(key);
    }
    static Object stateSet(String key,Object value) {
        ActiveContext current=ACTIVE_CONTEXT.get().peek();
        require(current!=null,"HOOK_STATE_OUTSIDE_INVOCATION");
        return current.session.setStateValue(key,value);
    }
    static boolean stateDelete(String key) {
        ActiveContext current=ACTIVE_CONTEXT.get().peek();
        require(current!=null,"HOOK_STATE_OUTSIDE_INVOCATION");
        return current.session.deleteStateValue(key);
    }
    static void stateClear() {
        ActiveContext current=ACTIVE_CONTEXT.get().peek();
        require(current!=null,"HOOK_STATE_OUTSIDE_INVOCATION");
        current.session.clearStateValues();
    }
    static List<String> stateKeys() {
        ActiveContext current=ACTIVE_CONTEXT.get().peek();
        require(current!=null,"HOOK_STATE_OUTSIDE_INVOCATION");
        return current.session.listStateKeys();
    }
    private synchronized Object getStateValue(String key){
        return sessionState.containsKey(key)?sessionState.get(key):ScriptValues.UNDEFINED;
    }
    private synchronized Object setStateValue(String key,Object value){
        if(!sessionState.containsKey(key))require(sessionState.size()<64,"HOOK_STATE_CAPACITY");
        String encoded=HookWire.encode(Map.of("k",key,"v",toJsonSafe(value)));
        require(encoded.length()<=4096,"HOOK_STATE_VALUE_SIZE");
        sessionState.put(key,value);
        return value;
    }
    private synchronized boolean deleteStateValue(String key){
        return sessionState.remove(key)!=null;
    }
    private synchronized void clearStateValues(){
        sessionState.clear();
    }
    private synchronized List<String> listStateKeys(){
        return List.copyOf(sessionState.keySet());
    }
    private static Object toJsonSafe(Object value) {
        if(value==null||value instanceof String||value instanceof Boolean||value instanceof Double)return value;
        if(value instanceof java.math.BigInteger)return value.toString();
        if(value instanceof List<?> list){List<Object> out=new ArrayList<>(list.size());for(Object item:list)out.add(toJsonSafe(item));return Collections.unmodifiableList(out);}
        if(value instanceof Map<?,?> map){Map<String,Object> out=new LinkedHashMap<>();for(var e:map.entrySet())out.put(String.valueOf(e.getKey()),toJsonSafe(e.getValue()));return Collections.unmodifiableMap(out);}
        throw new ScriptFault("HOOK_EVENT_VALUE_UNSUPPORTED");
    }
    private synchronized void appendEvent(String symbol,String tag,Object payload) {
        if(state!=State.INSTALLING&&state!=State.ACTIVE)return;
        long seq=++eventSeq;
        Map<String,Object> entry=new LinkedHashMap<>();
        entry.put("seq",(double)seq);entry.put("revision",revision);entry.put("symbol",symbol);entry.put("tag",tag);entry.put("payload",payload);
        if(events.size()>=256){events.pollFirst();droppedEvents++;}
        events.addLast(Collections.unmodifiableMap(entry));
        eventObserver.accept(Collections.unmodifiableMap(entry));
    }
    public synchronized EventSnapshot events(long sinceSeq) {
        List<Map<String,Object>> selected=new ArrayList<>();
        for(Map<String,Object> item:events)if(((Double)item.get("seq")).longValue()>sinceSeq)selected.add(item);
        return new EventSnapshot(List.copyOf(selected),droppedEvents,eventSeq);
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
            enabled=state==State.INSTALLING||state==State.ACTIVE;captured=parameters;inFlight++;withinBudget=inFlight<=64;
            if(enabled&&++hits==1){
                Map<String,Object> first=new LinkedHashMap<>();
                try{first.putAll(invocationContext.get());}catch(RuntimeException unavailable){first.put("contextUnavailable",true);}
                first.put("symbol",symbol);first.put("thread",Thread.currentThread().getName());first.put("processGeneration",generation);first.put("monotonicNanos",Long.toString(System.nanoTime()));
                firstInvocation=Collections.unmodifiableMap(first);
            }
        }
        depth.set(nesting+1);
        Deque<ActiveContext> stack=ACTIVE_CONTEXT.get();
        stack.push(new ActiveContext(this,symbol));
        try {
            if(!enabled)return original.call(args);
            // Capture original Throwable separately even when the guest throws ScriptFault itself.
            final Throwable[] originalFailure={null};
            try {
                if(nesting>=64||!withinBudget)throw new ScriptFault("HOOK_REENTRY_BUDGET");
                return rule.execute(captured,args,values->{
                    stack.pop();
                    try{return original.call(values);}
                    catch(Throwable t){originalFailure[0]=t;throw t;}
                    finally{stack.push(new ActiveContext(this,symbol));}
                });
            }catch(Throwable failure) {
                if(originalFailure[0]==failure)throw failure;
                if(failure instanceof HookBackendUncertain)throw failure;
                synchronized(this){errors++;}
                if(backend.nativeSymbol(symbol))fatal(failure);
                throw new IllegalStateException("HOOK_EXECUTION:"+failure.getMessage(),failure);
            }
        } finally {
            stack.pop();
            if(stack.isEmpty())ACTIVE_CONTEXT.remove();
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
