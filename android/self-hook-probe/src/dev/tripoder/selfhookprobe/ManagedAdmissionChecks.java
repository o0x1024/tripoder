package dev.tripoder.selfhookprobe;
import dalvik.system.InMemoryDexClassLoader;
import dev.tripoder.selfhook.ManagedHook;
import dev.tripoder.selfhook.OriginalMethodStorage;
import java.io.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Preparation/publication under moving GC, with initialized and uninitialized owners. */
final class ManagedAdmissionChecks {
    private static native boolean bind(Class<?> owner);
    private static void checkpoint(File directory,int round,String stage,Object detail)throws Exception{
        try(var out=new FileOutputStream(new File(directory,"progress.json"))){
            out.write(new JSONObject().put("round",round).put("stage",stage).put("detail",detail).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();
        }
    }
    static JSONObject run(android.content.Context context,File directory)throws Throwable{
        byte[] dex;try(var in=context.getAssets().open("jni-guest.dex")){dex=in.readAllBytes();}
        Map<Executable,Executable[]> occupied=new LinkedHashMap<>();
        for(int slot=0;slot<2304;slot++){
            Class<?> owner=Class.forName("dev.tripoder.selfhookprobe.capacity.Group"+(slot/128));
            occupied.put(owner.getDeclaredMethod("value"+(slot%128),int.class),new Executable[0]);
        }
        checkpoint(directory,-1,"BEFORE_PREFILL",occupied.size());
        var existing=ManagedHook.prepareBatch(occupied);Map<ManagedHook.Handle,ManagedHook.Handler> handlers=new LinkedHashMap<>();
        for(var handle:existing.values())handlers.put(handle,call->call.original(call.arguments()));ManagedHook.enableBatch(handlers);
        checkpoint(directory,-1,"PREFILLED",existing.size());
        long gcBefore=Long.parseLong(android.os.Debug.getRuntimeStat("art.gc.gc-count"));
        AtomicBoolean running=new AtomicBoolean(true);AtomicInteger collections=new AtomicInteger();AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread gc=new Thread(()->{try{byte[][] live=new byte[16][];int i=0;while(running.get()){
            live[i++%live.length]=new byte[65536];if(i%8==0){System.gc();collections.incrementAndGet();}Thread.sleep(1);
        }}catch(Throwable error){failure.set(error);}},"managed-publication-gc");gc.start();
        int checks=0;JSONArray rounds=new JSONArray();JSONObject namedResult=new JSONObject();
        try{for(int round=0;round<32;round++){
            System.clearProperty("tripoder.jni.sdk.initialized");
            ClassLoader loader=new InMemoryDexClassLoader(ByteBuffer.wrap(dex),ManagedAdmissionChecks.class.getClassLoader());
            Class<?> owner=Class.forName("dev.tripoder.selfhookprobe.jniguest.JniSdkTarget",false,loader);
            if(!bind(owner)||System.getProperty("tripoder.jni.sdk.initialized")!=null)throw new AssertionError("REGISTER_INITIALIZATION");checks++;
            Method target=owner.getDeclaredMethod("read",String.class);int mode=round%3;
            if(mode==1)Class.forName(owner.getName(),true,loader);
            if(mode==2&&!Boolean.TRUE.equals(target.invoke(null,"owned")))throw new AssertionError("RESOLVED_BASELINE");
            checkpoint(directory,round,"BEFORE_PREPARE",mode);
            ManagedHook.Handle handle=ManagedHook.prepare(target,new Executable[0]);
            if(mode==0&&System.getProperty("tripoder.jni.sdk.initialized")!=null)throw new AssertionError("PREPARE_INITIALIZED_OWNER");checks++;
            JSONObject prepared=new JSONObject(handle.report());checkpoint(directory,round,"PREPARED",prepared);
            for(int cycle=0;cycle<8;cycle++){
                System.gc();checkpoint(directory,round,"BEFORE_ENABLE",cycle);
                handle.enable(call->{if(!Boolean.TRUE.equals(call.original(call.arguments())))throw new AssertionError("ORIGINAL_VALUE");return false;});
                if(!Boolean.FALSE.equals(target.invoke(null,"owned")))throw new AssertionError("HOOK_VALUE");checks++;
                handle.disable();if(!Boolean.TRUE.equals(target.invoke(null,"owned")))throw new AssertionError("RESTORED_VALUE");checks++;
            }
            JSONObject retired=new JSONObject(handle.report());
            if(retired.getBoolean("active")||retired.getInt("transitions")!=16||!retired.getBoolean("backupRooted"))throw new AssertionError("RETIRED_OWNERSHIP");checks++;
            rounds.put(new JSONObject().put("round",round).put("mode",mode).put("prepared",prepared).put("retired",retired));
        }
            Class<?> namedOwner=Class.forName("dev.tripoder.selfhookprobe.ManagedAdmissionNamedTarget",false,ManagedAdmissionChecks.class.getClassLoader());
            Method named=namedOwner.getDeclaredMethod("read",String.class);checkpoint(directory,32,"BEFORE_NAMED_RESOLUTION",10000);
            for(int i=0;i<10000;i++)if(!Boolean.TRUE.equals(named.invoke(null,"owned")))throw new AssertionError("NAMED_BASELINE");
            if(!"yes".equals(System.getProperty("tripoder.managed.admission.named.initialized")))throw new AssertionError("NAMED_INITIALIZATION");checks++;
            ManagedHook.Handle handle=ManagedHook.prepare(named,new Executable[0]);namedResult.put("prepared",new JSONObject(handle.report()));
            for(int cycle=0;cycle<64;cycle++){
                checkpoint(directory,32,"BEFORE_NAMED_ENABLE",cycle);
                handle.enable(call->{if(!Boolean.TRUE.equals(call.original(call.arguments())))throw new AssertionError("NAMED_ORIGINAL");return false;});
                System.gc();if(!Boolean.FALSE.equals(named.invoke(null,"owned")))throw new AssertionError("NAMED_HOOK");checks++;
                handle.disable();if(!Boolean.TRUE.equals(named.invoke(null,"owned")))throw new AssertionError("NAMED_RESTORED");checks++;
            }
            JSONObject retired=new JSONObject(handle.report());
            if(retired.getBoolean("active")||retired.getInt("transitions")!=128||retired.getInt("originals")!=64||!retired.getBoolean("backupRooted"))throw new AssertionError("NAMED_RETIRED_OWNERSHIP");checks++;
            namedResult.put("retired",retired).put("warmCalls",10000);
        }finally{running.set(false);gc.join(10000);}
        if(gc.isAlive()||failure.get()!=null||collections.get()==0)throw new AssertionError("GC_WORKER",failure.get());
        long gcAfter=Long.parseLong(android.os.Debug.getRuntimeStat("art.gc.gc-count"));
        String rootsText=OriginalMethodStorage.report();JSONObject roots=new JSONObject(rootsText);
        if(gcAfter<=gcBefore||roots.getInt("published")!=2337||roots.getInt("rootedOutsideDeclaredTables")!=2337||roots.getInt("observedContainerRootMoves")==0)throw new AssertionError("ACTUAL_GC_ROOT_EVIDENCE");
        File rootFile=new File(directory,"storage-roots.json");ProbeFiles.write(rootFile,rootsText);checks++;
        checkpoint(directory,33,"COMPLETE",checks);
        ManagedHook.disableBatch(existing.values());
        return new JSONObject().put("passed",true).put("checks",checks).put("rounds",rounds).put("named",namedResult).put("gcRequests",collections.get()).put("actualGcCount",gcAfter-gcBefore).put("storageRootsSha256",ProbeFiles.sha(rootFile)).put("transitions",640).put("prefilledEntries",existing.size());
    }
}
