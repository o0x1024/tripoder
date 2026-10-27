package dev.tripoder.selfhook;
import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.IBinder;
import java.lang.reflect.*;
import java.util.Objects;
/** Exact Activity fields over the existing fixed registry. No generic hidden
 * reflection resolver or managed physical-identity getter is exposed. */
public final class ActivityFrameworkAccess {
    private static boolean attempted;private static ActivityFrameworkAccess instance;
    private final Field instrumentation,boundThread,recordIntent,recordToken,recordState,savedInstanceState,resources,theme,themeId,application,component,info,resultCode,resultData;
    private final Method view;
    private final Field autofillActivity;
    private final Field launchIntent,launchInfo;
    private ActivityFrameworkAccess()throws Exception{
        instrumentation=FixedFrameworkMembers.field(3,1,"mInstrumentation","Landroid/app/Instrumentation;",false);
        boundThread=FixedFrameworkMembers.field(24,29,"mThread","Landroid/app/ActivityThread;",false);
        recordIntent=FixedFrameworkMembers.field(25,48,"intent","Landroid/content/Intent;",false);recordToken=FixedFrameworkMembers.field(26,48,"token","Landroid/os/IBinder;",false);
        recordState=FixedFrameworkMembers.field(44,48,"state","Landroid/os/Bundle;",false);
        savedInstanceState=FixedFrameworkMembers.optionalField(45,27,"mSavedInstanceState","Landroid/os/Bundle;",false);
        resources=FixedFrameworkMembers.field(27,32,"mResources","Landroid/content/res/Resources;",false);theme=FixedFrameworkMembers.field(28,32,"mTheme","Landroid/content/res/Resources$Theme;",false);themeId=FixedFrameworkMembers.field(29,32,"mThemeResource","I",false);
        application=FixedFrameworkMembers.field(30,27,"mApplication","Landroid/app/Application;",false);component=FixedFrameworkMembers.field(31,27,"mComponent","Landroid/content/ComponentName;",false);info=FixedFrameworkMembers.field(32,27,"mActivityInfo","Landroid/content/pm/ActivityInfo;",false);
        resultCode=FixedFrameworkMembers.field(33,27,"mResultCode","I",false);resultData=FixedFrameworkMembers.field(34,27,"mResultData","Landroid/content/Intent;",false);
        view=FixedFrameworkMembers.method(31,"getView","()Landroid/view/View;",false);
        autofillActivity=FixedFrameworkMembers.field(42,60,"mActivity","Landroid/app/Activity;",false);
        launchIntent=FixedFrameworkMembers.field(67,83,"mIntent","Landroid/content/Intent;",false);
        launchInfo=FixedFrameworkMembers.field(68,83,"mInfo","Landroid/content/pm/ActivityInfo;",false);
    }
    public static synchronized void prepare()throws Exception{if(attempted)throw new IllegalStateException("ACTIVITY_FRAMEWORK_ALREADY_ATTEMPTED");attempted=true;instance=new ActivityFrameworkAccess();}
    private static ActivityFrameworkAccess require(){if(instance==null)throw new IllegalStateException("ACTIVITY_FRAMEWORK_NOT_READY");return instance;}
    public static Instrumentation instrumentation(){return (Instrumentation)Objects.requireNonNull(FixedFrameworkMembers.get(require().instrumentation,PhysicalApplication.thread()),"PHYSICAL_INSTRUMENTATION_REQUIRED");}
    public static boolean bound(Instrumentation value){Object thread=FixedFrameworkMembers.get(require().boundThread,Objects.requireNonNull(value));if(thread==null)return false;if(thread!=PhysicalApplication.thread())throw new SecurityException("INSTRUMENTATION_THREAD_MISMATCH");return true;}
    public static Intent recordIntent(Object record){return (Intent)FixedFrameworkMembers.get(require().recordIntent,Objects.requireNonNull(record));}
    public static IBinder recordToken(Object record){return (IBinder)Objects.requireNonNull(FixedFrameworkMembers.get(require().recordToken,Objects.requireNonNull(record)),"ACTIVITY_RECORD_TOKEN_REQUIRED");}
    public static Bundle recordState(Object record){return (Bundle)FixedFrameworkMembers.get(require().recordState,Objects.requireNonNull(record));}
    public static void recordState(Object record,Bundle state){FixedFrameworkMembers.set(require().recordState,Objects.requireNonNull(record),state);}
    public static boolean launchItem(Object item){return item!=null&&item.getClass()==require().launchIntent.getDeclaringClass();}
    public static boolean physicalClient(Object client){return client==PhysicalApplication.thread();}
    public static Intent launchIntent(Object item){if(!launchItem(item))throw new SecurityException("ACTIVITY_LAUNCH_ITEM_IDENTITY");return (Intent)FixedFrameworkMembers.get(require().launchIntent,item);}
    public static ActivityInfo launchInfo(Object item){if(!launchItem(item))throw new SecurityException("ACTIVITY_LAUNCH_ITEM_IDENTITY");return (ActivityInfo)FixedFrameworkMembers.get(require().launchInfo,item);}
    public static void launchInfo(Object item,ActivityInfo info){if(!launchItem(item))throw new SecurityException("ACTIVITY_LAUNCH_ITEM_IDENTITY");FixedFrameworkMembers.set(require().launchInfo,item,Objects.requireNonNull(info));}
    /** Android 16+ boot frameworks no longer declare Activity.mSavedInstanceState; callers get null there. */
    public static Bundle savedState(Activity activity){Field field=require().savedInstanceState;return field==null?null:(Bundle)FixedFrameworkMembers.get(field,Objects.requireNonNull(activity));}
    public static void apply(Activity value,Resources resources,Application application,Intent visible,ActivityInfo info,int theme){
        ActivityFrameworkAccess v=require();Objects.requireNonNull(value);FixedFrameworkMembers.set(v.resources,value,Objects.requireNonNull(resources));FixedFrameworkMembers.set(v.theme,value,null);FixedFrameworkMembers.set(v.themeId,value,0);
        FixedFrameworkMembers.set(v.application,value,Objects.requireNonNull(application));FixedFrameworkMembers.set(v.component,value,Objects.requireNonNull(visible.getComponent()));FixedFrameworkMembers.set(v.info,value,Objects.requireNonNull(info));
        value.setIntent(visible,value.getCaller());value.setTheme(theme);
    }
    public static int resultCode(Activity value){return (Integer)FixedFrameworkMembers.get(require().resultCode,Objects.requireNonNull(value));}
    public static Intent resultData(Activity value){return (Intent)FixedFrameworkMembers.get(require().resultData,Objects.requireNonNull(value));}
    public static android.view.View view(Object root){return (android.view.View)FixedFrameworkMembers.call(require().view,Objects.requireNonNull(root));}
    public static Activity autofillActivity(Object controller){return (Activity)Objects.requireNonNull(FixedFrameworkMembers.get(require().autofillActivity,Objects.requireNonNull(controller)),"AUTOFILL_ACTIVITY_REQUIRED");}
}
