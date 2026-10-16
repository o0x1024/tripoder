package dev.tripoder.selfhookprobe;
import dalvik.system.InMemoryDexClassLoader;
import dev.tripoder.selfhook.ManagedHook;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
final class JniSdkChecks {
    private static final List<String> passed=new ArrayList<>();
    private static native boolean bind(Class<?> owner,int variant);
    private static native String nativeReport();
    private static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);passed.add(name);}
    private static Object call(Method method,Object receiver,Object... args)throws Throwable{
        try{return method.invoke(receiver,args);}catch(InvocationTargetException error){throw error.getCause();}
    }
    private static long hash(){long value=0;for(int i=0;i<32;i++)value=Long.rotateLeft(value,3)^Long.rotateLeft(0xfedcba9876543210L,i);return value;}
    static JSONObject run(android.content.Context context)throws Throwable{
        byte[] dex;try(var input=context.getAssets().open("jni-guest.dex")){dex=input.readAllBytes();}
        ClassLoader loader=new InMemoryDexClassLoader(ByteBuffer.wrap(dex),JniSdkChecks.class.getClassLoader());
        Class<?> type=Class.forName("dev.tripoder.selfhookprobe.jniguest.JniSdkTarget",false,loader);
        check(type.getClassLoader()==loader&&System.getProperty("tripoder.jni.sdk.initialized")==null,"INDEPENDENT_UNINITIALIZED_GUEST");
        check(bind(type,1),"REAL_INITIAL_REGISTRATION");
        check(System.getProperty("tripoder.jni.sdk.initialized")==null,"REGISTRATION_DOES_NOT_INITIALIZE_GUEST");
        Class<?>[] longs=new Class<?>[32];Arrays.fill(longs,long.class);
        Method[] numbers={type.getDeclaredMethod("values",longs),type.getDeclaredMethod("fast",longs),type.getDeclaredMethod("critical",longs)};
        Method invoke=type.getDeclaredMethod("invoke",int.class),text=type.getDeclaredMethod("text",String.class,double.class,long.class,Object[].class),
            fail=type.getDeclaredMethod("fail"),missing=type.getDeclaredMethod("missing"),named=JniNamedTarget.class.getDeclaredMethod("named",int.class);
        Class<?>[] mixedTypes=new Class<?>[32];Object[] mixedValues=new Object[32];for(int i=0;i<16;i++){
            mixedTypes[2*i]=double.class;mixedTypes[2*i+1]=long.class;mixedValues[2*i]=Double.longBitsToDouble(0x3ff0000000000000L+i);mixedValues[2*i+1]=Long.rotateLeft(0xfedcba9876543210L,i);}
        Method mixed=type.getDeclaredMethod("mixed",mixedTypes);
        Map<Executable,Executable[]> requested=new LinkedHashMap<>();for(Method method:numbers)requested.put(method,new Executable[0]);
        for(Method method:new Method[]{text,fail,missing,named,mixed})requested.put(method,new Executable[0]);
        int capacity=ManagedHook.remainingCapacity();var handles=ManagedHook.prepareBatch(requested);
        check(System.getProperty("tripoder.jni.sdk.initialized")==null&&capacity-ManagedHook.remainingCapacity()==8,"PREPARE_NO_GUEST_INITIALIZATION");
        Map<ManagedHook.Handle,ManagedHook.Handler> handlers=new LinkedHashMap<>();
        for(Method method:numbers)handlers.put(handles.get(method),c->(Long)c.original(c.arguments())+41);
        handlers.put(handles.get(text),c->c.original(c.arguments()));handlers.put(handles.get(fail),c->c.original(c.arguments()));
        handlers.put(handles.get(missing),c->77);handlers.put(handles.get(named),c->(Integer)c.original(c.arguments())+41);
        handlers.put(handles.get(mixed),c->c.original(c.arguments()));
        ManagedHook.enableBatch(handlers);
        check("yes".equals(System.getProperty("tripoder.jni.sdk.initialized")),"ACTIVATION_INITIALIZES_GUEST");
        for(int mode=0;mode<3;mode++)check((Long)call(invoke,null,mode)==hash()+141,"NATIVE_32_ARGUMENT_ORIGINAL_MODE_"+mode);
        check(Double.doubleToRawLongBits((Double)call(mixed,null,mixedValues))==Long.MIN_VALUE,"MIXED_GPR_FPR_OVERFLOW_AND_NEGATIVE_ZERO");
        Object receiver=type.getDeclaredConstructor().newInstance();String message=new String(new char[]{'a',0,'\ud83d','\ude80'});Object[] references={receiver,message};
        check(call(text,receiver,message,-0.0,Long.MIN_VALUE,references)==message,"INSTANCE_UTF16_REFERENCE_IDENTITY");
        Throwable exact=(Throwable)type.getDeclaredField("FAILURE").get(null);
        try{call(fail,null);throw new AssertionError("NO_ORIGINAL_EXCEPTION");}catch(IllegalStateException error){check(error==exact,"EXACT_NATIVE_ORIGINAL_EXCEPTION");}
        check((Integer)call(missing,null)==77,"REPLACE_UNRESOLVED_NATIVE_WITHOUT_ORIGINAL");
        check((Integer)call(named,null,4)==58,"LAZY_NAMED_IMPLEMENTATION_RESOLUTION");
        boolean flags=true;for(int i=0;i<3;i++){JSONObject report=new JSONObject(handles.get(numbers[i]).report());long mask=i==1?0x80100:i==2?0x100100:0x100;
            flags&=(report.getLong("targetFlags")&0x180100)==mask&&(report.getLong("backupFlags")&0x180100)==mask&&report.getBoolean("backupRooted");}
        check(flags,"ALL_NATIVE_MODE_FLAGS_AND_BACKUP_ROOTS");
        check(bind(type,2),"REAL_BINDING_REPLACEMENT");
        for(int mode=0;mode<3;mode++)check((Long)call(invoke,null,mode)==hash()+241,"ORIGINAL_SEES_CURRENT_BINDING_MODE_"+mode);
        check(bind(type,0),"REAL_UNREGISTER_WITH_HOOK_ACTIVE");
        try{call(invoke,null,0);throw new AssertionError("UNREGISTERED_ORIGINAL_RETURNED");}catch(UnsatisfiedLinkError expected){check(true,"UNREGISTERED_ORIGINAL_RESOLUTION_FAILURE");}
        check((Integer)call(missing,null)==77,"REPLACEMENT_SURVIVES_UNREGISTER");
        check(bind(type,2),"REAL_REREGISTRATION_WITH_HOOK_ACTIVE");
        AtomicReference<Throwable> failure=new AtomicReference<>();Thread[] workers=new Thread[4];
        for(int i=0;i<workers.length;i++){workers[i]=new Thread(()->{try{for(int n=0;n<32;n++)for(int mode=0;mode<3;mode++){
            long value=(Long)call(invoke,null,mode);if(value!=hash()+141&&value!=hash()+241)throw new AssertionError("BINDING_VALUE");if(n%8==0)System.gc();}}
            catch(Throwable error){failure.compareAndSet(null,error);}},"jni-sdk-register-"+i);workers[i].start();}
        for(int n=0;n<64;n++)if(!bind(type,1+n%2))throw new AssertionError("CONCURRENT_REGISTRATION");
        for(Thread worker:workers){worker.join(30000);if(worker.isAlive())throw new AssertionError("CONCURRENT_ORIGINAL_TIMEOUT");}
        if(failure.get()!=null)throw failure.get();check(true,"CONCURRENT_REGISTER_ORIGINAL_AND_MOVING_GC");
        ManagedHook.Handle first=handles.get(numbers[0]);first.disable();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Object> result=new AtomicReference<>();
        first.enable(c->{entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("RELEASE_TIMEOUT");return (Long)c.original(c.arguments())+41;});
        Thread admitted=new Thread(()->{try{result.set(call(invoke,null,0));}catch(Throwable error){failure.set(error);}},"jni-sdk-admitted");admitted.start();
        if(!entered.await(10,TimeUnit.SECONDS))throw new AssertionError("ADMISSION_TIMEOUT");first.disable();release.countDown();admitted.join(10000);
        if(failure.get()!=null)throw failure.get();check(!admitted.isAlive()&&Objects.equals(result.get(),hash()+241),"ADMITTED_ORIGINAL_SURVIVES_DISABLE");
        check((Long)call(invoke,null,0)==hash()+200,"NEW_CALL_AFTER_DISABLE_USES_ART_BINDING");
        first.enable(handlers.get(first));ManagedHook.disableBatch(handles.values());
        for(int mode=0;mode<3;mode++)check((Long)call(invoke,null,mode)==hash()+200,"RETIRED_NATIVE_MODE_"+mode);
        try{call(missing,null);throw new AssertionError("RETIRED_MISSING_RETURNED");}catch(UnsatisfiedLinkError expected){check(true,"RETIRED_MISSING_USES_REAL_LOOKUP");}
        JSONObject report=new JSONObject(nativeReport());check(report.getInt("errors")==0&&report.getInt("calls")>=396,"NATIVE_STATE_LOADER_AND_CALL_COUNTS");
        check(capacity-ManagedHook.remainingCapacity()==8,"REREGISTRATION_DOES_NOT_CONSUME_HOOK_SLOTS");
        return new JSONObject().put("passed",true).put("checks",passed.size()).put("passedChecks",new JSONArray(passed)).put("native",report)
            .put("modes",3).put("parameters",32).put("concurrentCalls",384).put("registrations",67).put("productAccepted",false);
    }
}
