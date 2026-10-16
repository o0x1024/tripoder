package dev.tripoder.selfhookprobe;

import android.app.Application;
import dalvik.system.DexClassLoader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Logical Application view through the original native reflection access frame. */
public final class ReflectionViewProbe {
    private static native boolean prepare(Field field,Object owner,Application physical,Application logical,Class<?> valueType);
    private static native boolean toggle(boolean enabled);
    private static native String report();
    private int checks;
    private final JSONArray failures=new JSONArray();
    private void check(boolean value,String name) { checks++;if(!value)failures.put(name); }
    private static String getError(Field field,Object owner) {
        try {field.get(owner);return "NONE";}catch(Throwable error){return error.getClass().getName();}
    }
    private static String setError(Field field,Object owner,Object value) {
        try {field.set(owner,value);return "NONE";}catch(Throwable error){return error.getClass().getName();}
    }
    private static int hotReadWrite(Field field,Object owner,Object expected) throws Exception {
        int errors=0;
        for(int i=0;i<10000;i++){field.set(owner,expected);if(field.get(owner)!=expected)errors++;}
        return errors;
    }
    public static JSONObject run(String compilation,String apkPath) throws Exception {
        return new ReflectionViewProbe().execute(compilation,apkPath);
    }
    private JSONObject execute(String compilation,String apkPath) throws Exception {
        if(!"jit".equals(compilation)&&!"aot".equals(compilation))throw new IllegalArgumentException("EXPLICIT_ART_COMPILATION_REQUIRED");
        JSONObject result=new JSONObject();
        Application physical=new Application(),logical=new Application(),different=new Application();
        AppIdentityFixture owner=new AppIdentityFixture(physical),otherOwner=new AppIdentityFixture(physical);
        Field field=AppIdentityFixture.class.getDeclaredField("current");
        Field copy=AppIdentityFixture.class.getDeclaredField("current");
        Field other=AppIdentityFixture.class.getDeclaredField("other");
        String deniedGet=getError(field,owner),deniedSet=setError(field,owner,physical);
        String privateWrongGet=getError(field,new Object()),privateNullGet=getError(field,null);
        String privateWrongSet=setError(field,owner,new Object()),privateNullSet=setError(field,null,physical);
        check(deniedGet.equals("java.lang.IllegalAccessException")&&deniedSet.equals(deniedGet),"BASELINE_PRIVATE_ACCESS_DENIED");
        field.setAccessible(true);copy.setAccessible(true);
        String wrongGet=getError(field,new Object()),nullGet=getError(field,null),wrongSet=setError(field,owner,new Object());
        check(field.get(owner)==physical&&owner.physical()==physical,"ORIGINAL_PHYSICAL_APPLICATION");
        check(prepare(field,owner,physical,logical,Application.class),"PREPARE_IMMUTABLE_FIELD_IDENTITY");
        if(failures.length()>0)return finish(result);
        boolean active=false;
        try {
            active=toggle(true);check(active,"REGISTER_BOTH_NATIVE_REFLECTION_ENTRIES");
            if(!active)return finish(result);
            check(field.get(owner)==logical&&owner.physical()==physical,"LOGICAL_READ_PHYSICAL_STORAGE_UNCHANGED");
            check(copy.get(owner)==logical,"COPIED_FIELD_OBJECT_SAME_FIELD_ID");
            check(field.get(otherOwner)==physical&&other.get(owner)==physical,"OWNER_AND_FIELD_IDENTITIES_SCOPED");
            field.set(owner,logical);
            check(field.get(owner)==logical&&owner.physical()==physical,"LOGICAL_WRITE_PRESERVES_PHYSICAL_STORAGE");
            check(setError(field,owner,physical).equals("java.lang.SecurityException")&&owner.physical()==physical,"PHYSICAL_WRITE_DENIED_AFTER_ORIGINAL_CHECK");
            check(setError(field,owner,null).equals("java.lang.SecurityException")&&owner.physical()==physical,"NULL_WRITE_DENIED_WITH_PHYSICAL_IDENTITY_RETAINED");
            check(setError(field,owner,different).equals("java.lang.SecurityException")&&owner.physical()==physical,"OTHER_APPLICATION_WRITE_DENIED");
            check(setError(field,owner,new Object()).equals(wrongSet),"ORIGINAL_INVALID_VALUE_TYPE_ORDER");
            check(getError(field,new Object()).equals(wrongGet)&&getError(field,null).equals(nullGet),"ORIGINAL_OWNER_TYPE_AND_NULL_ERRORS");
            field.setAccessible(false);
            check(getError(field,owner).equals(deniedGet)&&setError(field,owner,logical).equals(deniedSet),"ORIGINAL_PRIVATE_CALLER_FRAME");
            check(getError(field,new Object()).equals(privateWrongGet)&&getError(field,null).equals(privateNullGet),"PRIVATE_GET_ERROR_ORDER");
            check(setError(field,owner,new Object()).equals(privateWrongSet)&&setError(field,null,logical).equals(privateNullSet),"PRIVATE_SET_ERROR_ORDER");
            field.setAccessible(true);
            owner.physical(different);
            check(getError(field,owner).equals("java.lang.SecurityException"),"UNEXPECTED_PHYSICAL_IDENTITY_READ_DENIED");
            owner.physical(physical);
            check(field.get(owner)==logical,"OWNED_FIXTURE_PHYSICAL_IDENTITY_RESTORED");
            field.set(otherOwner,different);
            check(otherOwner.physical()==different,"UNMAPPED_OWNER_WRITE_UNCHANGED");
            DexClassLoader secondLoader=new DexClassLoader(apkPath,null,null,ClassLoader.getSystemClassLoader().getParent());
            Class<?> secondType=secondLoader.loadClass(AppIdentityFixture.class.getName());
            Object secondOwner=secondType.getConstructor(Application.class).newInstance(physical);
            Field secondField=secondType.getDeclaredField("current");secondField.setAccessible(true);
            check(secondType!=AppIdentityFixture.class&&secondType.getClassLoader()==secondLoader&&secondField.get(secondOwner)==physical,
                "DISTINCT_LOADER_FIELD_NOT_MAPPED");
            for(int i=0;i<8;i++) {
                byte[][] pressure=new byte[8][];
                for(int j=0;j<pressure.length;j++)pressure[j]=new byte[32768];
                System.gc();
                check(field.get(owner)==logical&&owner.physical()==physical,"IDENTITY_ROOTS_AFTER_GC_REQUEST");
            }
            Method hot=ReflectionViewProbe.class.getDeclaredMethod("hotReadWrite",Field.class,Object.class,Object.class);
            long deadline=android.os.SystemClock.elapsedRealtime()+10000;
            JSONObject before;
            int primeErrors=0,primeRounds=0;
            do {
                primeErrors+=(Integer)hot.invoke(null,field,owner,logical);
                before=ArtProbe.sample(hot);primeRounds++;
                if(compilation.equals(ArtCompilation.kind(before)))break;
                if(android.os.SystemClock.elapsedRealtime()>=deadline)throw new IllegalStateException("REQUESTED_COMPILATION_NOT_OBSERVED:"+compilation);
                Thread.sleep(10);
            }while(true);
            check(primeErrors==0,"COMPILED_REFLECTION_IDENTITY_CALLER_PRIMED");
            long mappedBefore=new JSONObject(report()).getLong("mappedGets");
            long setBefore=new JSONObject(report()).getLong("mappedSets");
            int errors=0;
            for(int i=0;i<100;i++)errors+=(Integer)hot.invoke(null,field,owner,logical);
            long mappedAfter=new JSONObject(report()).getLong("mappedGets");
            long setAfter=new JSONObject(report()).getLong("mappedSets");
            check(errors==0&&mappedAfter-mappedBefore==1000000&&setAfter-setBefore==1000000,"COMPILED_MILLION_IDENTITY_READ_WRITE_PAIRS_EXACT");
            boolean hotPhysicalUnchanged=owner.physical()==physical;
            check(hotPhysicalUnchanged,"COMPILED_LOGICAL_WRITES_KEEP_PHYSICAL_APPLICATION");
            result.put("requestedCompilation",compilation).put("hotMethodBefore",before).put("hotMethodAfter",ArtProbe.sample(hot))
                .put("hotCalls",1000000).put("hotMapped",mappedAfter-mappedBefore).put("hotErrors",errors).put("primeRounds",primeRounds)
                .put("hotInvocation","Method.invoke").put("hotSetMapped",setAfter-setBefore).put("hotPhysicalUnchanged",hotPhysicalUnchanged);
            check(toggle(false),"RESTORE_BOTH_ORIGINAL_NATIVE_ENTRIES");active=false;
            check(field.get(owner)==physical,"DISABLED_ORIGINAL_PHYSICAL_READ");
            field.set(owner,different);check(owner.physical()==different,"DISABLED_ORIGINAL_WRITE");owner.physical(physical);
            AtomicBoolean stop=new AtomicBoolean();AtomicInteger calls=new AtomicInteger(),workerErrors=new AtomicInteger();
            Thread[] workers=new Thread[4];
            for(int i=0;i<workers.length;i++) {
                workers[i]=new Thread(()->{while(!stop.get())try{Object value=field.get(owner);
                    if(value!=physical&&value!=logical)workerErrors.incrementAndGet();calls.incrementAndGet();
                }catch(Throwable error){workerErrors.incrementAndGet();}},"reflection-view-"+i);workers[i].start();
            }
            int cycles=0;
            try {
                for(;cycles<20;cycles++) {
                    active=toggle(true);check(active,"CONCURRENT_REFLECTION_ACTIVATE");if(!active)break;
                    Thread.sleep(2);
                    boolean disabled=toggle(false);check(disabled,"CONCURRENT_REFLECTION_RESTORE");if(!disabled)break;
                    active=false;Thread.sleep(2);
                }
            }finally{stop.set(true);for(Thread worker:workers)worker.join();}
            check(cycles==20&&calls.get()>0&&workerErrors.get()==0,"REFLECTION_VIEW_CONCURRENT_IDENTITIES");
            check(field.get(owner)==physical&&owner.physical()==physical,"FINAL_ORIGINAL_IDENTITY_AND_STORAGE");
            result.put("cycles",cycles).put("workerCalls",calls.get()).put("workerErrors",workerErrors.get());
        }finally{if(active)check(toggle(false),"FINAL_RESTORE");}
        return finish(result);
    }
    private JSONObject finish(JSONObject result) throws Exception {
        return result.put("checks",checks).put("failures",failures).put("passed",failures.length()==0)
            .put("binding",new JSONObject(report())).put("nativeReflectionView",true)
            .put("realActivityThreadConfigured",false).put("genericJavaHook",false).put("gcMovementProven",false);
    }
}
