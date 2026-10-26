package dev.tripoder.runtime;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.os.*;
import java.lang.reflect.*;
import org.json.JSONObject;

/** Real SDK Activity restoration in an attached, unscheduled owned host shell.
 * No guest, AMS task, launch ticket or persisted application state is created. */
public final class RejectedRestoreIntegration extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void attach(Activity activity)throws Exception{
        Context context=getTargetContext();Class<?> threadType=Class.forName("android.app.ActivityThread");
        Method current=threadType.getDeclaredMethod("currentActivityThread");current.setAccessible(true);Object thread=current.invoke(null);
        String descriptor="(Landroid/content/Context;Landroid/app/ActivityThread;Landroid/app/Instrumentation;Landroid/os/IBinder;ILandroid/app/Application;Landroid/content/Intent;Landroid/content/pm/ActivityInfo;Ljava/lang/CharSequence;Landroid/app/Activity;Ljava/lang/String;Landroid/app/Activity$NonConfigurationInstances;Landroid/content/res/Configuration;Ljava/lang/String;Lcom/android/internal/app/IVoiceInteractor;Landroid/view/Window;Landroid/view/ViewRootImpl$ActivityConfigCallback;Landroid/os/IBinder;Landroid/os/IBinder;Landroid/os/IBinder;)V";
        Method selected=null;
        for(Method method:Activity.class.getDeclaredMethods())if(method.getName().equals("attach")&&java.lang.invoke.MethodType.methodType(method.getReturnType(),method.getParameterTypes()).toMethodDescriptorString().equals(descriptor)){
            if(selected!=null)throw new AssertionError("OWNED_ATTACH_AMBIGUOUS");selected=method;
        }
        if(selected==null)throw new AssertionError("OWNED_FIXED_ATTACH_MISSING");selected.setAccessible(true);
        ActivityInfo info=new ActivityInfo();info.name=activity.getClass().getName();info.packageName=context.getPackageName();info.applicationInfo=context.getApplicationInfo();info.enabled=true;
        Intent intent=new Intent().setComponent(new ComponentName(context.getPackageName(),info.name));
        selected.invoke(activity,context,thread,this,new Binder(),0,context.getApplicationContext(),intent,info,"Owned terminal restore",null,"owned-terminal-restore",null,context.getResources().getConfiguration(),null,null,null,null,new Binder(),new Binder(),new Binder());
    }
    @Override public void onStart(){
        JSONObject report=new JSONObject();Bundle result=new Bundle();
        try{
            if(!getTargetContext().getPackageName().startsWith("dev.tripoder.compat"))throw new SecurityException("OWNED_QA_REQUIRED");
            Throwable[] failure=new Throwable[1];
            runOnMainSync(()->{
                try{
                    Bundle fragmentState=new Bundle();fragmentState.putInt("owned-marker",7);
                    Bundle saved=new Bundle();saved.putParcelable("android:fragments",fragmentState);
                    Activity ordinary=new Activity();attach(ordinary);String ordinaryFailure="";
                    try{callActivityOnCreate(ordinary,saved);}catch(Throwable rejected){ordinaryFailure=rejected.getClass().getName();}
                    report.put("ordinarySdkRestoreFailure",ordinaryFailure);
                    if(!ordinaryFailure.equals("java.lang.ClassCastException"))throw new AssertionError("OWNED_STATE_DID_NOT_REACH_SDK_FRAGMENT_RESTORE");
                    RuntimeRejectedActivity terminal=new RuntimeRejectedActivity("OWNED_REVOKED_ENTRY");attach(terminal);
                    callActivityOnCreate(terminal,saved);
                    boolean intact=saved.size()==1&&saved.getParcelable("android:fragments",Bundle.class)==fragmentState&&fragmentState.size()==1&&fragmentState.getInt("owned-marker")==7;
                    if(!intact)throw new AssertionError("OWNED_SAVED_STATE_CHANGED");
                    report.put("terminalShellCreated",true).put("savedStateRetained",true).put("passed",true).put("checks",3);
                }catch(Throwable error){failure[0]=error;}
            });
            if(failure[0]!=null)throw failure[0];
        }catch(Throwable error){try{report.put("passed",false).put("error",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}}
        try{report.put("guestExecuted",false).put("amsTaskCreated",false).put("dataCleared",false).put("trustedMetadataInspectionProfile",true);result.putString("report",report.toString());}
        catch(Exception error){result.putString("evidenceError",error.getClass().getName());}
        finish(report.optBoolean("passed")?-1:0,result);
    }
}
