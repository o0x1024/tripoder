package dev.tripoder.selfhookprobe;

import dev.tripoder.selfhook.dex.DexCallerClosure;
import dev.tripoder.selfhook.dex.DexFile;
import dev.tripoder.selfhook.coverage.CallerCoveragePlan;
import dev.tripoder.selfhook.coverage.CallerMethodBindings;
import dev.tripoder.selfhook.coverage.MethodObservation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual APK and defining-loader binding for the owned integer caller case. */
final class AbiCallerIndex {
    final Method[] callers;
    final JSONObject report;
    final CallerMethodBindings bindings;
    AbiCallerIndex(String apkPath)throws Exception{
        DexFile dex;
        try(ZipFile zip=new ZipFile(apkPath)){
            java.util.zip.ZipEntry entry=zip.getEntry("classes.dex");
            ProbeFiles.require(entry!=null&&entry.getSize()>0&&entry.getSize()<=DexFile.MAX_BYTES&&zip.getEntry("classes2.dex")==null,"ABI_INDEX_SINGLE_DEX");
            try(java.io.InputStream input=zip.getInputStream(entry)){byte[] bytes=input.readNBytes(DexFile.MAX_BYTES+1);ProbeFiles.require(bytes.length==entry.getSize(),"ABI_DEX_SIZE");dex=new DexFile(bytes);}
        }
        DexCallerClosure graph=new DexCallerClosure(Collections.singletonList(dex));String target="Ldev/tripoder/selfhookprobe/AbiFixture;->integer(I)I";
        ClassLoader loader=AbiFixture.class.getClassLoader();ArrayList<Method> found=new ArrayList<>();JSONArray methods=new JSONArray();
        for(DexFile.MethodRef ref:graph.candidates(target)){
            ProbeFiles.require(ref.descriptor.equals("(I)I")&&!ref.name.startsWith("<"),"ABI_INDEX_CALLER_SIGNATURE");
            Class<?> owner=Class.forName(ref.owner.substring(1,ref.owner.length()-1).replace('/','.'),false,loader);
            ProbeFiles.require(owner.getClassLoader()==loader,"ABI_INDEX_DEFINING_LOADER");
            Method method=owner.getDeclaredMethod(ref.name,int.class);ProbeFiles.require(method.getReturnType()==int.class,"ABI_INDEX_RETURN_TYPE");
            found.add(method);methods.put(ref.key());
        }
        ProbeFiles.require(!found.isEmpty(),"ABI_CALLER_CLOSURE_EMPTY");
        java.util.Map<String,Class<?>> classes=new java.util.HashMap<>();
        classes.put("Ldev/tripoder/selfhookprobe/AbiFixture;",AbiFixture.class);
        for(Method method:found)classes.put(dev.tripoder.selfhook.dex.AbiSignature.descriptor(method.getDeclaringClass()),method.getDeclaringClass());
        bindings=new CallerMethodBindings(new CallerCoveragePlan(Collections.singletonList(dex),target),loader,classes);
        callers=bindings.scopedProbeCallers(64);
        ProbeFiles.require(java.util.Arrays.equals(callers,found.toArray(new Method[0])),"SINGLE_CALLER_BINDING_AUTHORITY");
        report=new JSONObject().put("dexSha256",dex.sha256).put("target",target).put("callers",methods)
            .put("dynamicSites",graph.dynamicSites).put("externalSites",graph.externalSites).put("completeRuntimeCoverage",graph.completeRuntimeCoverage);
    }
    JSONObject observe()throws Exception{
        MethodObservation snapshot=MethodObservation.capture(bindings);JSONArray methods=new JSONArray();
        for(MethodObservation.ObservedMethod method:snapshot.methods)methods.put(new JSONObject()
            .put("source",method.binding.source.key()).put("dexIndex",method.dexIndex).put("sourceIndex",method.binding.source.index)
            .put("method",Long.toHexString(method.methodToken)).put("owner",Long.toHexString(method.ownerToken))
            .put("flags",method.flags).put("indices",method.indices).put("data",Long.toHexString(method.dataToken))
            .put("quick",Long.toHexString(method.quickEntryToken)));
        return new JSONObject().put("sequence",snapshot.observationSequence).put("methods",methods)
            .put("analysisDigest",bindings.plan.analysisDigest).put("dexSha256",report.getString("dexSha256"))
            .put("completeLoadedInventory",snapshot.completeLoadedInventory).put("completeRuntimeCoverage",snapshot.completeRuntimeCoverage);
    }
}
