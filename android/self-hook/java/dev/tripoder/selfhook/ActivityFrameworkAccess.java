package dev.tripoder.selfhook;
import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.os.IBinder;
import java.lang.reflect.*;
import java.util.Objects;
/** Exact Activity fields over the existing fixed registry. No generic hidden
 * reflection resolver or managed physical-identity getter is exposed. */
public final class ActivityFrameworkAccess {
    private static boolean attempted;private static ActivityFrameworkAccess instance;
    private final Field instrumentation,boundThread,recordIntent,recordToken,resources,theme,themeId,application,component,info,resultCode,resultData;
    private final Method view;
    private ActivityFrameworkAccess()throws Exception{
        instrumentation=FixedFrameworkMembers.field(3,1,"mInstrumentation","Landroid/app/Instrumentation;",false);
        boundThread=FixedFrameworkMembers.field(24,29,"mThread","Landroid/app/ActivityThread;",false);
        recordIntent=FixedFrameworkMembers.field(25,48,"intent","Landroid/content/Intent;",false);recordToken=FixedFrameworkMembers.field(26,48,"token","Landroid/os/IBinder;",false);
        resources=FixedFrameworkMembers.field(27,32,"mResources","Landroid/content/res/Resources;",false);theme=FixedFrameworkMembers.field(28,32,"mTheme","Landroid/content/res/Resources$Theme;",false);themeId=FixedFrameworkMembers.field(29,32,"mThemeResource","I",false);
        application=FixedFrameworkMembers.field(30,27,"mApplication","Landroid/app/Application;",false);component=FixedFrameworkMembers.field(31,27,"mComponent","Landroid/content/ComponentName;",false);info=FixedFrameworkMembers.field(32,27,"mActivityInfo","Landroid/content/pm/ActivityInfo;",false);
        resultCode=FixedFrameworkMembers.field(33,27,"mResultCode","I",false);resultData=FixedFrameworkMembers.field(34,27,"mResultData","Landroid/content/Intent;",false);
        view=FixedFrameworkMembers.method(31,"getView","()Landroid/view/View;",false);
    }
    public static synchronized void prepare()throws Exception{if(attempted)throw new IllegalStateException("ACTIVITY_FRAMEWORK_ALREADY_ATTEMPTED");attempted=true;instance=new ActivityFrameworkAccess();}
    private static ActivityFrameworkAccess require(){if(instance==null)throw new IllegalStateException("ACTIVITY_FRAMEWORK_NOT_READY");return instance;}
    public static Instrumentation instrumentation(){return (Instrumentation)Objects.requireNonNull(FixedFrameworkMembers.get(require().instrumentation,PhysicalApplication.thread()),"PHYSICAL_INSTRUMENTATION_REQUIRED");}
    public static boolean bound(Instrumentation value){Object thread=FixedFrameworkMembers.get(require().boundThread,Objects.requireNonNull(value));if(thread==null)return false;if(thread!=PhysicalApplication.thread())throw new SecurityException("INSTRUMENTATION_THREAD_MISMATCH");return true;}
    public static Intent recordIntent(Object record){return (Intent)FixedFrameworkMembers.get(require().recordIntent,Objects.requireNonNull(record));}
    public static IBinder recordToken(Object record){return (IBinder)Objects.requireNonNull(FixedFrameworkMembers.get(require().recordToken,Objects.requireNonNull(record)),"ACTIVITY_RECORD_TOKEN_REQUIRED");}
    public static void apply(Activity value,Resources resources,Application application,Intent visible,ActivityInfo info,int theme){
        ActivityFrameworkAccess v=require();Objects.requireNonNull(value);FixedFrameworkMembers.set(v.resources,value,Objects.requireNonNull(resources));FixedFrameworkMembers.set(v.theme,value,null);FixedFrameworkMembers.set(v.themeId,value,0);
        FixedFrameworkMembers.set(v.application,value,Objects.requireNonNull(application));FixedFrameworkMembers.set(v.component,value,Objects.requireNonNull(visible.getComponent()));FixedFrameworkMembers.set(v.info,value,Objects.requireNonNull(info));
        value.setIntent(visible,value.getCaller());value.setTheme(theme);
    }
    public static int resultCode(Activity value){return (Integer)FixedFrameworkMembers.get(require().resultCode,Objects.requireNonNull(value));}
    public static Intent resultData(Activity value){return (Intent)FixedFrameworkMembers.get(require().resultData,Objects.requireNonNull(value));}
    public static android.view.View view(Object root){return (android.view.View)FixedFrameworkMembers.call(require().view,Objects.requireNonNull(root));}
}
