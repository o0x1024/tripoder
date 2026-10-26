package dev.tripoder.runtime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** A framework call the runtime has no translation for goes to the platform under the host identity instead of failing the guest.
 * The platform then enforces what an ordinary app may do. Refusal stays only where passing through would reach another guest's state.
 * Each distinct signature is logged once (logcat tag TripoderFailThrough) so census sees which translations are still missing. */
final class RuntimeFailThrough {
    private static final Set<String> SEEN=ConcurrentHashMap.newKeySet();
    static void note(String area,String signature){if(SEEN.size()<4096&&SEEN.add(area+":"+signature))android.util.Log.w("TripoderFailThrough",area+":"+signature);}
    /** The platform knows this process as the host package; an argument naming the guest package names the host to it. */
    static Object[] hostIdentity(Object[] args){
        RuntimeEngine engine=RuntimeEngine.current;if(engine==null||args==null)return args;
        Object[] mapped=null;
        for(int i=0;i<args.length;i++)if(engine.packageName.equals(args[i])){if(mapped==null)mapped=args.clone();mapped[i]=engine.host.getPackageName();}
        return mapped==null?args:mapped;
    }
    private RuntimeFailThrough(){}
}
