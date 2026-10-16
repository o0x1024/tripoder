package dev.tripoder.selfhookprobe;

import dev.tripoder.selfhook.ManagedHook;
import dev.tripoder.selfhook.OriginalMethodStorage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

final class ManagedAbiChecks {
    private int checks;
    private final Map<String,ManagedHook.Handle> handles=new LinkedHashMap<>();
    private final JSONArray results=new JSONArray(),helpers=new JSONArray();
    private final java.io.File directory;
    private final String compilation;
    private final AbiCallerIndex callerIndex;
    private final Map<Method,JSONObject> beforeActivation=new LinkedHashMap<>();
    ManagedAbiChecks(java.io.File directory,String compilation,String apkPath)throws Exception{this.directory=directory;this.compilation=compilation;this.callerIndex=new AbiCallerIndex(apkPath);}
    private void check(boolean ok,String reason){checks++;ProbeFiles.require(ok,reason);}
    private static Object invoke(Method method,Object receiver,Object... args)throws Throwable{
        try{return method.invoke(receiver,args);}catch(InvocationTargetException error){throw error.getCause();}
    }
    private ManagedHook.Handle prepare(String name,Class<?>... types)throws Exception{
        Method method=AbiFixture.class.getDeclaredMethod(name,types);
        Method[] callers=name.equals("integer")?callerIndex.callers:new Method[0];
        return prepareMethod(name,method,callers);
    }
    private ManagedHook.Handle prepareMethod(String name,Method method,Method[] callers)throws Exception{
        ManagedHook.Handle handle=ManagedHook.prepare(method,callers);handles.put(name,handle);
        byte[] dex=handle.helperDex();java.io.File file=new java.io.File(directory,"helper-"+name+".dex");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){out.write(dex);out.getFD().sync();}
        helpers.put(new JSONObject().put("method",name).put("file",file.getName()).put("sha256",ProbeFiles.sha(file)));
        java.io.File relay=new java.io.File(directory,"relay-"+name+".bin");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(relay)){out.write(handle.relayBytes());out.getFD().sync();}
        return handle;
    }
    private void one(String name,Class<?> type,Object value)throws Throwable{
        Method method=AbiFixture.class.getDeclaredMethod(name,type);Object before=invoke(method,null,value);
        JSONObject compiled=beforeActivation.get(method);
        Method entry=name.equals("integer")?AbiCaller.class.getDeclaredMethod("integer",int.class):method;
        JSONObject callerCompiled=beforeActivation.get(entry);
        ProbeFiles.require(compiled!=null&&callerCompiled!=null,"COMPILED_BEFORE_FIRST_ACTIVATION");
        ManagedHook.Handle handle=prepare(name,type);AtomicInteger seen=new AtomicInteger();
        handle.enable(call->{seen.incrementAndGet();check(call.receiver()==null,"STATIC_RECEIVER");return call.original(call.arguments());});
        int initial=AbiFixture.calls.get();
        for(int i=0;i<100;i++){Object after=invoke(entry,null,value);check(type.isPrimitive()?before.equals(after):before==after,"ROUNDTRIP_"+name);}
        check(seen.get()==100&&AbiFixture.calls.get()==initial+100,"EXACT_ORIGINAL_"+name);
        handle.disable();int old=seen.get();invoke(method,null,value);check(seen.get()==old,"DISABLED_"+name);
        results.put(new JSONObject().put("name",name).put("calls",100).put("targetBefore",compiled).put("callerBefore",callerCompiled).put("native",new JSONObject(handle.report())));
    }
    private JSONObject awaitCompilation(Method method,Object receiver,Object[] args)throws Throwable{
        long deadline=android.os.SystemClock.elapsedRealtime()+10000;
        do{
            for(int i=0;i<10000;i++)invoke(method,receiver,args);
            JSONObject sample=ArtProbe.sample(method);
            ProbeFiles.write(new java.io.File(directory,"compilation-"+method.getDeclaringClass().getSimpleName()+"-"+method.getName()+".json"),sample.toString(2)+"\n");
            String actual=ArtCompilation.kind(sample);
            if(compilation.equals(actual)||(compilation.equals("cold")&&(actual.equals("jit")||actual.equals("aot"))))return sample;
            ProbeFiles.require(android.os.SystemClock.elapsedRealtime()<deadline,"ABI_COMPILATION_NOT_OBSERVED:"+method.getName());
            Thread.sleep(10);
        }while(true);
    }
    JSONObject run()throws Throwable{
        AbiFixture receiver=new AbiFixture();Object token=new Object();
        // The process lease deliberately prevents subsequent JIT compilation.
        // Observe the complete compiled matrix before the first hook is enabled.
        String[] names={"bool","tiny","small","character","integer","wide","single","decimal","reference","array"};
        Class<?>[] types={boolean.class,byte.class,short.class,char.class,int.class,long.class,float.class,double.class,Object.class,int[].class};
        Object[] values={true,(byte)-128,(short)-32768,'\uffff',Integer.MIN_VALUE,9007199254740993L,-0.0f,-0.0d,token,new int[]{1,-1}};
        for(int i=0;i<names.length;i++){
            Method method=AbiFixture.class.getDeclaredMethod(names[i],types[i]);
            beforeActivation.put(method,awaitCompilation(method,null,new Object[]{values[i]}));
        }
        Method compiledCaller=AbiCaller.class.getDeclaredMethod("integer",int.class);
        beforeActivation.put(compiledCaller,awaitCompilation(compiledCaller,null,new Object[]{Integer.MIN_VALUE}));
        JSONObject callerBefore=callerIndex.observe();
        one("bool",boolean.class,true);one("tiny",byte.class,(byte)-128);one("small",short.class,(short)-32768);
        one("character",char.class,'\uffff');one("integer",int.class,Integer.MIN_VALUE);one("wide",long.class,9007199254740993L);
        one("single",float.class,-0.0f);one("decimal",double.class,-0.0d);one("reference",Object.class,token);one("array",int[].class,new int[]{1,-1});
        Method voidMethod=AbiFixture.class.getDeclaredMethod("nothing",Object.class);ManagedHook.Handle nothing=prepare("nothing",Object.class);
        nothing.enable(call->call.original(call.arguments()));int n=AbiFixture.calls.get();check(invoke(voidMethod,null,token)==null&&AbiFixture.calls.get()==n+1,"VOID_ORIGINAL");nothing.disable();
        Method instance=AbiFixture.class.getDeclaredMethod("instance",long.class,Object.class);ManagedHook.Handle inst=prepare("instance",long.class,Object.class);
        inst.enable(call->{check(call.receiver()==receiver,"INSTANCE_RECEIVER");return call.original(call.arguments());});
        check(invoke(instance,receiver,Long.MIN_VALUE,receiver).equals(Long.MIN_VALUE),"INSTANCE_WIDE");inst.disable();
        Method sync=AbiFixture.class.getDeclaredMethod("monitor",Object.class);ManagedHook.Handle monitor=prepare("monitor",Object.class);
        monitor.enable(call->{check(Thread.holdsLock(receiver),"HANDLER_MONITOR");return call.original(call.arguments());});
        check(invoke(sync,receiver,token)==token,"SYNCHRONIZED_REFERENCE");monitor.disable();
        Method staticSync=AbiFixture.class.getDeclaredMethod("monitorStatic",long.class);ManagedHook.Handle ms=prepare("monitorStatic",long.class);
        ms.enable(call->{check(Thread.holdsLock(AbiFixture.class),"HANDLER_STATIC_MONITOR");return call.original(call.arguments());});
        check(invoke(staticSync,null,Long.MAX_VALUE).equals(Long.MAX_VALUE),"STATIC_SYNCHRONIZED_LONG");ms.disable();
        Class<?>[] mixedTypes={int.class,long.class,double.class,Object.class,float.class,long.class,double.class,Object.class,int.class,long.class,double.class,float.class,Object.class,int.class,long.class,double.class,float.class,Object.class};
        Object[] mixedArgs={Integer.MIN_VALUE,9007199254740993L,Math.PI,token,-0.0f,Long.MAX_VALUE,-0.0d,null,17,Long.MIN_VALUE,Double.NaN,1.25f,receiver,-4,-17L,Double.POSITIVE_INFINITY,Float.NaN,new int[]{9}};
        Method mixed=AbiFixture.class.getDeclaredMethod("mixed",mixedTypes);ManagedHook.Handle mix=prepare("mixed",mixedTypes);
        mix.enable(call->call.original(call.arguments()));check(Arrays.equals((Object[])invoke(mixed,null,mixedArgs),mixedArgs),"MIXED_GPR_FPR_STACK_AND_REFERENCE_ARGS");mix.disable();
        RuntimeException marker=new RuntimeException("owned-original-marker");Method fail=AbiFixture.class.getDeclaredMethod("failure",Object.class);ManagedHook.Handle failure=prepare("failure",Object.class);
        failure.enable(call->call.original(call.arguments()));try{invoke(fail,null,marker);check(false,"ORIGINAL_THROW_MISSING");}catch(Throwable error){check(error==marker,"ORIGINAL_THROW_IDENTITY");}failure.disable();
        Method recursive=AbiFixture.class.getDeclaredMethod("recursive",int.class);ManagedHook.Handle rec=prepare("recursive",int.class);AtomicInteger recursion=new AtomicInteger();
        rec.enable(call->{recursion.incrementAndGet();return call.original(call.arguments());});check(invoke(recursive,null,4).equals(10L)&&recursion.get()==5,"RECURSIVE_ORIGINAL");rec.disable();
        ManagedHook.Handle integer=handles.get("integer");Method intMethod=AbiFixture.class.getDeclaredMethod("integer",int.class);int count=AbiFixture.calls.get();
        integer.enable(call->{call.original(call.arguments());return call.original(call.arguments());});
        try{invoke(intMethod,null,7);check(false,"ORIGINAL_TWICE_ALLOWED");}catch(IllegalStateException expected){check(AbiFixture.calls.get()==count+1,"ORIGINAL_AT_MOST_ONCE");}integer.disable();
        integer.enable(call->1L);try{invoke(intMethod,null,7);check(false,"WRONG_RESULT_ALLOWED");}catch(IllegalArgumentException expected){check(true,"EXACT_BOX_TYPE_REJECTED");}integer.disable();
        Method lazy=Class.forName("dev.tripoder.selfhookprobe.AbiLazy",false,AbiFixture.class.getClassLoader()).getDeclaredMethod("value",long.class);
        check(AbiFixture.initializations.get()==0,"LAZY_CLASS_NOT_INITIALIZED");
        ManagedHook.Handle lazyHandle=prepareMethod("lazy",lazy,new Method[0]);
        check(AbiFixture.initializations.get()==0,"PREPARE_DOES_NOT_INITIALIZE_TARGET");
        lazyHandle.enable(call->call.original(call.arguments()));
        check(AbiFixture.initializations.get()==1&&invoke(lazy,null,9007199254740993L).equals(9007199254740993L),"ACTIVATE_INITIALIZES_ONCE");lazyHandle.disable();
        JSONObject concurrency=concurrent(integer,intMethod);
        JSONObject moving=moving(receiver);
        JSONObject rootStress=ManagedRootStress.run();
        JSONObject callerAfter=callerIndex.observe();
        JSONArray states=new JSONArray();for(Map.Entry<String,ManagedHook.Handle> e:handles.entrySet())states.put(new JSONObject().put("name",e.getKey())
            .put("relaySha256",ProbeFiles.sha(new java.io.File(directory,"relay-"+e.getKey()+".bin"))).put("state",new JSONObject(e.getValue().report())));
        return new JSONObject().put("checks",checks).put("roundtrips",results).put("helpers",helpers).put("states",states)
            .put("callerIndex",callerIndex.report).put("concurrency",concurrency).put("movingGc",moving).put("concurrentRoots",rootStress)
            .put("callerObservationBefore",callerBefore).put("callerObservationAfter",callerAfter)
            .put("storage",new JSONObject(OriginalMethodStorage.report())).put("requestedCompilation",compilation).put("fullCallerCoverage",false);
    }
    private JSONObject concurrent(ManagedHook.Handle handle,Method target)throws Throwable{
        java.util.concurrent.atomic.AtomicBoolean stop=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicLong calls=new java.util.concurrent.atomic.AtomicLong();AtomicInteger errors=new AtomicInteger();
        CountDownLatch started=new CountDownLatch(4);Thread[] workers=new Thread[4];int initial=AbiFixture.calls.get();
        long originalBefore=new JSONObject(handle.report()).getLong("originals");
        for(int i=0;i<workers.length;i++){
            workers[i]=new Thread(()->{started.countDown();while(!stop.get()){
                try{int value=(Integer)invoke(target,null,7);if(value!=7&&value!=107)errors.incrementAndGet();}
                catch(Throwable error){errors.incrementAndGet();}calls.incrementAndGet();}},"owned-abi-worker-"+i);workers[i].start();
        }
        boolean active=false;
        try{
            check(started.await(5,TimeUnit.SECONDS),"ABI_WORKERS_STARTED");long deadline=android.os.SystemClock.elapsedRealtime()+5000;
            while(calls.get()<1000){ProbeFiles.require(android.os.SystemClock.elapsedRealtime()<deadline,"ABI_WORKER_PROGRESS");Thread.yield();}
            for(int i=0;i<20;i++){handle.enable(call->(Integer)call.original(call.arguments())+100);active=true;Thread.sleep(2);
                handle.disable();active=false;Thread.sleep(2);}
        }finally{if(active)handle.disable();stop.set(true);for(Thread worker:workers)worker.join(5000);}
        for(Thread worker:workers)check(!worker.isAlive(),"ABI_WORKER_JOINED");
        check(errors.get()==0&&calls.get()>=1000,"ABI_CONCURRENT_VALUES");
        check(AbiFixture.calls.get()-initial==calls.get(),"ABI_CONCURRENT_ORIGINAL_EXACTLY_ONCE");
        return new JSONObject().put("threads",4).put("cycles",20).put("calls",calls.get()).put("errors",errors.get())
            .put("originalBodyCalls",AbiFixture.calls.get()-initial).put("nativeOriginalCalls",new JSONObject(handle.report()).getLong("originals")-originalBefore);
    }
    private JSONObject moving(AbiFixture receiver)throws Throwable{
        Method hold=AbiFixture.class.getDeclaredMethod("holding",Object.class);ManagedHook.Handle handle=prepare("holding",Object.class);
        Object[] payload=new Object[]{new byte[1024],new byte[2048],new String(new char[]{'o','w','n'})};
        Object[] roots={payload,payload[0],payload[1],payload[2]};
        AbiFixture.entered=new CountDownLatch(1);AbiFixture.release=new CountDownLatch(1);AtomicReference<Object> value=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        handle.enable(call->call.original(call.arguments()));
        Thread worker=new Thread(()->{try{value.set(invoke(hold,receiver,(Object)payload));}catch(Throwable e){error.set(e);}},"owned-moving-abi");worker.start();
        check(AbiFixture.entered.await(5,TimeUnit.SECONDS),"ORIGINAL_REFERENCE_FRAME_ENTERED");
        long[] before=GcProbe.snapshot(roots);check(before!=null,"GC_ADDRESS_BASELINE");
        long[] after=before;int moves=0,rounds=0;
        String gcBefore=android.os.Debug.getRuntimeStat("art.gc.gc-count");
        try{
            for(;rounds<16&&moves==0;rounds++){
                byte[][] pressure=new byte[1024][];for(int i=0;i<pressure.length;i++)pressure[i]=new byte[4096];
                System.gc();Thread.sleep(30);after=GcProbe.snapshot(roots);check(after!=null,"GC_ADDRESS_AFTER");
                for(int i=0;i<before.length;i++)if(before[i]!=after[i])moves++;
            }
            handle.disable();check(worker.isAlive(),"REFERENCE_FRAME_IN_FLIGHT_AFTER_DISABLE");
        }finally{AbiFixture.release.countDown();worker.join(5000);}
        check(!worker.isAlive()&&error.get()==null&&value.get()==payload,"MOVED_REFERENCE_ORIGINAL_IDENTITY");
        check(moves>0,"MOVING_GC_MUST_BE_OBSERVED");
        return new JSONObject().put("before",new JSONArray(before)).put("after",new JSONArray(after)).put("movedReferences",moves)
            .put("gcBefore",gcBefore).put("gcAfter",android.os.Debug.getRuntimeStat("art.gc.gc-count")).put("rounds",rounds)
            .put("sameReferenceReturned",true).put("disabledInFlight",true);
    }
}
