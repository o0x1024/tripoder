package dev.tripoder.runtime;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import dev.tripoder.selfhook.ManagedHook;
import java.lang.reflect.*;
import java.util.*;
import org.json.JSONObject;

/** Owned warmed callers and covariant bridge; no guest admission or AMS task. */
final class InstrumentationEntryChecks {
    private int checks;
    private static int narrowCalls,parentCalls,dispatchCalls;
    private static OwnedActivity ownedResult;
    private static final Intent INTENT=new Intent("dev.tripoder.owned.INSTRUMENTATION_ENTRY");
    private void check(boolean passed,String name){checks++;if(!passed)throw new AssertionError(name);}
    public static final class OwnedActivity extends Activity {}
    public static class Parent extends Instrumentation {
        @Override public Activity newActivity(ClassLoader loader,String name,Intent intent){
            if(loader!=InstrumentationEntryChecks.class.getClassLoader()||!name.equals(OwnedActivity.class.getName())||intent!=INTENT)
                throw new AssertionError("OWNED_CONSTRUCTION_ARGUMENT_IDENTITY");
            parentCalls++;return ownedResult;
        }
    }
    public static final class Inherited extends Parent {}
    public static class Covariant extends Parent {
        @Override public OwnedActivity newActivity(ClassLoader loader,String name,Intent intent){narrowCalls++;return (OwnedActivity)super.newActivity(loader,name,intent);}
    }
    public static final class InheritedCovariant extends Covariant {}
    public static final class Passive extends Instrumentation {}
    public static final class NativeOverride extends Parent {
        @Override public native Activity newActivity(ClassLoader loader,String name,Intent intent);
    }
    public static final class Dispatch {
        public static Activity invoke(Instrumentation instrumentation)throws Exception{
            dispatchCalls++;return instrumentation.newActivity(InstrumentationEntryChecks.class.getClassLoader(),OwnedActivity.class.getName(),INTENT);
        }
    }
    JSONObject run()throws Exception{
        Method bridge=RuntimeInstrumentationMethods.construction(Covariant.class);
        check(bridge.getDeclaringClass()==Covariant.class&&bridge.getReturnType()==Activity.class&&bridge.isBridge(),"EXACT_SDK_DESCRIPTOR_SELECTS_COVARIANT_BRIDGE");
        Method narrow=Covariant.class.getDeclaredMethod("newActivity",ClassLoader.class,String.class,Intent.class);
        check(narrow.getReturnType()==OwnedActivity.class&&!narrow.equals(bridge),"PARAMETER_ONLY_REFLECTION_HAS_DIFFERENT_COVARIANT_TARGET");
        check(RuntimeInstrumentationMethods.construction(InheritedCovariant.class).equals(bridge),"INHERITED_COVARIANT_HAS_SAME_DECLARING_OWNER");
        check(RuntimeInstrumentationMethods.construction(Inherited.class).getDeclaringClass()==Parent.class,"INHERITED_REGULAR_HAS_SAME_DECLARING_OWNER");
        check(RuntimeInstrumentationMethods.construction(Passive.class).getDeclaringClass()==Instrumentation.class,"NO_OVERRIDE_SELECTS_EXISTING_FIXED_BASE");
        check(Modifier.isNative(RuntimeInstrumentationMethods.construction(NativeOverride.class).getModifiers()),"NATIVE_EXACT_OVERRIDE_IS_NOT_SKIPPED_FOR_PARENT");
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())throw new IllegalStateException("OWNED_CONSTRUCTION_MAIN_THREAD_REQUIRED");
        ownedResult=new OwnedActivity();Instrumentation warmed=new Covariant();
        RuntimeManagedInstaller installer=new RuntimeManagedInstaller(Map.of(),true);
        Method dispatch=Dispatch.class.getMethod("invoke",Instrumentation.class);
        installer.install(Map.of(dispatch,(receiver,args,original)->original.call(args)));installer.publishPrepared();
        ManagedHook.completeBootstrap();JSONObject ready=new JSONObject(ManagedHook.processPolicy());
        check(ready.getBoolean("bootstrapComplete")&&ready.getBoolean("runtimeStateRestored"),"REAL_BOOTSTRAP_BOUNDARY_COMPLETED_BEFORE_LATE_CALLBACKS");
        for(int i=0;i<20000;i++)Dispatch.invoke(warmed);
        check(narrowCalls==20000&&parentCalls==20000&&dispatchCalls==20000,"WARMED_COVARIANT_AND_SUPER_CHAIN_ORACLE");
        JSONObject before=new JSONObject(ManagedHook.processPolicy());
        Constructor<RuntimeSelfHookActivities> constructor=RuntimeSelfHookActivities.class.getDeclaredConstructor();constructor.setAccessible(true);
        RuntimeSelfHookActivities owner=constructor.newInstance();
        Field managed=RuntimeSelfHookActivities.class.getDeclaredField("installer");managed.setAccessible(true);managed.set(owner,installer);
        Field identities=RuntimeSelfHookActivities.class.getDeclaredField("instrumentations");identities.setAccessible(true);
        @SuppressWarnings("unchecked") Set<Class<?>> seen=(Set<Class<?>>)identities.get(owner);seen.add(Instrumentation.class);
        Method enroll=RuntimeSelfHookActivities.class.getDeclaredMethod("enroll",Instrumentation.class);enroll.setAccessible(true);
        int capacity=ManagedHook.remainingCapacity();enroll.invoke(owner,new Covariant());enroll.invoke(owner,new InheritedCovariant());
        check(capacity-ManagedHook.remainingCapacity()==1&&ManagedHook.ownsTarget(bridge)&&!ManagedHook.ownsTarget(narrow),"ACTUAL_ENROLL_INSTALLS_ONLY_EXACT_BRIDGE_ONCE");
        enroll.invoke(owner,new Parent());enroll.invoke(owner,new Inherited());enroll.invoke(owner,new Passive());
        check(capacity-ManagedHook.remainingCapacity()==2&&seen.size()==3,"ACTUAL_ENROLL_DEDUPLICATES_DECLARING_CLASS_IDENTITIES");
        JSONObject after=new JSONObject(ManagedHook.processPolicy());
        check(after.getInt("publicationDeoptimizations")==before.getInt("publicationDeoptimizations"),"LATE_THICK_ENROLL_DOES_NOT_REPEAT_GLOBAL_INVALIDATION");
        check(after.getInt("callbackPublications")==before.getInt("callbackPublications")+2,"LATE_THICK_ENROLL_PUBLISHES_BOTH_CALLBACK_ENTRIES");
        narrowCalls=parentCalls=dispatchCalls=0;
        Activity result=Dispatch.invoke(warmed);
        check(result==ownedResult&&narrowCalls==1&&parentCalls==1&&dispatchCalls==1,"INTERPRETED_ORIGINAL_VIRTUAL_DISPATCH_REACHES_BRIDGE_AND_SUPER_ONCE");
        result=Dispatch.invoke(new InheritedCovariant());
        check(result==ownedResult&&narrowCalls==2&&parentCalls==2&&dispatchCalls==2,"INHERITED_WARMED_BRIDGE_DELIVERY_RETAINS_RESULT_IDENTITY");
        return new JSONObject().put("passed",true).put("checks",checks).put("warmCalls",20000)
            .put("lateCallbackPublications",2).put("additionalGlobalInvalidations",0).put("before",before).put("after",after)
            .put("actualProductEnroller",true).put("guestAdmissionAccepted",false).put("arbitraryCompiledCallerCoverage",false);
    }
}
