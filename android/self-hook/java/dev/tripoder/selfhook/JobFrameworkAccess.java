package dev.tripoder.selfhook;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;

/** Exact fixed-platform Job members. No guest receives the system callback or service token. */
public final class JobFrameworkAccess {
    private static JobFrameworkAccess current;
    private final Constructor<?> scheduler;
    private final Method early,late,attach,thread,cleanable,cleanup;
    private final Field callback,extras,namespace;
    private final Set<Method> hooks=new LinkedHashSet<>();
    private JobFrameworkAccess()throws Exception{
        scheduler=FixedFrameworkMembers.constructor(50,"(Landroid/content/Context;Landroid/app/job/IJobScheduler;)V");
        early=FixedFrameworkMembers.method(51,"hasEarlyConstraint","()Z",false);late=FixedFrameworkMembers.method(51,"hasLateConstraint","()Z",false);
        callback=FixedFrameworkMembers.field(39,52,"callback","Landroid/os/IBinder;",false);extras=FixedFrameworkMembers.field(40,52,"extras","Landroid/os/PersistableBundle;",false);namespace=FixedFrameworkMembers.field(41,52,"mJobNamespace","Ljava/lang/String;",false);
        attach=FixedFrameworkMembers.method(13,"attach","(Landroid/content/Context;Landroid/app/ActivityThread;Ljava/lang/String;Landroid/os/IBinder;Landroid/app/Application;Ljava/lang/Object;)V",false);
        thread=FixedFrameworkMembers.method(1,"currentActivityThread","()Landroid/app/ActivityThread;",true);
        cleanable=FixedFrameworkMembers.method(52,"getCleanable","()Ljava/lang/ref/Cleaner$Cleanable;",false);cleanup=FixedFrameworkMembers.method(52,"getJobCleanupCallback","()Landroid/app/job/JobParameters$JobCleanupCallback;",false);
        add(53,"jobFinished","(Landroid/app/job/JobParameters;Z)V");add(53,"onBind","(Landroid/content/Intent;)Landroid/os/IBinder;");
        add(53,"setNotification","(Landroid/app/job/JobParameters;ILandroid/app/Notification;I)V");
        for(String name:List.of("updateEstimatedNetworkBytes","updateTransferredNetworkBytes")){add(53,name,"(Landroid/app/job/JobParameters;JJ)V");add(53,name,"(Landroid/app/job/JobParameters;Landroid/app/job/JobWorkItem;JJ)V");}
        add(52,"dequeueWork","()Landroid/app/job/JobWorkItem;");add(52,"completeWork","(Landroid/app/job/JobWorkItem;)V");
        add(52,"enableCleaner","()V");add(52,"disableCleaner","()V");add(52,"initCleaner","(Landroid/app/job/JobParameters$JobCleanupCallback;)V");add(52,"getCallback","()Landroid/app/job/IJobCallback;");
        for(String name:List.of("acknowledgeStartMessage","acknowledgeStopMessage","jobFinished"))add(54,name,"(IZ)V");
    }
    private void add(int owner,String name,String descriptor)throws Exception{hooks.add(FixedFrameworkMembers.method(owner,name,descriptor,false));}
    public static synchronized void prepare()throws Exception{if(current!=null)throw new IllegalStateException("JOB_FRAMEWORK_ALREADY_PREPARED");current=new JobFrameworkAccess();}
    private static JobFrameworkAccess require(){if(current==null)throw new IllegalStateException("JOB_FRAMEWORK_NOT_READY");return current;}
    static boolean permitsHook(Executable method){return current!=null&&current.hooks.contains(method);}
    public static Set<Method> hooks(){return Set.copyOf(require().hooks);}
    public static JobScheduler facade(Context host){return (JobScheduler)FixedFrameworkMembers.create(require().scheduler,host,null);}
    public static boolean early(JobInfo info){return (Boolean)FixedFrameworkMembers.call(require().early,info);}
    public static boolean late(JobInfo info){return (Boolean)FixedFrameworkMembers.call(require().late,info);}
    public static IBinder callback(JobParameters parameters){return Objects.requireNonNull((IBinder)FixedFrameworkMembers.get(require().callback,parameters),"JOB_CALLBACK_MISSING");}
    public static String namespace(JobParameters parameters){return (String)FixedFrameworkMembers.get(require().namespace,parameters);}
    public static JobParameters view(JobParameters physical,PersistableBundle logicalExtras,String logicalNamespace){
        JobFrameworkAccess access=require();Parcel parcel=Parcel.obtain();JobParameters view;
        try{physical.writeToParcel(parcel,0);if(parcel.hasFileDescriptors()||parcel.dataSize()>65536)throw new SecurityException("JOB_PARAMETERS_PAYLOAD_UNSUPPORTED");parcel.setDataPosition(0);view=JobParameters.CREATOR.createFromParcel(parcel);}finally{parcel.recycle();}
        if(FixedFrameworkMembers.call(access.cleanable,view)!=null||FixedFrameworkMembers.call(access.cleanup,view)!=null)throw new SecurityException("JOB_VIEW_HAS_CLEANER");
        FixedFrameworkMembers.set(access.callback,view,null);FixedFrameworkMembers.set(access.extras,view,new PersistableBundle(logicalExtras));FixedFrameworkMembers.set(access.namespace,view,logicalNamespace);
        return view;
    }
    public static void attach(Service service,Context context,Application application,String component){JobFrameworkAccess a=require();FixedFrameworkMembers.call(a.attach,service,context,FixedFrameworkMembers.call(a.thread,null),component,null,application,null);}
    private JobFrameworkAccess(boolean unused){throw new AssertionError();}
}
