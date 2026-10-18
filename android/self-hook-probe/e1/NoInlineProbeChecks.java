package dev.tripoder.selfhookprobe;

import android.content.Context;
import android.os.SystemClock;
import dalvik.system.InMemoryDexClassLoader;
import dev.tripoder.selfhook.OwnedIntHook;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import org.json.JSONObject;
import static dev.tripoder.selfhookprobe.ProbeFiles.*;

/** Fixed ART hypothesis test, using the existing owned int(int) backend unchanged. */
final class NoInlineProbeChecks {
    private static native int instrumentationLevel();
    static int knownCaller(int value){return CoverageTarget.value(value);}
    private int calls;private boolean prepared,active;private Method target,hot;
    private final JSONObject result;
    NoInlineProbeChecks(JSONObject result){this.result=result;}
    private void level(String name)throws Exception{int value=instrumentationLevel();result.put(name,value);require(value==0,"E1_GLOBAL_INSTRUMENTATION_NOT_ZERO:"+name);}
    private void call(Method caller,int value)throws Exception{
        Object actual=caller.invoke(null,value);calls++;
        if(!Integer.valueOf(value+101).equals(actual)){
            result.put("bypass",new JSONObject().put("call",calls).put("actual",actual).put("expected",value+101).put("caller",ArtProbe.sample(caller)));
            throw new IllegalStateException("E1_LATE_CALLER_BYPASSED");
        }
    }
    private JSONObject jit(Method method)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+10000;JSONObject sample;
        do{sample=ArtProbe.sample(method);if("jit".equals(ArtCompilation.kind(sample)))return sample;Thread.sleep(20);}while(SystemClock.elapsedRealtime()<deadline);
        result.put("jitNotObserved",sample);throw new IllegalStateException("E1_JIT_NOT_OBSERVED:"+method.getName());
    }
    void run(Context context,File directory)throws Exception{
        result.put("backend","owned-int-entry").put("genericManagedHook",false).put("productAccepted",false)
            .put("knownCallersAtPrepare",1).put("lateCallersKnownAtPrepare",0).put("primingCalls",100000).put("compiledCalls",1000);
        require(CoverageTarget.bodyCalls.get()==0&&CoverageTarget.initializations.get()==0,"E1_FRESH_TARGET_REQUIRED");
        target=CoverageTarget.class.getDeclaredMethod("value",int.class);result.put("targetBefore",ArtProbe.sample(target));
        require((result.getJSONObject("targetBefore").getJSONArray("words").getLong(1)&0x02000000)==0,"E1_PREEXISTING_COMPILE_DONT_BOTHER");
        level("levelBefore");
        require(OwnedIntHook.prepare(target,new Method[]{NoInlineProbeChecks.class.getDeclaredMethod("knownCaller",int.class)},100),"E1_PREPARE");prepared=true;
        result.put("prepared",new JSONObject(OwnedIntHook.report()));level("levelPrepared");
        require(OwnedIntHook.toggle(true),"E1_ENABLE");active=true;level("levelActive");
        result.put("targetActive",ArtProbe.sample(target));
        require((result.getJSONObject("targetActive").getJSONArray("words").getLong(1)&0x02000000)!=0,"E1_TARGET_COMPILE_GUARD_MISSING");
        byte[] dex;try(var input=context.getAssets().open("late-caller.dex")){dex=input.readAllBytes();}
        ClassLoader parent=NoInlineProbeChecks.class.getClassLoader();
        Class<?> first=Class.forName("dev.tripoder.selfhookprobe.dynamic.LateCaller",true,new InMemoryDexClassLoader(ByteBuffer.wrap(dex),parent));
        Class<?> second=Class.forName(first.getName(),true,new InMemoryDexClassLoader(ByteBuffer.wrap(dex),parent));
        require(first!=second&&first.getClassLoader()!=second.getClassLoader()&&CoverageTarget.initializations.get()==2,"E1_DISTINCT_LATE_LOADERS");
        result.put("targetActiveBeforeLoaderCreation",true).put("distinctLateLoaders",true);
        Method a=first.getDeclaredMethod("call",int.class);hot=second.getDeclaredMethod("call",int.class);
        Method control=second.getDeclaredMethod("local",Object.class);Object token=new Object();
        for(int i=0;i<100000;i++)require(control.invoke(null,token)==token,"E1_CONTROL_RESULT");
        result.put("controlJit",jit(control)).put("controlCalls",CoverageTarget.dynamicBodies.get());
        call(a,7);call(hot,9);for(int i=0;i<100000;i++)call(hot,7);
        result.put("callerJit",jit(hot));for(int i=0;i<1000;i++)call(hot,i);
        JSONObject state=new JSONObject(OwnedIntHook.report());result.put("activeState",state).put("totalIntercepted",calls);
        require(calls==101002&&state.getLong("callbacks")==calls&&state.getLong("originals")==calls&&CoverageTarget.bodyCalls.get()==calls,"E1_EXACT_INTERCEPTION_COUNTS");
        require(OwnedIntHook.toggle(false),"E1_DISABLE");active=false;
        require(hot.invoke(null,7).equals(8)&&new JSONObject(OwnedIntHook.report()).getLong("callbacks")==calls&&CoverageTarget.bodyCalls.get()==calls+1,"E1_DISABLED_ORIGINAL");
        result.put("disabledOriginalVerified",true);
    }
    void finish()throws Exception{
        if(target!=null)result.put("targetAtEnd",ArtProbe.sample(target));
        if(prepared){
            result.put("stateBeforeCleanup",new JSONObject(OwnedIntHook.report()));
            if(active){require(OwnedIntHook.toggle(false),"E1_CLEANUP_DISABLE");active=false;}
            result.put("disabledState",new JSONObject(OwnedIntHook.report())).put("targetDisabled",ArtProbe.sample(target));
            level("levelDisabled");
        }
        result.put("attemptedCalls",calls).put("originalBodyCalls",CoverageTarget.bodyCalls.get());
    }
}
