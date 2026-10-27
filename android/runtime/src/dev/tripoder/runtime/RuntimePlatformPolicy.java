package dev.tripoder.runtime;

import android.content.Context;
import dev.tripoder.selfhook.FixedRuntimeHooks;
import java.util.Set;

/** One service policy. Existing persona facades preserve explicit test overrides; other device services use Android. */
final class RuntimePlatformPolicy {
    /** Only services that need identity translation or persona callbacks install a facade. */
    static boolean platformManager(RuntimeSelfHookManagerPolicy.Family family){
        return switch(family){
            case AUDIO,POWER,WIFI_P2P,NETWORK_STATS->true;
            case CONNECTIVITY,TELEPHONY,BATTERY,LOCATION->false;
            default->false;
        };
    }
    private static final Set<String> PLATFORM=Set.of(Context.AUDIO_SERVICE,Context.POWER_SERVICE,
        Context.WIFI_SERVICE,Context.WIFI_P2P_SERVICE,Context.NETWORK_STATS_SERVICE);
    static boolean platformService(String name){return name!=null&&PLATFORM.contains(name);}
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
