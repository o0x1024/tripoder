package dev.tripoder.runtime;

import android.app.*;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.util.*;

/** Logical caller views attach to the real Android token; its actual shared UID is unchanged. */
final class RuntimeActivityCallers {
    private final Map<Activity,String> initial=new IdentityHashMap<>(),current=new IdentityHashMap<>();
    private final Map<ComponentCaller,String> callers=new WeakHashMap<>();
    private final Map<Activity,Origin> origins=new IdentityHashMap<>();
    private static final class Origin {
        final String kind,pkg,component,physical;final boolean expectsResult;
        Origin(Bundle b){kind=RuntimeWire.required(b,"callerKind");pkg=RuntimeWire.required(b,"callerPackage");component=b.getString("callerComponent");physical=b.getString("callerPhysical");expectsResult=b.getBoolean("expectsResult");}
    }
    synchronized void initial(Activity activity,Bundle launch){Origin origin=new Origin(launch);origins.put(activity,origin);initial.put(activity,origin.pkg);}
    synchronized ComponentName callingActivity(Activity activity,ComponentName physical){
        Origin origin=origins.get(activity);if(origin==null)throw new SecurityException("ACTIVITY_CALLER_SCOPE_MISSING");
        if(origin.kind.equals("CONTROLLER")){
            if(physical!=null&&!physical.getPackageName().equals(origin.pkg))throw new SecurityException("ACTIVITY_CONTROLLER_CALLER_MISMATCH");
            return physical;
        }
        if(!origin.kind.equals("ACTIVITY"))throw new SecurityException("ACTIVITY_SOURCE_UNRECORDED");
        if(!origin.expectsResult){if(physical!=null)throw new SecurityException("UNEXPECTED_ANDROID_RESULT_OWNER");return null;}
        if(physical==null||!physical.getPackageName().equals(RuntimeEngine.current.host.getPackageName())||!physical.getClassName().equals(origin.physical))throw new SecurityException("ANDROID_RESULT_OWNER_MISMATCH:"+physical);
        if(origin.component==null)throw new SecurityException("ACTIVITY_SOURCE_COMPONENT_MISSING");
        return new ComponentName(origin.pkg,origin.component);
    }
    synchronized void enter(Activity activity,String pkg,ComponentCaller caller){if(current.containsKey(activity))throw new SecurityException("ACTIVITY_NEW_INTENT_REENTRANT");bind(caller,pkg);current.put(activity,pkg);}
    synchronized void enterResult(Activity activity,String pkg,ComponentCaller caller){if(current.containsKey(activity))throw new SecurityException("ACTIVITY_RESULT_REENTRANT");if(caller!=null)bind(caller,pkg);current.put(activity,pkg);}
    synchronized void leave(Activity activity){current.remove(activity);}
    private void bind(ComponentCaller caller,String pkg){if(caller==null)throw new SecurityException("ACTIVITY_CALLER_REQUIRED");String prior=callers.putIfAbsent(caller,pkg);if(prior!=null&&!prior.equals(pkg))throw new SecurityException("ACTIVITY_CALLER_IDENTITY_CHANGED");}
    synchronized ComponentCaller observe(Activity activity,ComponentCaller caller,boolean active){
        if(caller==null)return null;if(!callers.containsKey(caller)){String pkg=(active?current:initial).get(activity);if(pkg==null)throw new SecurityException("ACTIVITY_CALLER_SCOPE_MISSING");bind(caller,pkg);}return caller;
    }
    synchronized boolean owns(ComponentCaller caller){return callers.containsKey(caller);}
    synchronized String packageName(ComponentCaller caller){String pkg=callers.get(caller);if(pkg==null)throw new SecurityException("ACTIVITY_CALLER_UNOWNED");return pkg;}
    synchronized Uri referrer(Activity activity){String declared=activity.getIntent().getStringExtra(Intent.EXTRA_REFERRER_NAME);if(declared!=null)return Uri.parse(declared);String pkg=current.containsKey(activity)?current.get(activity):initial.get(activity);return pkg==null?null:Uri.parse("android-app://"+pkg);}
    synchronized void remove(Activity activity){initial.remove(activity);current.remove(activity);origins.remove(activity);}
}
