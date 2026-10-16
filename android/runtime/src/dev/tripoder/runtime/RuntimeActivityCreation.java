package dev.tripoder.runtime;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.os.*;
import java.util.*;

/** One real framework construction, including a guest Instrumentation's final replacement. */
public final class RuntimeActivityCreation {
    private static final ThreadLocal<Permit> CURRENT=new ThreadLocal<>();
    private static final Map<Activity,Permit> ATTACHMENTS=new IdentityHashMap<>();
    private static final Map<Activity,Intent> REJECTIONS=new IdentityHashMap<>();
    private static final Map<ActivityInfo,Integer> THEMES=Collections.synchronizedMap(new WeakHashMap<>());
    public static final class Permit {
        final RuntimeEngine engine;final Intent physical,visible;final String ticket,instance,name,rejected;final Bundle caller;
        private final long thread=Thread.currentThread().getId();private String phase="PREPARED";private Object record;private IBinder token;
        Permit(String rejected){this.engine=null;physical=visible=null;ticket=instance=name=null;caller=null;this.rejected=rejected;}
        Permit(RuntimeEngine engine,Intent physical,Intent visible,String ticket,String instance,String name,Bundle caller){this.engine=engine;this.physical=new Intent(physical);this.visible=visible;this.ticket=ticket;this.instance=instance;this.name=name;this.caller=caller;rejected=null;}
        public boolean rejected(){return rejected!=null;}
        public boolean constructing(){return phase.equals("CONSTRUCTING");}
        public ClassLoader loader(){require();return engine.loader;}
        public String name(){require();return name;}
        public Intent intent(){require();return visible;}
        private void require(){if(CURRENT.get()!=this||thread!=Thread.currentThread().getId()||rejected())throw new SecurityException("ACTIVITY_CREATION_PERMIT_INVALID");}
        public void enter(){require();if(!phase.equals("PREPARED"))throw new SecurityException("ACTIVITY_CREATION_REENTERED");phase="CONSTRUCTING";}
        public void bindRecord(Object record,IBinder token){require();if(this.record!=null||record==null||token==null)throw new SecurityException("ACTIVITY_CREATION_RECORD_INVALID");this.record=record;this.token=token;}
        public void requireArguments(String physicalName,Intent supplied){require();if(!RuntimeActivitySlots.target(physical.getComponent().getClassName()).equals(physicalName)||supplied==null||!Objects.equals(physical.getComponent(),supplied.getComponent())||!Objects.equals(ticket,supplied.getStringExtra("tripoder.ticket"))||!Objects.equals(physical.getStringExtra("tripoder.delivery"),supplied.getStringExtra("tripoder.delivery")))throw new SecurityException("ACTIVITY_CREATION_ARGUMENTS_MISMATCH");}
        public Activity complete(Activity activity){require();if(!constructing())throw new SecurityException("ACTIVITY_CREATION_PHASE_INVALID");
            if(activity==null||activity.getBaseContext()!=null||engine.ownsActivity(activity)||!engine.ownsLoader(activity.getClass().getClassLoader()))throw new SecurityException("ACTIVITY_CREATION_RESULT_NOT_FRESH_OWNED_INSTANCE");
            ComponentName expected=new ComponentName(engine.packageName,RuntimeWire.required(physical.getExtras(),"tripoder.component"));
            if(!expected.equals(visible.getComponent())||visible.getPackage()!=null&&!engine.packageName.equals(visible.getPackage()))throw new SecurityException("INSTRUMENTATION_CHANGED_COMPONENT_IDENTITY");
            engine.registerCreation(this,activity);synchronized(ATTACHMENTS){if(ATTACHMENTS.put(activity,this)!=null)throw new SecurityException("ACTIVITY_CREATION_INSTANCE_REUSED");}phase="CREATED";return activity;
        }
        public void end(){if(CURRENT.get()!=this||thread!=Thread.currentThread().getId())throw new SecurityException("ACTIVITY_CREATION_THREAD_MISMATCH");CURRENT.remove();}
    }
    public static Permit current(){return CURRENT.get();}
    public static boolean physical(Intent intent){return intent!=null&&intent.getComponent()!=null&&RuntimeApplication.host!=null&&RuntimeApplication.host.getPackageName().equals(intent.getComponent().getPackageName())&&RuntimeActivitySlots.owns(intent.getComponent().getClassName());}
    public static Permit begin(Intent physical)throws Exception{
        if(CURRENT.get()!=null)throw new SecurityException("ACTIVITY_CREATION_NESTED_LAUNCH_UNSUPPORTED");
        if(!physical(physical))throw new SecurityException("ACTIVITY_PHYSICAL_COMPONENT_MISMATCH");
        String rejection=RuntimeFactory.bootstrap(physical.getComponent().getClassName(),"restoreActivity",RuntimeWire.required(physical.getExtras(),"tripoder.ticket"));
        Permit permit=rejection==null?RuntimeEngine.current.claimCreation(physical):new Permit(rejection);
        CURRENT.set(permit);try{if(!permit.rejected())permit.engine.startApplication();return permit;}catch(Throwable error){CURRENT.remove();throw error;}
    }
    public static Activity constructDefault(Instrumentation instrumentation,ClassLoader loader,String name,Intent visible)throws Exception{
        Permit permit=CURRENT.get();if(permit==null)throw new SecurityException("ACTIVITY_CREATION_PERMIT_MISSING");permit.require();
        if(!permit.constructing()||!permit.engine.ownsLoader(loader)||visible==null||visible.getComponent()==null||!permit.engine.packageName.equals(visible.getComponent().getPackageName()))throw new SecurityException("ACTIVITY_CONSTRUCTION_ARGUMENTS_INVALID");
        // Preserve Android's app-created Instrumentation rule: an unbound instance uses DEFAULT.
        AppComponentFactory factory=RuntimeBridge.instrumentationBound(instrumentation)?permit.engine.componentFactory:new AppComponentFactory();
        return factory.instantiateActivity(loader,name,visible);
    }
    public static void attaching(Activity activity,Intent physical,ActivityInfo info,IBinder token){
        Permit permit;synchronized(ATTACHMENTS){permit=ATTACHMENTS.remove(activity);}Intent expected=permit==null?null:permit.physical;
        if(permit!=null&&permit.token!=null&&!permit.token.equals(token))throw new SecurityException("ACTIVITY_ATTACH_TOKEN_MISMATCH");
        if(expected==null||physical==null||info==null||!Objects.equals(expected.getComponent(),physical.getComponent())||!Objects.equals(expected.getStringExtra("tripoder.ticket"),physical.getStringExtra("tripoder.ticket"))||!Objects.equals(expected.getStringExtra("tripoder.delivery"),physical.getStringExtra("tripoder.delivery"))||!RuntimeActivitySlots.target(expected.getComponent().getClassName()).equals(RuntimeActivitySlots.target(info.name)))throw new SecurityException("ACTIVITY_ATTACH_CAPABILITY_MISMATCH");
        THEMES.put(info,RuntimeEngine.current.theme(activity));
    }
    public static Integer theme(ActivityInfo info){return THEMES.get(info);}
    public static boolean attachRejected(Activity activity,Intent supplied){
        Intent expected;synchronized(REJECTIONS){expected=REJECTIONS.remove(activity);}
        if(expected==null)return false;
        if(activity.getClass()!=RuntimeRejectedActivity.class||supplied==null||!Objects.equals(expected.getComponent(),supplied.getComponent())||!Objects.equals(expected.getStringExtra("tripoder.ticket"),supplied.getStringExtra("tripoder.ticket"))||!Objects.equals(expected.getStringExtra("tripoder.delivery"),supplied.getStringExtra("tripoder.delivery")))throw new SecurityException("REJECTED_ACTIVITY_ATTACH_MISMATCH");return true;
    }
    static Activity cold(String name,Intent physical)throws Exception{
        if(!physical(physical)||!RuntimeActivitySlots.target(physical.getComponent().getClassName()).equals(name))throw new SecurityException("ACTIVITY_PHYSICAL_COMPONENT_MISMATCH");
        Permit permit=begin(physical);try{return permit.rejected()?rejectedInstance(permit,physical):RuntimeBridge.constructWithCurrentInstrumentation(permit);}finally{permit.end();}
    }
    /** A rejected hot launch (performLaunchActivity already created the client record) must still
     * materialise the terminal host RejectedActivity so the real token owns a record; otherwise an
     * independently dispatched non-lifecycle transaction (e.g. TopResumedActivityChangeItem) targets
     * a missing record and kills the successor process. No guest class or Instrumentation is entered. */
    public static Activity constructRejected(Intent supplied){
        Permit permit=CURRENT.get();if(permit==null||!permit.rejected())throw new SecurityException("ACTIVITY_CREATION_PERMIT_INVALID");
        return rejectedInstance(permit,supplied);
    }
    private static Activity rejectedInstance(Permit permit,Intent supplied){
        Activity rejected=new RuntimeRejectedActivity(permit.rejected);synchronized(REJECTIONS){REJECTIONS.put(rejected,new Intent(supplied));}return rejected;
    }
    private RuntimeActivityCreation(){}
}
