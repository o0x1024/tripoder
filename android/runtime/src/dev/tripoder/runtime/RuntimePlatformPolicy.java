package dev.tripoder.runtime;

import android.content.Context;
import dev.tripoder.selfhook.FixedRuntimeHooks;
import java.util.Set;

/** One service policy: ordinary device services use Android; package-owned
 * notifications, jobs and shortcuts use explicit instance/Binder boundaries. */
final class RuntimePlatformPolicy {
    /** No Manager family participates in the fixed ART binding set. */
    static boolean platformManager(RuntimeSelfHookManagerPolicy.Family family){
        return true; // No process-wide ART replacements for Manager classes.
    }
    private static final Set<String> PLATFORM=Set.of(Context.AUDIO_SERVICE,Context.POWER_SERVICE,
        Context.WIFI_SERVICE,Context.WIFI_P2P_SERVICE,Context.NETWORK_STATS_SERVICE,
        Context.ACTIVITY_SERVICE,Context.CONNECTIVITY_SERVICE,Context.TELEPHONY_SERVICE,Context.BATTERY_SERVICE,Context.LOCATION_SERVICE);
    static boolean platformService(String name){
        if(name==null||!PLATFORM.contains(name))return false;
        if(Context.ACTIVITY_SERVICE.equals(name)&&RuntimeSelfHookManagers.isInstalled(RuntimeSelfHookManagerPolicy.Family.ACTIVITY_MANAGER))return false;
        if(Context.CONNECTIVITY_SERVICE.equals(name)&&RuntimeSelfHookManagers.isInstalled(RuntimeSelfHookManagerPolicy.Family.CONNECTIVITY))return false;
        if(Context.TELEPHONY_SERVICE.equals(name)&&RuntimeSelfHookManagers.isInstalled(RuntimeSelfHookManagerPolicy.Family.TELEPHONY))return false;
        if(Context.BATTERY_SERVICE.equals(name)&&RuntimeSelfHookManagers.isInstalled(RuntimeSelfHookManagerPolicy.Family.BATTERY))return false;
        if(Context.LOCATION_SERVICE.equals(name)&&RuntimeSelfHookManagers.isInstalled(RuntimeSelfHookManagerPolicy.Family.LOCATION))return false;
        return true;
    }
    /** Package queries already use the explicitly installed IPackageManager
     * endpoint. Only raw Context and ServiceManager acquisition need a seam. */
    static boolean packageHook(java.lang.reflect.Executable method){
        String owner=method.getDeclaringClass().getName();
        return owner.equals("android.app.ContextImpl")||owner.equals("android.os.ServiceManager");
    }
    /** UI, traffic and Wi-Fi channel operations keep platform semantics. */
    static boolean platformHooks(FixedRuntimeHooks.Group group){
        if(group==FixedRuntimeHooks.Group.UI)return true;
        return group==FixedRuntimeHooks.Group.TRAFFIC||group==FixedRuntimeHooks.Group.WIFI_CHANNEL||group==FixedRuntimeHooks.Group.FAILURES;
    }
    /** BiometricPrompt hands its Context's package name to the system, which rejects a logical package under the host UID, so guests keep that one projection too. */
    /** Guests keep the platform's UI effects, but Autofill checks the Activity's component against the calling UID and
     * the Activity keeps its guest-visible component, so that one answer must name the admitted physical window. */
    static boolean autofillComponent(java.lang.reflect.Executable method){return method.getName().equals("autofillClientGetComponentName");}
    private RuntimePlatformPolicy(){}
}
