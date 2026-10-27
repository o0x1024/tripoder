package dev.tripoder.runtime;
import android.app.*;
import android.os.Bundle;
import dev.tripoder.selfhook.ActivityFrameworkAccess;

public class RuntimeApplication extends Application implements Application.ActivityLifecycleCallbacks {
    static RuntimeApplication host;
    @Override protected void attachBaseContext(android.content.Context base){super.attachBaseContext(base);host=this;}
    @Override public void onCreate(){super.onCreate();registerActivityLifecycleCallbacks(this);}
    /** Guest view state contains app-defined Parcelables; resolve them with the guest loader. */
    private static void stateLoader(Activity a,Bundle b){
        if(!RuntimeBridge.owns(a))return;
        ClassLoader loader=RuntimeEngine.current.guestLoader();
        if(b!=null)b.setClassLoader(loader);
        try{Bundle saved=ActivityFrameworkAccess.savedState(a);if(saved!=null)saved.setClassLoader(loader);}
        catch(Throwable error){android.util.Log.w("TripoderActivities","saved-state-loader",error);}
    }
    @Override public void onActivityPreCreated(Activity a,Bundle b){stateLoader(a,b);}
    @Override public void onActivityCreated(Activity a,Bundle b){stateLoader(a,b);}
    @Override public void onActivityPostCreated(Activity a,Bundle b){if(RuntimeBridge.owns(a))RuntimeEngine.current.activityCreated(a);}
    @Override public void onActivityStarted(Activity a){stateLoader(a,null);}
    @Override public void onActivityResumed(Activity a){if(RuntimeBridge.owns(a))RuntimeEngine.current.activityResumed(a);}
    @Override public void onActivityPaused(Activity a){}
    @Override public void onActivityStopped(Activity a){}
    @Override public void onActivitySaveInstanceState(Activity a,Bundle b){}
    @Override public void onActivityDestroyed(Activity a){}
    @Override public void onActivityPreDestroyed(Activity a){if(RuntimeBridge.owns(a))RuntimeEngine.current.activityDestroying(a);}
    @Override public void onActivityPostDestroyed(Activity a){if(RuntimeBridge.owns(a))RuntimeEngine.current.activityDestroyed(a);}
}
