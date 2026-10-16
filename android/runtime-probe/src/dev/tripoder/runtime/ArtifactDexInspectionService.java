package dev.tripoder.runtime;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.io.File;
import java.nio.file.Files;

/** Probe-only process: verify/map DEX and define a class without running its initializer. */
public final class ArtifactDexInspectionService extends Service {
    private int owner;
    private boolean inspected;
    private ClassLoader retained;
    private final IBinder endpoint=new RuntimeWire.Endpoint(){
        @Override protected synchronized Bundle handle(int code,Bundle input,int pid,int uid)throws Exception{
            if(uid!=android.os.Process.myUid())throw new SecurityException("PROBE_UID_MISMATCH");
            if(owner==0)owner=pid;if(owner!=pid)throw new SecurityException("PROBE_OWNER_MISMATCH");
            if(code==RuntimeWire.STOP){new Handler(Looper.getMainLooper()).postDelayed(()->android.os.Process.killProcess(android.os.Process.myPid()),100);return new Bundle();}
            if(code!=RuntimeWire.CHECK||inspected)throw new IllegalStateException("PROBE_INSPECTION_ALREADY_USED");inspected=true;
            File root=new File(RuntimeWire.required(input,"artifact"));
            File allowed=new File(getFilesDir(),"runtime/artifacts").getCanonicalFile();
            if(!root.getCanonicalFile().getParentFile().equals(allowed))throw new SecurityException("PROBE_ARTIFACT_PATH_INVALID");
            long start=SystemClock.elapsedRealtime(),heapBefore=heap();
            ArtifactSet set=ArtifactSet.verify(root,RuntimeWire.required(input,"descriptorSha"));
            if(!set.packageName.equals(RuntimeWire.required(input,"package")))throw new SecurityException("PROBE_PACKAGE_MISMATCH");
            long verified=SystemClock.elapsedRealtime();
            retained=RuntimeDexLoader.create(set,new File(root,"lib").getCanonicalPath(),getClassLoader());
            String name=set.baseManifest.applicationName;
            if(name.isEmpty())throw new IllegalStateException("PROBE_APPLICATION_CLASS_REQUIRED");
            Class<?> application=Class.forName(name,false,retained);
            if(application.getClassLoader()!=retained)throw new SecurityException("PROBE_CLASS_LOADER_MISMATCH");
            int dexCount=0;long dexBytes=0;for(var entry:set.parts.get(0).entries)if(entry.path.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")){dexCount++;dexBytes+=entry.size;}
            int mapped=0;boolean readOnly=true;for(String line:Files.readAllLines(new File("/proc/self/maps").toPath()))if(line.contains("memfd:tripoder-dex")){mapped++;readOnly&=!line.split("\\s+")[1].contains("w");}
            if(!readOnly)throw new SecurityException("PROBE_DEX_WRITABLE_MAPPING");
            Debug.MemoryInfo memory=new Debug.MemoryInfo();Debug.getMemoryInfo(memory);
            Bundle out=new Bundle();out.putInt("pid",android.os.Process.myPid());out.putInt("uid",android.os.Process.myUid());out.putString("package",set.packageName);out.putString("applicationClass",application.getName());out.putString("loader",retained.getClass().getName());out.putInt("dexFiles",dexCount);out.putLong("dexBytes",dexBytes);out.putInt("observedDexMappings",mapped);out.putBoolean("observedMappingsReadOnly",readOnly);out.putLong("heapBeforeBytes",heapBefore);out.putLong("heapAfterBytes",heap());out.putInt("totalPssKiB",memory.getTotalPss());out.putLong("verifyMs",verified-start);out.putLong("loadMs",SystemClock.elapsedRealtime()-verified);out.putBoolean("applicationInitialized",false);return out;
        }
    };
    private static long heap(){return Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();}
    @Override public IBinder onBind(Intent intent){return endpoint;}
}
