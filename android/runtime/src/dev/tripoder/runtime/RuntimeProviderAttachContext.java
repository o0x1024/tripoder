package dev.tripoder.runtime;
import android.app.Application;
import android.content.*;

/** Used only inside platform ContentProvider.attachInfo, after the guest override has run. On Android the context a provider of the application receives is the Application itself, and real code casts it, so this is an Application too. The registrations an Application keeps are forwarded to the guest's own Application, which is the one that dispatches events. */
final class RuntimeProviderAttachContext extends Application {
    private Object appOps;
    RuntimeProviderAttachContext(Context guest,Context host){attachBaseContext(guest);appOps=host.getSystemService(Context.APP_OPS_SERVICE);if(appOps==null)throw new IllegalStateException("PROVIDER_PLATFORM_APPOPS_MISSING");}
    private Application guestApplication(){Context application=getBaseContext().getApplicationContext();return application instanceof Application&&application!=this?(Application)application:null;}
    @Override public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback){Application application=guestApplication();if(application!=null)application.registerActivityLifecycleCallbacks(callback);else super.registerActivityLifecycleCallbacks(callback);}
    @Override public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback){Application application=guestApplication();if(application!=null)application.unregisterActivityLifecycleCallbacks(callback);else super.unregisterActivityLifecycleCallbacks(callback);}
    @Override public void registerOnProvideAssistDataListener(OnProvideAssistDataListener listener){Application application=guestApplication();if(application!=null)application.registerOnProvideAssistDataListener(listener);else super.registerOnProvideAssistDataListener(listener);}
    @Override public void unregisterOnProvideAssistDataListener(OnProvideAssistDataListener listener){Application application=guestApplication();if(application!=null)application.unregisterOnProvideAssistDataListener(listener);else super.unregisterOnProvideAssistDataListener(listener);}
    @Override public Object getSystemService(String name){if(Context.APP_OPS_SERVICE.equals(name)&&appOps!=null){Object service=appOps;appOps=null;return service;}return super.getSystemService(name);}
}
