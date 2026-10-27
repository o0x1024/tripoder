package dev.tripoder.runtime;

import dev.tripoder.selfhook.NativeModules;
import java.io.File;
import java.util.*;
import java.util.function.BooleanSupplier;

/** The immutable native library identity and mandatory process boundary have
 * one owner. Creating an optional Hook observation never installs this boundary. */
final class RuntimeNativeLibraryCatalog {
    interface Inventory {List<String> names();String path(String name)throws Exception;Map<String,Object> inspect(String name)throws Exception;}
    record Artifact(int index,String name,String path){}
    private final Inventory inventory;
    private final BooleanSupplier ownerLive;
    private final Map<String,Artifact> artifacts;
    private boolean attempted,ready;

    RuntimeNativeLibraryCatalog(Inventory inventory,BooleanSupplier ownerLive)throws Exception{
        this.inventory=Objects.requireNonNull(inventory);this.ownerLive=Objects.requireNonNull(ownerLive);
        List<String> names=List.copyOf(inventory.names());
        if(names.size()>4096||new HashSet<>(names).size()!=names.size())throw new IllegalArgumentException("HOOK_MODULE_CATALOG");
        Map<String,Artifact> collected=new LinkedHashMap<>();
        for(int i=0;i<names.size();i++){
            String name=names.get(i);if(!name.matches("lib[a-zA-Z0-9_+.$-]+\\.so"))throw new IllegalArgumentException("HOOK_MODULE_NAME");
            String path=inventory.path(name);
            if(path==null||!path.startsWith("/")||!new File(path).getName().equals(name))throw new SecurityException("HOOK_MODULE_ARTIFACT_PATH");
            collected.put(name,new Artifact(i,name,path));
        }
        artifacts=Collections.unmodifiableMap(collected);
    }
    /** Signal routing and module lifetime participate in guest execution even
     * without a user Hook. A failed/uncertain installation is never retried. */
    synchronized void installProcessBoundary(){
        if(attempted)throw new IllegalStateException("NATIVE_LIBRARY_RUNTIME_ALREADY_ATTEMPTED");
        attempted=true;
        if(!ownerLive.getAsBoolean())throw new IllegalStateException("HOOK_PROCESS_STALE");
        NativeModules.initialize(artifacts.values().stream().map(Artifact::path).toArray(String[]::new));
        if(!ownerLive.getAsBoolean())throw new IllegalStateException("HOOK_PROCESS_STALE");
        ready=true;
    }
    synchronized void requireReady(){if(!ready||!ownerLive.getAsBoolean())throw new IllegalStateException("NATIVE_LIBRARY_RUNTIME_NOT_READY");}
    Map<String,Artifact> artifacts(){return artifacts;}
    Map<String,Object> inspect(String name)throws Exception{return inventory.inspect(name);}
}
