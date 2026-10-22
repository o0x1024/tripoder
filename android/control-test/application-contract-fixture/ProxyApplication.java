package dev.tripoder.applicationcontract;

import android.app.*;
import android.content.*;
import android.os.Bundle;

/** Own proxy contract: callbacks are owned by the real Application explicitly returned as application context. */
public final class ProxyApplication extends Application {
    public static Application real;public static int created,received,unexpected;public static MainActivity activity;
    @Override public void onCreate(){
        super.onCreate();
        try{real=Instrumentation.newApplication(Delegate.class,getBaseContext());}catch(Exception error){throw new IllegalStateException(error);}
        real.onCreate();
    }
    @Override public Context getApplicationContext(){return real==null?super.getApplicationContext():real;}
    public static final class Delegate extends Application {
        @Override public void onCreate(){registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity value,Bundle state){created++;}
            public void onActivityStarted(Activity value){}public void onActivityResumed(Activity value){}public void onActivityPaused(Activity value){}
            public void onActivityStopped(Activity value){}public void onActivitySaveInstanceState(Activity value,Bundle state){}public void onActivityDestroyed(Activity value){}
        });}
    }
    public static final class First extends BroadcastReceiver {public void onReceive(Context context,Intent intent){received++;}}
    public static final class Second extends BroadcastReceiver {public void onReceive(Context context,Intent intent){received++;}}
    public static final class Disabled extends BroadcastReceiver {public void onReceive(Context context,Intent intent){unexpected++;}}
    public static final class WrongData extends BroadcastReceiver {public void onReceive(Context context,Intent intent){unexpected++;}}
}
