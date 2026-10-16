package dev.tripoder.selfhook;

import android.content.Context;
import android.os.Debug;
import android.os.SystemClock;
import dev.tripoder.runtime.ApkStore;
import java.io.File;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.*;

/** Probe only. DEX declarations are an independent oracle for the first registry enumeration. */
public final class FrameworkRootGcChecks {
    public static JSONObject run(Context context,JSONArray checks)throws Exception{
        JSONObject oracle;
        try(var input=context.getAssets().open("framework-root-oracle.json")){oracle=new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
        JSONArray jars=oracle.getJSONArray("jars"),roots=oracle.getJSONArray("roots");
        if(roots.length()!=55)throw new IllegalStateException("FRAMEWORK_ORACLE_ROOT_COUNT");
        for(int i=0;i<jars.length();i++){JSONObject jar=jars.getJSONObject(i);check(checks,"platform-jar:"+jar.getString("devicePath"),ApkStore.digest(new File(jar.getString("devicePath"))).equals(jar.getString("sha256")));}
        JSONObject fresh=new JSONObject(FrameworkAccess.statusNative());check(checks,"fresh-registry-before-pressure",!fresh.getBoolean("ready")&&fresh.getInt("calls")==0);
        AtomicBoolean running=new AtomicBoolean(true);AtomicLong allocated=new AtomicLong();AtomicReference<Throwable> failure=new AtomicReference<>();
        CountDownLatch pressureStarted=new CountDownLatch(1);
        Thread pressure=new Thread(()->{
            try{byte[][] live=new byte[32][];int next=0;while(running.get()){
                byte[] value=new byte[128*1024];value[0]=(byte)next;live[next++%live.length]=value;allocated.addAndGet(value.length);
                if(next==8)pressureStarted.countDown();
                if(next%16==0){System.gc();Thread.sleep(2);}
            }}catch(Throwable error){failure.set(error);}
        },"framework-root-gc-pressure");
        long before=gcCount(),started=SystemClock.elapsedRealtimeNanos();JSONArray observed=new JSONArray();
        pressure.start();
        try{
            if(!pressureStarted.await(5,TimeUnit.SECONDS))throw new IllegalStateException("FRAMEWORK_GC_PRESSURE_NOT_STARTED");
            // Invoke our own private JNI entry points, not hidden platform reflection.
            // No Java declaration cache is populated, so every ordinal is a first read.
            Method field=FrameworkAccess.class.getDeclaredMethod("fieldNative",int.class),count=FrameworkAccess.class.getDeclaredMethod("countNative",int.class),method=FrameworkAccess.class.getDeclaredMethod("methodNative",int.class,int.class);
            Method prepare=FrameworkAccess.class.getDeclaredMethod("prepareNative",Method.class,Method.class,Method.class);
            for(Method entry:List.of(field,count,method,prepare))entry.setAccessible(true);
            check(checks,"native-registry-prepared-under-pressure",Boolean.TRUE.equals(prepare.invoke(null,field,count,method)));
            for(int index=0;index<roots.length();index++){
                JSONObject expected=roots.getJSONObject(index);if(expected.getInt("owner")!=index)throw new IllegalStateException("FRAMEWORK_ORACLE_ORDER");
                Class<?> owner=FixedFrameworkMembers.owner(index);
                check(checks,"root-identity:"+index,owner.getName().equals(expected.getString("name")));
                List<String> declarations=new ArrayList<>();int nulls=0;
                int size=(Integer)count.invoke(null,index);if(size<1||size>1024)throw new IllegalStateException("FRAMEWORK_GC_METHOD_COUNT:"+index+":"+size);
                for(int ordinal=0;ordinal<size;ordinal++){
                    Thread.sleep(1);Object member=method.invoke(null,index,ordinal);
                    if(member==null){nulls++;continue;}
                    if(member instanceof Method){Method reflected=(Method)member;if(reflected.getDeclaringClass()!=owner)throw new SecurityException("FRAMEWORK_GC_DECLARING_OWNER");declarations.add(reflected.getName()+FixedFrameworkMembers.signature(reflected.getParameterTypes(),reflected.getReturnType()));}
                    else if(member instanceof Constructor<?>){Constructor<?> constructor=(Constructor<?>)member;if(constructor.getDeclaringClass()!=owner)throw new SecurityException("FRAMEWORK_GC_DECLARING_OWNER");declarations.add("<init>"+FixedFrameworkMembers.signature(constructor.getParameterTypes(),void.class));}
                    else throw new IllegalStateException("FRAMEWORK_GC_MEMBER_KIND");
                }
                Collections.sort(declarations);String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((String.join("\n",declarations)+"\n").getBytes(StandardCharsets.UTF_8)));
                JSONObject row=new JSONObject().put("owner",index).put("name",owner.getName()).put("count",declarations.size()).put("nullOrdinals",nulls).put("sha256",digest).put("gcCount",gcCount());observed.put(row);
                check(checks,"complete-dex-declarations:"+index+":"+row,digest.equals(expected.getString("sha256"))&&declarations.size()==expected.getJSONArray("methods").length());
                if(failure.get()!=null)throw new IllegalStateException("FRAMEWORK_GC_PRESSURE_FAILED",failure.get());
            }
        }finally{running.set(false);pressure.join(10000);if(pressure.isAlive())throw new IllegalStateException("FRAMEWORK_GC_PRESSURE_NOT_STOPPED");}
        if(failure.get()!=null)throw new IllegalStateException("FRAMEWORK_GC_PRESSURE_FAILED",failure.get());
        long after=gcCount();check(checks,"real-gc-during-first-enumeration",after-before>=10&&allocated.get()>=32*1024*1024&&SystemClock.elapsedRealtimeNanos()-started>=5000000000L);
        return new JSONObject().put("roots",observed).put("gcBefore",before).put("gcAfter",after).put("allocatedBytes",allocated.get()).put("elapsedNs",SystemClock.elapsedRealtimeNanos()-started)
            .put("registry",new JSONObject(FrameworkAccess.statusNative())).put("firstEnumeration",true).put("oracle",oracle.getString("oracleKind"));
    }
    private static long gcCount(){String value=Debug.getRuntimeStat("art.gc.gc-count");if(value==null)throw new IllegalStateException("FRAMEWORK_GC_COUNTER_UNAVAILABLE");return Long.parseLong(value);}
    private static void check(JSONArray checks,String name,boolean value)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",value));if(!value)throw new AssertionError(name);}
    private FrameworkRootGcChecks(){}
}
