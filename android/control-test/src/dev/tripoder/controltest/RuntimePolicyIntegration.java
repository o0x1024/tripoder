package dev.tripoder.controltest;

import android.content.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Real product ART policy: deferred fixed publication, late callbacks and warmed callers. */
public final class RuntimePolicyIntegration extends ControlTestBase {
    public static final class Callback extends BroadcastReceiver {
        int deliveries;boolean realPending;java.util.concurrent.CountDownLatch done;
        @Override public void onReceive(Context context,Intent intent){
            deliveries++;
            if(intent!=null&&done!=null){PendingResult pending=goAsync();realPending=pending!=null;if(pending!=null)pending.finish();done.countDown();}
        }
    }
    public static final class Target {
        public static int value(int input){return input*3+1;}
        public static int caller(int input){return value(input)+2;}
    }
    public static final class HotCaller {public static int call(int input){return Target.caller(input);}}
    private Class<?> hooks,handler;
    private Object originalHandler(AtomicInteger hits){
        return Proxy.newProxyInstance(handler.getClassLoader(),new Class<?>[]{handler},(proxy,method,args)->{
            if(!method.getName().equals("invoke"))throw new UnsupportedOperationException(method.getName());
            hits.incrementAndGet();Object call=args[0];Object[] values=(Object[])call.getClass().getMethod("arguments").invoke(call);
            try{return call.getClass().getMethod("original",Object[].class).invoke(call,(Object)values);}
            catch(InvocationTargetException error){throw error.getCause();}
        });
    }
    private Object prepare(Method target)throws Exception{return hooks.getMethod("prepare",Executable.class,Executable[].class).invoke(null,target,new Executable[0]);}
    private JSONObject policy()throws Exception{return new JSONObject((String)hooks.getMethod("processPolicy").invoke(null));}
    private String compiledMapping(Object handle)throws Exception{
        JSONObject state=new JSONObject((String)handle.getClass().getMethod("report").invoke(handle));long quick=Long.parseUnsignedLong(state.getString("targetQuick"),16);
        String maps=java.nio.file.Files.readString(java.nio.file.Path.of("/proc/self/maps"));
        for(String line:maps.split("\n")){
            int dash=line.indexOf('-'),space=line.indexOf(' ');if(dash<0||space<0)continue;
            if(Long.parseUnsignedLong(line,0,dash,16)<=quick&&quick<Long.parseUnsignedLong(line,dash+1,space,16))
                return line.contains("jit-code-cache")||line.contains("jit-cache")?"jit":"runtime";
        }return "unmapped";
    }
    @Override void test()throws Exception{
        report.put("scope","product-runtime-publication-and-callback-policy");System.loadLibrary("tripoderselfhook");
        hooks=Class.forName("dev.tripoder.selfhook.ManagedHook");handler=Class.forName("dev.tripoder.selfhook.ManagedHook$Handler");
        hooks.getMethod("initializeProcessForBootstrap",boolean.class).invoke(null,false);
        JSONObject initial=policy();check("preparation-does-not-deoptimize-process",!initial.getBoolean("owned")&&initial.getInt("publicationDeoptimizations")==0&&initial.getBoolean("bootstrapDeferred"),initial);
        Method target=Target.class.getMethod("value",int.class);Object fixed=prepare(target);AtomicInteger fixedHits=new AtomicInteger();
        hooks.getMethod("enableBatch",Map.class).invoke(null,Map.of(fixed,originalHandler(fixedHits)));
        hooks.getMethod("completeBootstrap").invoke(null);
        JSONObject ready=policy();check("fixed-publication-invalidates-once",ready.getInt("publicationDeoptimizations")==1&&ready.getBoolean("bootstrapComplete"),ready);
        check("bootstrap-restores-runtime-flags-and-jit",ready.getBoolean("runtimeStateRestored")&&ready.getBoolean("jitEnabled")&&ready.getInt("observedLevel")==0&&!ready.getBoolean("processLifetimeLease"),ready);
        check("fixed-entry-runs-original-once",Target.value(7)==22&&fixedHits.get()==1,fixedHits.get());
        Callback callback=new Callback();Method delivery=Callback.class.getMethod("onReceive",Context.class,Intent.class);
        for(int i=0;i<200000;i++)callback.onReceive(null,null);
        Object handle=prepare(delivery);AtomicInteger callbackHits=new AtomicInteger();
        hooks.getMethod("enableEntryBatch",Map.class).invoke(null,Map.of(handle,originalHandler(callbackHits)));
        int prior=callback.deliveries;delivery.invoke(callback,null,null);
        check("late-callback-reaches-entry-and-original-once",callbackHits.get()==1&&callback.deliveries==prior+1,callbackHits.get());
        callback.done=new java.util.concurrent.CountDownLatch(1);String action="dev.tripoder.controltest.POLICY_"+run;
        getTargetContext().registerReceiver(callback,new IntentFilter(action),Context.RECEIVER_NOT_EXPORTED);
        try{
            getTargetContext().sendBroadcast(new Intent(action).setPackage(enginePackage()));
            check("android-delivery-reaches-warmed-callback-and-pending-result",callback.done.await(10,java.util.concurrent.TimeUnit.SECONDS)&&callback.realPending&&callbackHits.get()==2,callbackHits.get());
        }finally{getTargetContext().unregisterReceiver(callback);}
        JSONObject late=policy();check("late-callback-does-not-repeat-global-deoptimization",late.getInt("publicationDeoptimizations")==1&&late.getInt("callbackPublications")==1,late);
        handle.getClass().getMethod("disable").invoke(handle);prior=callback.deliveries;delivery.invoke(callback,null,null);
        check("callback-disable-restores-original",callbackHits.get()==2&&callback.deliveries==prior+1,callback.deliveries);
        handle.getClass().getMethod("enable",handler).invoke(handle,originalHandler(callbackHits));
        check("entry-to-general-upgrade-invalidates-callers",policy().getInt("publicationDeoptimizations")==2,policy());
        handle.getClass().getMethod("disable").invoke(handle);
        fixed.getClass().getMethod("disable").invoke(fixed);
        Object callerProof=prepare(HotCaller.class.getMethod("call",int.class));
        long sum=0;String mapping="runtime";long until=android.os.SystemClock.elapsedRealtime()+5000;
        do{for(int i=0;i<300000;i++)sum+=HotCaller.call(i&31);android.os.SystemClock.sleep(50);mapping=compiledMapping(callerProof);}while(!mapping.equals("jit")&&android.os.SystemClock.elapsedRealtime()<until);
        report.put("warmChecksum",sum);check("unhooked-caller-actually-compiles-in-jit-cache",mapping.equals("jit"),mapping);
        Method caller=Target.class.getMethod("caller",int.class);Object lateCaller=prepare(caller);
        Object replacement=Proxy.newProxyInstance(handler.getClassLoader(),new Class<?>[]{handler},(proxy,method,args)->777);
        hooks.getMethod("enableBatch",Map.class).invoke(null,Map.of(lateCaller,replacement));
        check("late-general-hook-still-invalidates-compiled-callers",policy().getInt("publicationDeoptimizations")==3,policy());
        check("warmed-general-hook-changes-real-result",HotCaller.call(3)==777,HotCaller.call(3));
        lateCaller.getClass().getMethod("disable").invoke(lateCaller);
        check("general-disable-restores-result",HotCaller.call(3)==12,HotCaller.call(3));
        check("late-publication-restores-runtime-state",policy().getBoolean("runtimeStateRestored")&&policy().getBoolean("jitEnabled"),policy());
        boolean rejected=false;try{hooks.getMethod("enableEntryBatch",Map.class).invoke(null,Map.of(fixed,originalHandler(new AtomicInteger())));}catch(InvocationTargetException error){rejected=error.getCause() instanceof IllegalArgumentException;}
        check("callback-route-rejects-static-target",rejected,true);
        Class<?> mappings=Class.forName("dev.tripoder.runtime.RuntimeNativeMappingEvidence");Method select=mappings.getDeclaredMethod("relevantRowsNative",byte[].class,long[].class);select.setAccessible(true);
        Method parse=mappings.getDeclaredMethod("parse",byte[].class,String.class,long.class,long.class);parse.setAccessible(true);
        String core="/data/app/owned/lib/arm64/libtripoderselfhook.so";
        String raw="1000-2000 r-xp 0 00:00 7 "+core+"\n2000-3000 rw-p 0 00:00 0 [anon:helper]\n3000-4000 r-xp 0 00:00 8 /memfd:FrIdA\n4000-5000 r-xp 0 00:00 9 /tmp/普通文件\n";
        byte[] bytes=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);long[] counts=new long[1];byte[] selected=(byte[])select.invoke(null,bytes,counts);
        @SuppressWarnings("unchecked") Map<String,Object> complete=(Map<String,Object>)parse.invoke(null,bytes,core,7L,0L);
        @SuppressWarnings("unchecked") Map<String,Object> compact=(Map<String,Object>)parse.invoke(null,selected,core,7L,0L);
        check("native-scan-counts-all-rows-and-drops-only-irrelevant-rows",counts[0]==4&&selected.length<bytes.length,true);
        check("native-scan-preserves-core-foreign-and-native-evidence",List.of("coreRows","nativePathsSha256","nativeRowsSha256","foreignBackendRows").stream().allMatch(key->complete.get(key).equals(compact.get(key))),true);
        rejected=false;try{select.invoke(null,(raw+"bad-row\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),counts);}catch(InvocationTargetException error){rejected=error.getCause() instanceof SecurityException;}
        check("native-scan-rejects-malformed-unselected-rows",rejected,true);
    }
}
