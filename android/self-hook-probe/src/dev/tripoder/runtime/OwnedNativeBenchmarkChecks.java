package dev.tripoder.runtime;
import dev.tripoder.runtime.script.*;
import dev.tripoder.selfhook.NativeHook;
import dev.tripoder.selfhook.NativeModules;
import dev.tripoder.selfhookprobe.NativeBenchmark;
import dev.tripoder.selfhookprobe.NativeJniCaller;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;
import org.json.JSONArray;
/** Same source used against the archived core and upgraded core. Raw samples
 * retain clock and harness overhead; no subtraction or performance pass claim. */
public final class OwnedNativeBenchmarkChecks {
    private static final String MODULE="libtripoderownedhooks.so";
    private static final int SAMPLES=1000000,NATIVE_SAMPLES=100000;
    private static Map<String,Object> map(Object... args){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<args.length;i+=2)out.put((String)args[i],args[i+1]);return out;}
    @SuppressWarnings("unchecked") private static Map<String,Object> command(SelfHookControl control,String generation,String command,Object... args){
        Map<String,Object> input=map("command",command,"processGeneration",generation);input.putAll(map(args));
        Map<String,Object> reply=HookWire.request(control.handle(HookWire.encode(input)));
        if(!Boolean.TRUE.equals(reply.get("ok")))throw new IllegalStateException("BENCH_CONTROL:"+reply);return (Map<String,Object>)reply.get("result");
    }
    private static JSONObject save(File directory,String name,long[] samples)throws Exception {return save(directory,name,samples,1);}
    private static JSONObject save(File directory,String name,long[] samples,int batch)throws Exception {
        if(samples==null||samples.length<1000)throw new IllegalStateException("BENCH_SAMPLES");File file=new File(directory,name+".bin");
        long writeStart=System.nanoTime();try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file),65536))){for(long value:samples){if(value<0)throw new IllegalStateException("BENCH_CLOCK");out.writeLong(value);}}long writeNs=System.nanoTime()-writeStart;
        long[] sorted=samples.clone();Arrays.sort(sorted);double sum=0;for(long value:samples)sum+=value;
        return new JSONObject().put("count",samples.length).put("writeNs",writeNs).put("callsPerSample",batch).put("meanNs",sum/samples.length/batch).put("p50Ns",(double)sorted[sorted.length/2]/batch)
            .put("p95Ns",(double)sorted[(sorted.length*95)/100]/batch).put("p99Ns",(double)sorted[(sorted.length*99)/100]/batch)
            .put("rawFile",file.getName()).put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))));
    }
    private static long stat(String name){return Long.parseLong(android.os.Debug.getRuntimeStat("art.gc."+name));}
    private static long[] javaSamples(String name,JSONArray diagnostics)throws Exception{
        // Measurement storage is setup, not a part of the exercised API. Touch
        // its pages before warming the actual JNI allocation/callback path.
        long[] samples=new long[SAMPLES];Arrays.fill(samples,-1);
        long warm=System.nanoTime();do{if(NativeJniCaller.add(3,4)!=7)throw new IllegalStateException("BENCH_WARMUP");}while(System.nanoTime()-warm<500000000L);
        if(diagnostics==null){for(int i=0;i<SAMPLES;i++){long start=System.nanoTime();int value=NativeJniCaller.add(3,4);samples[i]=System.nanoTime()-start;if(value!=7)throw new IllegalStateException("BENCH_SEMANTICS");}return samples;}
        final int window=10000;JSONArray windows=new JSONArray();
        for(int begin=0;begin<SAMPLES;begin+=window){long gc=stat("gc-count"),gcTime=stat("gc-time"),blocking=stat("blocking-gc-count"),allocated=stat("bytes-allocated");long[] before=NativeBenchmark.measureState();
        for(int i=begin;i<begin+window;i++){
            long start=System.nanoTime();int value=NativeJniCaller.add(3,4);samples[i]=System.nanoTime()-start;
            if(value!=7)throw new IllegalStateException("BENCH_SEMANTICS");
        }
        long[] after=NativeBenchmark.measureState();windows.put(new JSONObject().put("begin",begin).put("count",window).put("before",new JSONArray(before)).put("after",new JSONArray(after)).put("gc",stat("gc-count")-gc).put("gcTimeMs",stat("gc-time")-gcTime).put("blockingGc",stat("blocking-gc-count")-blocking).put("allocatedBytes",stat("bytes-allocated")-allocated));
        }diagnostics.put(new JSONObject().put("path",name).put("windows",windows));return samples;
    }
    public static JSONObject run(String library,String nativeSha,String apkSha,String generation,File directory,int requestedCpu,boolean diagnose)throws Exception {
        int cpu=NativeBenchmark.pinThread(requestedCpu);if(cpu<0)throw new IllegalStateException("BENCH_AFFINITY_REQUIRED:"+cpu);
        File file=new File(library);Map<String,Object> identity=map("space","native-benchmark","installGeneration","owned","descriptorSha256",apkSha,
            "processGeneration",generation,"pid",(double)android.os.Process.myPid(),"target",map("packageName","dev.tripoder.selfhookprobe","versionCode","1","apkSha256",apkSha));
        RuntimeNativeObservation observation=new RuntimeNativeObservation(new RuntimeNativeObservation.Inventory(){
            public List<String> names(){return List.of(MODULE);}
            public String path(String name)throws Exception{if(!MODULE.equals(name))throw new IllegalArgumentException();return file.getCanonicalPath();}
            public Map<String,Object> inspect(String name)throws Exception{
                if(!MODULE.equals(name)||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))).equals(nativeSha))throw new IllegalStateException("BENCH_ARTIFACT");
                return HookWire.request(RuntimeElfIdentity.inspect(file).put("moduleName",MODULE).put("path",library).put("sourcePath",library).put("sha256",nativeSha).toString());
            }
        },()->true,identity);
        RuntimeNativeHookBackend backend=new RuntimeNativeHookBackend(observation);
        SelfHookControl control=new SelfHookControl(identity,new SelfHookControl.Platform(){
            public Map<String,Object> identity(){return identity;}public Set<String> capabilities(){return Set.of("native-hook");}
            public List<Map<String,Object>> modules(){return observation.modules();}public List<Map<String,Object>> moduleEvents(){return observation.moduleEvents();}
            public SelfHookControl.Observation observe(List<Map<String,Object>> selectors){try{return new SelfHookControl.Observation(backend.observe(selectors),observation.modules());}catch(Exception e){throw new IllegalStateException(e);}}
            public boolean evidenceLive(HookEvidence evidence){return backend.live(evidence);}
            public HookInvocationSession.PreparedBackend prepare(DraftCompiler.Plan plan){return backend.prepare(plan);}
            public void discardObservation(){backend.discard();}
        },android.os.SystemClock::elapsedRealtime);
        if(!NativeJniCaller.open(library,new File(directory,"audit.txt").getCanonicalPath()))throw new IllegalStateException("BENCH_LIBRARY");
        NativeModules.Snapshot module=NativeModules.snapshot(0);long rva=NativeJniCaller.rvas()[0];
        long[] nativeSamples=NativeBenchmark.nativeSamples(library,module.generation,rva,NATIVE_SAMPLES);
        if(nativeSamples==null||nativeSamples.length!=NATIVE_SAMPLES*2+3)throw new IllegalStateException("BENCH_NATIVE");
        JSONObject result=new JSONObject().put("javaSamplesPerPath",SAMPLES).put("nativeSamplesPerPath",NATIVE_SAMPLES).put("clockOverheadIncluded",true).put("workerCpu",cpu).put("warmupNsPerPath",500000000L).put("productAuthorityConnected",false);
        result.put("prepareNs",nativeSamples[NATIVE_SAMPLES*2]).put("publishNs",nativeSamples[NATIVE_SAMPLES*2+1]).put("retireNs",nativeSamples[NATIVE_SAMPLES*2+2]);
        JSONArray windows=diagnose?new JSONArray():null;result.put("windowDiagnostics",windows).put("diagnosticInstrumentation",diagnose);
        long[] jniBaseline=javaSamples("jniBaseline",windows);
        NativeHook.Handle handle=NativeHook.prepare(0,module.generation,rva,library,NativeHook.I32,new int[]{NativeHook.I32,NativeHook.I32},call->call.original(call.arguments(),call.incomingErrno()));
        NativeHook.publish(handle);long[] jniHook=javaSamples("jniHook",windows);
        long[] foreignJni=NativeBenchmark.foreignSamples(100000);NativeHook.retire(handle);
        String buildId=RuntimeElfIdentity.inspect(file).getString("buildId");
        Map<String,Object> selector=map("id","add","kind","native","moduleName",MODULE,"symbol","tripoder_hook_add","argumentTypes",List.of("int32","int32"),"returnType","int32","buildId",buildId,"signatureSource","confirmed");
        Map<String,Object> observed=command(control,generation,"observe","selectors",List.of(selector));
        String evidence=(String)((Map<?,?>)observed.get("evidence")).get("id");
        String source="import {definePlan} from '@abox/hook';export default definePlan({id:'bench',target,params:{},setup(ctx){ctx.native.function('"+MODULE+"','tripoder_hook_add',{argumentTypes:['int32','int32'],returnType:'int32',buildId:'"+buildId+"'}).after(({result})=>result);}});";
        Map<String,Object> prepared=command(control,generation,"prepare","evidenceId",evidence,"source",source);
        Map<String,Object> active=command(control,generation,"activate","token",prepared.get("token"),"sourceSha256",prepared.get("sourceSha256"));
        long[] productRule=javaSamples("productRule",windows);
        Map<String,Object> disabled=command(control,generation,"disable","revision",active.get("revision"));
        if(!"DISABLED".equals(disabled.get("state"))||!NativeJniCaller.close()||NativeModules.snapshot(0).live)throw new IllegalStateException("BENCH_RETIRE");
        if(!NativeBenchmark.verifyThread(cpu))throw new IllegalStateException("BENCH_AFFINITY_CHANGED");
        // Persist only after all timed paths finish: sorting, hashing and file
        // writes must not change the heap or page cache before the next path.
        result.put("directC",save(directory,"direct-c",Arrays.copyOfRange(nativeSamples,0,NATIVE_SAMPLES),64));
        result.put("hookC",save(directory,"hook-c",Arrays.copyOfRange(nativeSamples,NATIVE_SAMPLES,NATIVE_SAMPLES*2),64));
        result.put("jniBaseline",save(directory,"jni-baseline",jniBaseline));result.put("jniHook",save(directory,"jni-hook",jniHook));
        result.put("foreignJniHook",save(directory,"foreign-jni-hook",foreignJni));result.put("productRule",save(directory,"product-rule",productRule));
        return result.put("sampleBuffersPrefaulted",true).put("artifactWritesAfterMeasurements",true).put("affinityVerifiedAfterSamples",true).put("passed",true).put("semanticChecksPassed",true).put("performanceAccepted",false);
    }
}
