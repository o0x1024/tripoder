package dev.tripoder.selfhookprobe;

import android.app.Application;
import android.content.Context;
import dev.tripoder.selfhook.FrameworkAccess;
import dev.tripoder.selfhook.ManagedHook;
import dev.tripoder.selfhook.OriginalMethodStorage;
import dev.tripoder.selfhook.PhysicalApplication;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Fixed owned-process tests; same-name class identities remain distinct. */
final class LoaderTargetChecks {
    static JSONObject run(Context context,Class<?> first,Class<?> second)throws Exception{
        Method a=first.getDeclaredMethod("local",Object.class),b=second.getDeclaredMethod("local",Object.class);
        ProbeFiles.require(a.getDeclaringClass()!=b.getDeclaringClass()&&first.getClassLoader()!=second.getClassLoader(),"LOADER_TARGET_IDENTITIES");
        AtomicInteger intercepted=new AtomicInteger();Object token=new Object(),replacement=new Object();
        int initial=CoverageTarget.dynamicBodies.get();
        ManagedHook.Handle handle=ManagedHook.prepare(a,new Method[0]);boolean active=false;
        JSONObject dynamic;
        try{
            handle.enable(call->{intercepted.incrementAndGet();Object[] args=call.arguments();
                Object original=call.original(args);ProbeFiles.require(original==args[0],"DYNAMIC_ORIGINAL_OBJECT_IDENTITY");return replacement;});active=true;
            for(int i=0;i<1000;i++){
                Object value=i%7==0?null:token;
                ProbeFiles.require(a.invoke(null,value)==replacement&&b.invoke(null,value)==value,"SAME_NAME_LOADER_ISOLATION");
            }
            System.gc();Thread.sleep(30);
            ProbeFiles.require(a.invoke(null,token)==replacement&&b.invoke(null,token)==token,"DYNAMIC_ROOTS_AFTER_GC");
            ProbeFiles.require(intercepted.get()==1001&&CoverageTarget.dynamicBodies.get()-initial==2002,"DYNAMIC_EXACT_ORIGINALS");
            JSONObject enabled=new JSONObject(handle.report());handle.disable();active=false;
            ProbeFiles.require(a.invoke(null,token)==token&&intercepted.get()==1001,"DYNAMIC_DISABLED_ORIGINAL");
            dynamic=new JSONObject().put("sameNameDifferentLoaders",true).put("intercepted",1001).put("unownedCalls",1001)
                .put("bodyCalls",CoverageTarget.dynamicBodies.get()-initial).put("active",enabled).put("disabled",new JSONObject(handle.report()));
        }finally{if(active)handle.disable();}

        FrameworkAccess.Members members=FrameworkAccess.prepare();
        boolean unknownBootRejected=false;
        try{ManagedHook.prepare(Math.class.getDeclaredMethod("abs",int.class),new Method[0]);}
        catch(IllegalArgumentException expected){unknownBootRejected=expected.getMessage().startsWith("FIXED_BOOT_MANAGED_ADAPTER_REQUIRED:");}
        ProbeFiles.require(unknownBootRejected,"UNKNOWN_BOOT_ADAPTER_MUST_BE_REJECTED");
        Application host=(Application)context.getApplicationContext();PhysicalApplication.prepare(host,members);PhysicalApplication.check();
        Method current=(Method)members.methods[1];
        ProbeFiles.require(current.getDeclaringClass()==Class.forName("android.app.ActivityThread",false,null)
            &&current.invoke(null)==host,"BOOT_CURRENT_APPLICATION_BASELINE");
        Thread owner=Thread.currentThread();Application marker=new Application();AtomicInteger bootCalls=new AtomicInteger();
        ManagedHook.Handle boot=ManagedHook.prepare(current,new Method[0]);boolean bootActive=false;
        JSONObject framework;
        try{
            boot.enable(call->{Object original=call.original(call.arguments());
                if(Thread.currentThread()!=owner)return original;
                ProbeFiles.require(original==host,"BOOT_ORIGINAL_PHYSICAL_APPLICATION");bootCalls.incrementAndGet();return marker;});bootActive=true;
            for(int i=0;i<1000;i++)ProbeFiles.require(current.invoke(null)==marker,"BOOT_MANAGED_INTERCEPTION");
            PhysicalApplication.check();JSONObject enabled=new JSONObject(boot.report());
            boot.disable();bootActive=false;
            ProbeFiles.require(current.invoke(null)==host&&bootCalls.get()==1000,"BOOT_DISABLED_ORIGINAL");PhysicalApplication.check();
            framework=new JSONObject().put("declaringClass","android.app.ActivityThread").put("method","currentApplication")
                .put("intercepted",bootCalls.get()).put("originalIdentityPreserved",true).put("active",enabled)
                .put("disabled",new JSONObject(boot.report())).put("physical",new JSONObject(PhysicalApplication.reportNative()));
        }finally{if(bootActive)boot.disable();}
        JSONObject storage=new JSONObject(OriginalMethodStorage.report());
        ProbeFiles.require(storage.getInt("allocations")==3&&storage.getInt("rootedOutsideDeclaredTables")==3
            &&storage.getInt("bootAllocations")==1,"LOADER_STORAGE_ROOTS");
        return new JSONObject().put("dynamic",dynamic).put("framework",framework).put("storage",storage)
            .put("unknownBootRejected",unknownBootRejected)
            .put("callerSensitiveCovered",false).put("loaderUnloadCovered",false).put("productAccepted",false);
    }
}
