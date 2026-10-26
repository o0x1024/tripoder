package dev.tripoder.runtime;

import android.content.Context;
import dev.tripoder.selfhook.FixedRuntimeHooks;
import java.util.Set;

/** Guests use Android's real device services under the physical host UID.
 * Component, package, resource and storage translation remains in the runtime.
 * Only device identity is mode-dependent: a THICK guest is answered by virtual identity managers. */
final class RuntimeThinPolicy {
    /** The device is not concealed: identity managers, traffic, Wi-Fi channels and mounts are the platform's own. */
    static boolean active(){return RuntimeEngine.current!=null&&!RuntimeEngine.current.mode.concealsDevice();}
    /** Audio and power are device state an app reasons about, not identity: the platform answers in both modes. Battery is the platform's in THIN, but a THICK guest is answered by the battery facade so the sandbox persona (level/charging override) can apply; with no override it passes through to the real value. */
    static boolean platformManager(RuntimeSelfHookManagerPolicy.Family family){
        return switch(family){
            case AUDIO,POWER->true;
            case CONNECTIVITY,TELEPHONY,WIFI_P2P,NETWORK_STATS,BATTERY,LOCATION->active();
            default->false;
        };
    }
    private static final Set<String> STATE=Set.of(Context.AUDIO_SERVICE,Context.POWER_SERVICE);
    private static final Set<String> IDENTITY=Set.of(Context.CONNECTIVITY_SERVICE,Context.TELEPHONY_SERVICE,Context.BATTERY_SERVICE,Context.LOCATION_SERVICE,
        Context.WIFI_SERVICE,Context.WIFI_P2P_SERVICE,Context.NETWORK_STATS_SERVICE);
    static boolean platformService(String name){return name!=null&&(STATE.contains(name)||active()&&IDENTITY.contains(name));}
    /** UI effects are the platform's in both modes. Traffic and Wi-Fi channels are identity; failure diagnostics stay with THICK. */
    static boolean platformHooks(FixedRuntimeHooks.Group group){
        if(group==FixedRuntimeHooks.Group.UI)return true;
        return active()&&(group==FixedRuntimeHooks.Group.TRAFFIC||group==FixedRuntimeHooks.Group.WIFI_CHANNEL||group==FixedRuntimeHooks.Group.FAILURES);
    }
    /** BiometricPrompt hands its Context's package name to the system, which rejects a logical package under the host UID, so guests keep that one projection too. */
    /** Guests keep the platform's UI effects, but Autofill checks the Activity's component against the calling UID and
     * the Activity keeps its guest-visible component, so that one answer must name the admitted physical window. */
    static boolean autofillComponent(java.lang.reflect.Executable method){return method.getName().equals("autofillClientGetComponentName");}
    private RuntimeThinPolicy(){}
}
