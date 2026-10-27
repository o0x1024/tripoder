package dev.tripoder.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.json.JSONObject;

/** The fixed libc path and IO policy share one native process owner. */
final class RuntimeSelfHookStorage {
    private RuntimeSelfHookStorage(){}
    private static RuntimeEngine installedOwner;
    private static native void installNative(byte[][] paths,boolean diagnostics);
    private static native void traceExitNative(String path);
    private static native void faultEvidenceNative(String path)throws java.io.IOException;
    private static native void faultMapsNative(String path)throws java.io.IOException;
    static native void faultStageNative(int stage);
    static native String syscallNamespaceNative();
    static native void setSyscallPolicyNative(boolean blockExecve,boolean blockKill,boolean networkSinkhole);
    static native String signalChainNative();
    static native byte[] readPhysicalProc(String path,int limit)throws java.io.IOException;
    static native String physicalArtifactPath(String path)throws java.io.IOException;
    static synchronized void install()throws Exception{
        if(installedOwner!=null){if(installedOwner!=RuntimeEngine.current)throw new SecurityException("NATIVE_STORAGE_OWNER_CHANGED");return;}
        JSONObject spec=new JSONObject(RuntimeBridge.nativeStorageSpec());
        java.util.Set<String> fields=new java.util.HashSet<>();spec.keys().forEachRemaining(fields::add);
        if(!fields.equals(Set.of("data","dataCanonical","artifacts","artifactsCanonical","package")))
            throw new IllegalArgumentException("NATIVE_STORAGE_SPEC_FIELDS");
        String[] keys={"data","dataCanonical","artifacts","artifactsCanonical","package"};
        byte[][] values=new byte[keys.length+3][];
        for(int i=0;i<keys.length;i++){
            String value=spec.getString(keys[i]);
            if(value.isEmpty()||value.indexOf('\0')>=0)throw new IllegalArgumentException("NATIVE_STORAGE_SPEC_VALUE");
            values[i]=value.getBytes(StandardCharsets.UTF_8);
        }
        RuntimeEngine owner=RuntimeEngine.current;
        String[] hostRoots={owner.host.getDataDir().toString(),owner.host.getDataDir().getCanonicalPath(),owner.host.createDeviceProtectedStorageContext().getDataDir().getCanonicalPath()};
        for(int i=0;i<hostRoots.length;i++)values[keys.length+i]=hostRoots[i].getBytes(StandardCharsets.UTF_8);
        installNative(values,owner.traceStartup);
        android.os.Bundle metadata=owner.host.getPackageManager().getApplicationInfo(owner.host.getPackageName(),android.content.pm.PackageManager.GET_META_DATA).metaData;
        if(metadata!=null&&metadata.getBoolean("tripoder.diagnostics.faults",false)){
            faultEvidenceNative(new java.io.File(owner.host.getFilesDir(),"native-fault-"+owner.generation+".txt").toString());
            faultMapsNative(new java.io.File(owner.host.getFilesDir(),"native-maps-"+owner.generation+".txt").toString());
        }
        RuntimeEngine engine=RuntimeEngine.current;
        if(engine.traceExit)traceExitNative(engine.exitTracePath);
        installedOwner=engine;
    }
}
