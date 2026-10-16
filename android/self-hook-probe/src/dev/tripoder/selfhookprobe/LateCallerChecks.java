package dev.tripoder.selfhookprobe;

import dev.tripoder.selfhook.ManagedHook;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Future owned InMemoryDex callers, intentionally not supplied to prepare(). */
final class LateCallerChecks {
    private final ManagedHook.Handle handle;
    private final AtomicInteger intercepted=new AtomicInteger();
    private boolean active;
    private int calls;
    private final int initialBodies;
    private final JSONObject processInterpreter;
    private final JSONObject processBefore,processPrepared;
    LateCallerChecks() throws Exception {
        processBefore=new JSONObject(ManagedHook.processPolicy());
        ProbeFiles.require(!processBefore.getBoolean("owned")&&processBefore.getInt("claims")==0,"NO_EARLY_INTERPRETER_LEASE");
        initialBodies=CoverageTarget.bodyCalls.get();
        handle=ManagedHook.prepare(CoverageTarget.class.getDeclaredMethod("value",int.class),new Method[0]);
        processPrepared=new JSONObject(ManagedHook.processPolicy());
        ProbeFiles.require(!processPrepared.getBoolean("owned")&&processPrepared.getInt("claims")==0,"PREPARE_MUST_NOT_CLAIM_PROCESS_POLICY");
        handle.enable(call->{intercepted.incrementAndGet();return (Integer)call.original(call.arguments())+100;});active=true;
        processInterpreter=new JSONObject(ManagedHook.processPolicy());
        ProbeFiles.require(processInterpreter.getBoolean("owned")&&processInterpreter.getInt("observedLevel")==2
            &&processInterpreter.getInt("claims")==1,"CORE_PROCESS_INTERPRETER_REQUIRED");
    }
    private void invoke(Method caller,int value,File directory)throws Exception{
        Object result;
        try{result=caller.invoke(null,value);}catch(InvocationTargetException error){throw new IllegalStateException("LATE_CALLER_THROW",error.getCause());}
        calls++;
        if(!Integer.valueOf(value+101).equals(result)){
            ProbeFiles.write(new File(directory,"late-caller-failure.json"),new JSONObject().put("calls",calls).put("intercepted",intercepted.get())
                .put("actual",result).put("method",ArtProbe.sample(caller)).put("native",new JSONObject(handle.report())).toString(2)+"\n");
            throw new IllegalStateException("LATE_CALLER_BYPASSED_ACTIVE_HOOK");
        }
    }
    JSONObject finish(Class<?> first,Class<?> second,File directory)throws Exception{
        try{
            Method a=first.getDeclaredMethod("call",int.class),b=second.getDeclaredMethod("call",int.class);
            invoke(a,7,directory);invoke(b,9,directory);
            ProbeFiles.require(CoverageTarget.initializations.get()==2,"DISTINCT_LATE_CLASS_INITIALIZATION");
            int prime=100000;for(int i=0;i<prime;i++)invoke(b,7,directory);
            JSONObject sample=ArtProbe.sample(b);
            ProbeFiles.write(new File(directory,"late-caller-compilation.json"),sample.toString(2)+"\n");
            ProbeFiles.require(ArtCompilation.kind(sample).equals("runtime"),"LATE_CALLER_MUST_REMAIN_INTERPRETED");
            for(int i=0;i<1000;i++)invoke(b,i,directory);
            ProbeFiles.require(intercepted.get()==calls&&CoverageTarget.bodyCalls.get()-initialBodies==calls,"LATE_CALLER_EXACT_ORIGINALS");
            JSONObject state=new JSONObject(handle.report());
            handle.disable();active=false;
            ProbeFiles.require(b.invoke(null,7).equals(8)&&intercepted.get()==calls&&CoverageTarget.bodyCalls.get()-initialBodies==calls+1,"LATE_DISABLED_ORIGINAL");
            return new JSONObject().put("targetActiveBeforeLoaderCreation",true).put("knownCallersAtPrepare",0)
                .put("primingCalls",prime).put("compiledCalls",1000).put("totalIntercepted",calls).put("intercepted",intercepted.get())
                .put("originalBodyCalls",CoverageTarget.bodyCalls.get()-initialBodies).put("compiledCaller",sample).put("processInterpreter",processInterpreter)
                .put("processBefore",processBefore).put("processPrepared",processPrepared).put("processAfterDisable",new JSONObject(ManagedHook.processPolicy()))
                .put("activeState",state).put("disabledState",new JSONObject(handle.report()))
                .put("scope","two-owned-late-inmemory-loaders-process-interpreter").put("fullFutureCoverage",false);
        }finally{if(active){handle.disable();active=false;}}
    }
    void close(){if(active){handle.disable();active=false;}}
}
