package dev.tripoder.selfhook;

import dalvik.system.InMemoryDexClassLoader;
import dev.tripoder.selfhook.dex.AbiSignature;
import dev.tripoder.selfhook.dex.HelperDex;
import dev.tripoder.selfhook.dex.HookCapacity;
import java.lang.reflect.Method;
import java.lang.reflect.Executable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Fixed-platform managed hooks. Initialize the interpreter policy before guest
 * code can execute. A late activation cannot migrate existing compiled frames. */
public final class ManagedHook {
    public interface Handler { Object invoke(Invocation call) throws Throwable; }
    public static final class Invocation {
        private final Entry entry;
        private final Object receiver;
        private final Object[] args;
        private final Thread thread=Thread.currentThread();
        private boolean called,closed,originalCompleted;
        private Throwable originalFailure;
        Invocation(Entry entry,Object receiver,Object[] args){this.entry=entry;this.receiver=receiver;this.args=args.clone();}
        public Object receiver(){return receiver;}
        public Object[] arguments(){return args.clone();}
        public Object original(Object... replacement) throws Throwable {
            if(Thread.currentThread()!=thread||called||closed)throw new IllegalStateException("ORIGINAL_INVOCATION_NOT_OWNED");
            validateArguments(entry,receiver,replacement);called=true;
            try{Object result=originalNative(entry.id,receiver,replacement);originalCompleted=true;return result;}
            catch(Throwable error){originalFailure=error;throw error;}
        }
    }
    public static final class Handle {
        private final Entry entry;
        private Handle(Entry entry){this.entry=entry;}
        public void enable(Handler handler){Map<Handle,Handler> batch=new LinkedHashMap<>();batch.put(this,handler);enableBatch(batch);}
        public void disable(){disableBatch(java.util.List.of(this));}
        public String report(){return reportNative(entry.id);}
        public byte[] helperDex(){return entry.dex.clone();}
        public byte[] relayBytes(){return relayNative(entry.id);}
    }
    private static final class Entry {
        final int id;final Executable target;final Method helper;final Class<?>[] parameters;
        final Class<?> result;final boolean instance,synchronizedMethod,constructor;
        final InMemoryDexClassLoader loader;final byte[] dex;
        volatile Handler handler;
        Entry(int id,Executable target,Method helper,InMemoryDexClassLoader loader,byte[] dex){
            this.id=id;this.target=target;this.helper=helper;this.loader=loader;this.dex=dex;
            parameters=target.getParameterTypes();constructor=target instanceof Constructor<?>;result=constructor?void.class:((Method)target).getReturnType();
            instance=!Modifier.isStatic(target.getModifiers());synchronizedMethod=Modifier.isSynchronized(target.getModifiers());
        }
    }
    private static final AtomicReferenceArray<Entry> entries=new AtomicReferenceArray<>(HookCapacity.MAX_ENTRIES);
    // Executable equality includes its actual declaring Class and complete
    // signature. Keep managed roots; never key this by moving heap addresses.
    private static final Map<Executable,Entry> targets=new java.util.HashMap<>();
    private static int used;
    private static boolean processInitializationAttempted,processInitialized;
    private static boolean changing;
    private ManagedHook(){}
    private static native boolean prepareNative(int slot,Executable target,Method helper,Class<?> owner,String shorty,Executable[] callers);
    private static native boolean toggleBatchNative(int[] slots,boolean active);
    private static native Object originalNative(int slot,Object receiver,Object[] args);
    private static native String reportNative(int slot);
    private static native byte[] relayNative(int slot);
    private static native String processPolicyNative();
    private static native boolean initializeProcessNative();
    private static native int capacityNative();
    private static native int nativeOriginalRemainingNative();
    private static void requireCapacity(){
        if(capacityNative()!=HookCapacity.MAX_ENTRIES)throw new IllegalStateException("MANAGED_CAPACITY_ARTIFACT_MISMATCH");
    }
    public static synchronized int remainingCapacity(){requireCapacity();return HookCapacity.MAX_ENTRIES-used;}
    public static synchronized boolean ownsTarget(Executable target){
        return targets.containsKey(target);
    }
    public static String processPolicy(){return processPolicyNative();}
    public static synchronized void requireProcessInitialization(){
        if(!processInitialized)throw new IllegalStateException("EXPLICIT_PROCESS_INITIALIZATION_REQUIRED");
    }
    /** One mutator pause validates and publishes the complete batch. Success is
     * a typed ownership receipt; callers need not parse per-entry JSON reports. */
    public static synchronized void enableBatch(Map<Handle,Handler> supplied){
        if(changing)throw new IllegalStateException("HOOK_PUBLICATION_REENTRY");changing=true;
        try{enableBatchLocked(supplied);}finally{changing=false;}
    }
    private static void enableBatchLocked(Map<Handle,Handler> supplied){
        Map<Handle,Handler> batch=new LinkedHashMap<>(supplied);
        if(batch.isEmpty()||batch.size()>HookCapacity.MAX_ENTRIES)throw new IllegalStateException("HOOK_ENABLE_STATE");
        java.util.Set<Class<?>> owners=new java.util.LinkedHashSet<>();int[] slots=new int[batch.size()];int i=0;
        for(var item:batch.entrySet()){
            Handle handle=item.getKey();if(handle==null||item.getValue()==null||handle.entry.handler!=null||entries.get(handle.entry.id)!=handle.entry)
                throw new IllegalStateException("HOOK_ENABLE_STATE");
            owners.add(handle.entry.target.getDeclaringClass());slots[i++]=handle.entry.id;
        }
        try {for(Class<?> owner:owners)if(Class.forName(owner.getName(),true,owner.getClassLoader())!=owner)throw new IllegalStateException("CLASS_IDENTITY_CHANGED");}
        catch(ClassNotFoundException error){throw new IllegalStateException("CLASS_INITIALIZATION_FAILED",error);}
        batch.forEach((handle,handler)->handle.entry.handler=handler);
        if(!toggleBatchNative(slots,true)){batch.keySet().forEach(handle->handle.entry.handler=null);throw new IllegalStateException("HOOK_ACTIVATION_REJECTED");}
    }
    public static synchronized void disableBatch(java.util.Collection<Handle> supplied){
        if(changing)throw new IllegalStateException("HOOK_PUBLICATION_REENTRY");changing=true;
        try{disableBatchLocked(supplied);}finally{changing=false;}
    }
    private static void disableBatchLocked(java.util.Collection<Handle> supplied){
        java.util.Set<Handle> batch=new java.util.LinkedHashSet<>(supplied);
        if(batch.isEmpty()||batch.size()!=supplied.size()||batch.size()>HookCapacity.MAX_ENTRIES)throw new IllegalStateException("HOOK_DISABLE_STATE");
        int[] slots=new int[batch.size()];int i=0;
        for(Handle handle:batch){if(handle==null||handle.entry.handler==null||entries.get(handle.entry.id)!=handle.entry)throw new IllegalStateException("HOOK_DISABLE_STATE");slots[i++]=handle.entry.id;}
        batch.forEach(handle->handle.entry.handler=null);
        if(!toggleBatchNative(slots,false))throw new IllegalStateException("HOOK_DISABLE_REJECTED");
    }

    /** Process bootstrap, before constructing/loading any guest application code.
     * This is a one-shot policy mutation, not a proof that old AOT frames migrated.
     * The owner of the guest lifecycle must enforce the execution boundary. */
    public static synchronized void initializeProcess(){
        requireCapacity();
        if(processInitializationAttempted||used!=0)throw new IllegalStateException("PROCESS_POLICY_INITIALIZATION_ORDER");
        processInitializationAttempted=true;
        if(!initializeProcessNative())throw new IllegalStateException("PROCESS_POLICY_INITIALIZATION_FAILED");
        processInitialized=true;
    }

    public static synchronized Handle prepare(Executable target,Executable[] knownCallers) throws Exception {
        Map<Executable,Executable[]> request=new LinkedHashMap<>();request.put(target,knownCallers);
        return prepareBatch(request).get(target);
    }
    /** All metadata is checked before reserving slots. DEX files retain their
     * independent immutable slot names but share one ART loader per batch. */
    public static synchronized Map<Executable,Handle> prepareBatch(Map<? extends Executable,Executable[]> requested)throws Exception {
        requireCapacity();
        if(processInitializationAttempted&&!processInitialized)throw new IllegalStateException("PROCESS_POLICY_INITIALIZATION_FAILED");
        if(requested==null)throw new IllegalArgumentException("HOOK_PREPARE_BUDGET");
        Map<Executable,Executable[]> snapshot=new LinkedHashMap<>(requested);
        if(snapshot.isEmpty()||snapshot.size()>HookCapacity.MAX_ENTRIES-used)throw new IllegalArgumentException("HOOK_PREPARE_BUDGET");
        Map<Executable,AbiSignature> signatures=new LinkedHashMap<>();int nativeCount=0;
        for(var item:snapshot.entrySet()){
            Executable[] callers=item.getValue()==null?null:item.getValue().clone();validateTarget(item.getKey(),callers);
            item.setValue(callers);signatures.put(item.getKey(),new AbiSignature(item.getKey()));if(Modifier.isNative(item.getKey().getModifiers()))nativeCount++;
        }
        if(nativeCount>nativeOriginalRemainingNative())throw new IllegalStateException("NATIVE_ORIGINAL_CAPACITY");
        int first=used;used+=snapshot.size();byte[][] dexes=HelperDex.createBatch(java.util.List.copyOf(signatures.values()),first);
        ByteBuffer[] buffers=new ByteBuffer[dexes.length];for(int i=0;i<dexes.length;i++)buffers[i]=ByteBuffer.wrap(dexes[i]);int index;
        InMemoryDexClassLoader loader=new InMemoryDexClassLoader(buffers,ManagedHook.class.getClassLoader());
        Map<Executable,Handle> result=new LinkedHashMap<>();index=0;
        for(var item:snapshot.entrySet()){
            result.put(item.getKey(),prepareEntry(first+index,item.getKey(),item.getValue(),signatures.get(item.getKey()),loader,dexes[index]));index++;
        }
        return java.util.Collections.unmodifiableMap(result);
    }
    private static void validateTarget(Executable target,Executable[] knownCallers){
        if(target==null||target.getDeclaringClass().isInterface()||java.lang.reflect.Proxy.isProxyClass(target.getDeclaringClass())
            ||Modifier.isAbstract(target.getModifiers()))
            throw new IllegalArgumentException("OWNED_MANAGED_TARGET_REQUIRED");
        FrameworkAccess.requireManagedHookTarget(target);
        if(knownCallers==null||knownCallers.length>64)throw new IllegalArgumentException("HOOK_PREPARE_BUDGET");
        if(targets.containsKey(target))throw new IllegalStateException("TARGET_ALREADY_OWNED");
        java.util.HashSet<Executable> unique=new java.util.HashSet<>();
        for(Executable caller:knownCallers)if(caller==null||caller.equals(target)||!unique.add(caller)
            ||Modifier.isNative(caller.getModifiers())||Modifier.isAbstract(caller.getModifiers()))throw new IllegalArgumentException("OWNED_CALLER_REQUIRED");
        for(java.lang.annotation.Annotation annotation:target.getDeclaredAnnotations()){
            String name=annotation.annotationType().getName();
            if(name.equals("jdk.internal.reflect.CallerSensitive")||name.equals("sun.reflect.CallerSensitive")
                ||name.endsWith(".CallerSensitive"))throw new IllegalArgumentException("CALLER_SENSITIVE_REQUIRES_ORIGINAL_FRAME_ADAPTER");
        }
    }
    private static Handle prepareEntry(int id,Executable target,Executable[] callers,AbiSignature abi,InMemoryDexClassLoader loader,byte[] dex)throws Exception {
        Class<?> helperClass=Class.forName(HelperDex.className(id),true,loader);
        Class<?>[] helperTypes=new Class<?>[abi.parameters.size()+(abi.instance?1:0)];int at=0;
        if(abi.instance)helperTypes[at++]=Object.class;
        for(Class<?> type:target.getParameterTypes())helperTypes[at++]=type.isPrimitive()?type:Object.class;
        Method helper=helperClass.getDeclaredMethod("invoke",helperTypes);
        Class<?> result=target instanceof Method?((Method)target).getReturnType():void.class;
        if(helper.getReturnType()!=(result.isPrimitive()?result:Object.class))throw new IllegalStateException("HELPER_SIGNATURE");
        if(!OriginalMethodStorage.prepare(target))throw new IllegalStateException("ORIGINAL_STORAGE_REQUIRED: "+OriginalMethodStorage.report());
        StringBuilder shorty=new StringBuilder(AbiSignature.reference(abi.result)?"L":abi.result);
        for(String arg:abi.parameters)shorty.append(AbiSignature.reference(arg)?"L":arg);
        Entry entry=new Entry(id,target,helper,loader,dex);entries.set(id,entry);targets.put(target,entry);
        if(!prepareNative(id,target,helper,target.getDeclaringClass(),shorty.toString(),callers))throw new IllegalStateException("MANAGED_ENTRY_PREPARE: "+reportNative(id));
        return new Handle(entry);
    }
    /** Called solely by the generated helper; arguments remain managed roots. */
    public static Object dispatch(int id,Object receiver,Object[] args) throws Throwable {
        Entry entry=id>=0&&id<HookCapacity.MAX_ENTRIES?entries.get(id):null;
        if(entry==null)throw new IllegalStateException("UNKNOWN_MANAGED_ENTRY");
        Handler snapshot=entry.handler;
        if(entry.synchronizedMethod){synchronized(entry.instance?receiver:entry.target.getDeclaringClass()){return execute(entry,snapshot,receiver,args);}}
        return execute(entry,snapshot,receiver,args);
    }
    private static Object execute(Entry entry,Handler handler,Object receiver,Object[] args) throws Throwable {
        validateArguments(entry,receiver,args);
        if(handler==null)return originalNative(entry.id,receiver,args);
        Invocation call=new Invocation(entry,receiver,args);
        try {Object result=handler.invoke(call);
            if(entry.constructor&&!call.originalCompleted){
                if(call.originalFailure!=null)throw call.originalFailure;
                throw new IllegalStateException("CONSTRUCTOR_ORIGINAL_REQUIRED");
            }
            validate(entry.result,result);return result;}
        finally {call.closed=true;}
    }
    private static void validateArguments(Entry entry,Object receiver,Object[] args){
        if(entry.instance?!entry.target.getDeclaringClass().isInstance(receiver):receiver!=null)throw new IllegalArgumentException("ABI_RECEIVER");
        if(args==null||args.length!=entry.parameters.length)throw new IllegalArgumentException("ABI_ARGUMENT_COUNT");
        for(int i=0;i<args.length;i++)validate(entry.parameters[i],args[i]);
    }
    private static void validate(Class<?> type,Object value){
        if(type==void.class){if(value!=null)throw new IllegalArgumentException("ABI_VOID_RESULT");return;}
        Class<?> actual=type;
        if(type==boolean.class)actual=Boolean.class;else if(type==byte.class)actual=Byte.class;else if(type==short.class)actual=Short.class;
        else if(type==char.class)actual=Character.class;else if(type==int.class)actual=Integer.class;else if(type==long.class)actual=Long.class;
        else if(type==float.class)actual=Float.class;else if(type==double.class)actual=Double.class;
        if(value==null?type.isPrimitive():!actual.isInstance(value))throw new IllegalArgumentException("ABI_VALUE_TYPE:"+type.getName());
    }
}
