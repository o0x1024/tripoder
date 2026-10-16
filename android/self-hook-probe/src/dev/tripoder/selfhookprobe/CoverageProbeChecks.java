package dev.tripoder.selfhookprobe;

import android.content.Context;
import android.os.Debug;
import dalvik.system.InMemoryDexClassLoader;
import dev.tripoder.selfhook.coverage.LoadedClassInventory;
import dev.tripoder.selfhook.dex.DexCallerClosure;
import dev.tripoder.selfhook.dex.DexFile;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

final class CoverageProbeChecks {
    private static final String NAME="dev.tripoder.selfhookprobe.dynamic.LateCaller";
    private static final String DESC="Ldev/tripoder/selfhookprobe/dynamic/LateCaller;";
    private static JSONObject receipt(LoadedClassInventory.Snapshot snapshot)throws Exception{
        return new JSONObject().put("sequence",snapshot.sequence).put("budget",snapshot.budget)
            .put("visited",snapshot.visitedAtLeast).put("classes",snapshot.classes.size())
            .put("bootClasses",snapshot.bootClassCount).put("loaders",snapshot.definingLoaderCount)
            .put("complete",snapshot.enumerationCompleted).put("futureClassPublicationCovered",snapshot.futureClassPublicationCovered);
    }
    static JSONObject run(Context context,File directory)throws Exception{
        LoadedClassInventory.Snapshot small=LoadedClassInventory.capture(1);
        ProbeFiles.require(!small.enumerationCompleted&&small.classes.isEmpty(),"NO_PARTIAL_INVENTORY");
        boolean rejected=false;try{small.forLoader(context.getClassLoader());}catch(IllegalStateException expected){rejected=true;}
        ProbeFiles.require(rejected,"BUDGET_MUST_REJECT_BINDING");
        LoadedClassInventory.Snapshot before=LoadedClassInventory.capture(65536);
        ProbeFiles.require(before.enumerationCompleted,"INITIAL_INVENTORY");
        for(Class<?> type:before.classes)ProbeFiles.require(!type.getName().equals(NAME),"LATE_CLASS_ALREADY_LOADED");
        byte[] bytes;try(java.io.InputStream input=context.getAssets().open("late-caller.dex")){bytes=input.readAllBytes();}
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(directory,"late-caller.dex"))){out.write(bytes);}
        LateCallerChecks lateHooks=new LateCallerChecks();
        try{
        ClassLoader parent=CoverageTarget.class.getClassLoader();
        InMemoryDexClassLoader first=new InMemoryDexClassLoader(ByteBuffer.wrap(bytes),parent);
        InMemoryDexClassLoader second=new InMemoryDexClassLoader(ByteBuffer.wrap(bytes),parent);
        Class<?> a=Class.forName(NAME,false,first),b=Class.forName(NAME,false,second);
        ProbeFiles.require(a!=b&&a.getClassLoader()==first&&b.getClassLoader()==second&&CoverageTarget.initializations.get()==0,"DYNAMIC_LOADER_IDENTITY_NO_INIT");
        LoadedClassInventory.Snapshot loaded=LoadedClassInventory.capture(65536);
        ProbeFiles.require(loaded.forLoader(first).get(DESC)==a&&loaded.forLoader(second).get(DESC)==b,"EXACT_DEFINING_LOADER_INVENTORY");
        ProbeFiles.require(CoverageTarget.initializations.get()==0,"ENUMERATION_MUST_NOT_INITIALIZE");
        DexFile apk;
        try(ZipFile zip=new ZipFile(context.getApplicationInfo().sourceDir);java.io.InputStream input=zip.getInputStream(zip.getEntry("classes.dex"))){apk=new DexFile(input.readAllBytes());}
        DexFile late=new DexFile(bytes);
        String target="Ldev/tripoder/selfhookprobe/CoverageTarget;->value(I)I";
        List<DexFile.MethodRef> candidates=new DexCallerClosure(Arrays.asList(apk,late)).candidates(target);
        ProbeFiles.require(candidates.size()==1&&candidates.get(0).key().equals(DESC+"->call(I)I"),"EXACT_LATE_CALLER_GRAPH");
        long[] rootsBefore=GcProbe.snapshot(new Object[]{a,b});
        System.gc();Thread.sleep(30);
        long[] rootsAfter=GcProbe.snapshot(new Object[]{a,b});
        ProbeFiles.require(rootsBefore!=null&&rootsAfter!=null,"CLASS_GC_ROOT_SNAPSHOT");
        int moved=0;for(int i=0;i<2;i++)if(rootsBefore[i]!=rootsAfter[i])moved++;
        ProbeFiles.require(moved>0,"CLASS_MOVEMENT_MUST_BE_OBSERVED");
        AtomicBoolean stop=new AtomicBoolean();AtomicLong batches=new AtomicLong();AtomicReference<Throwable> failed=new AtomicReference<>();
        long gcBefore=Long.parseLong(Debug.getRuntimeStat("art.gc.gc-count"));
        Thread worker=new Thread(()->{try{while(!stop.get()){
            byte[][] pressure=new byte[8][];for(int i=0;i<8;i++)pressure[i]=new byte[256*1024];
            java.lang.ref.Reference.reachabilityFence(pressure);batches.incrementAndGet();Thread.sleep(1);
        }}catch(Throwable error){failed.set(error);}},"owned-enumeration-gc");
        JSONArray observations=new JSONArray();worker.start();
        try{for(int i=0;i<24;i++){
            LoadedClassInventory.Snapshot snapshot=LoadedClassInventory.capture(65536);
            ProbeFiles.require(snapshot.forLoader(first).get(DESC)==a&&snapshot.forLoader(second).get(DESC)==b,"LIVE_CLASS_ROOTS_DURING_GC");
            observations.put(receipt(snapshot));Thread.sleep(5);
        }}finally{stop.set(true);worker.join(10000);}
        long gcAfter=Long.parseLong(Debug.getRuntimeStat("art.gc.gc-count"));
        ProbeFiles.require(!worker.isAlive()&&failed.get()==null&&batches.get()>0&&gcAfter>gcBefore,"ENUMERATION_GC_THREAD_LIFETIME");
        ProbeFiles.require(CoverageTarget.initializations.get()==0,"OBSERVATION_HAS_NO_CLINIT");
        JSONObject future=lateHooks.finish(a,b,directory);
        JSONObject loaderTargets=LoaderTargetChecks.run(context,a,b);
        ProbeFiles.write(new File(directory,"maps-before-entry.txt"),ProbeFiles.read("/proc/self/maps"));
        JSONObject entryOwner=EntryOwnerEvidence.run(directory);
        return new JSONObject().put("lateHooks",future).put("loaderTargets",loaderTargets).put("entryOwner",entryOwner).put("small",receipt(small)).put("before",receipt(before)).put("loaded",receipt(loaded))
            .put("observations",observations).put("allocationBatches",batches.get()).put("gcBefore",gcBefore).put("gcAfter",gcAfter)
            .put("classRootsBefore",new JSONArray(rootsBefore)).put("classRootsAfter",new JSONArray(rootsAfter)).put("movedClasses",moved)
            .put("lateDexSha256",late.sha256).put("apkDexSha256",apk.sha256).put("caller",candidates.get(0).key())
            .put("initializationsBeforeInvoke",0).put("initializationsAfterInvoke",CoverageTarget.initializations.get())
            .put("distinctDefiningLoaders",true).put("partialBindingRejected",rejected)
            .put("futureClassPublicationCovered",false).put("entryInterpositionInstalled",true).put("productAccepted",false);
        }finally{lateHooks.close();}
    }
}
