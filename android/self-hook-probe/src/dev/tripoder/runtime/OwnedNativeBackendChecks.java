package dev.tripoder.runtime;
import dev.tripoder.runtime.script.*;
import dev.tripoder.selfhook.NativeModules;
import dev.tripoder.selfhookprobe.NativeJniCaller;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
/** Actual product Native observation/backend/compiler under an owned command
 * transport. Broker grants and external operation journal are not simulated. */
public final class OwnedNativeBackendChecks {
    private static final String MODULE="libtripoderownedhooks.so";
    private static int checks;
    private static void check(boolean ok,String name){checks++;if(!ok)throw new IllegalStateException(name);}
    private static Map<String,Object> map(Object... args){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<args.length;i+=2)out.put((String)args[i],args[i+1]);return out;}
    private static Map<String,Object> request(SelfHookControl control,String generation,String command,Object... args){Map<String,Object> input=map("command",command,"processGeneration",generation);input.putAll(map(args));return HookWire.request(control.handle(HookWire.encode(input)));}
    @SuppressWarnings("unchecked") private static Map<String,Object> success(Map<String,Object> reply){check(Boolean.TRUE.equals(reply.get("ok")),"NATIVE_CONTROL_RESPONSE:"+reply);return (Map<String,Object>)reply.get("result");}
    private static void rejected(Map<String,Object> reply,String reason){check(Boolean.FALSE.equals(reply.get("ok"))&&String.valueOf(reply.get("error")).contains(reason),"NATIVE_CONTROL_REJECTION:"+reply);}
    @SuppressWarnings("unchecked") private static String evidence(Map<String,Object> observed){return (String)((Map<String,Object>)observed.get("evidence")).get("id");}
    private static Map<String,Object> selector(String id,String name,String result,String buildId,String... arguments){return map("id",id,"kind","native","moduleName",MODULE,"symbol",name,"argumentTypes",List.of(arguments),"returnType",result,"buildId",buildId,"signatureSource","confirmed");}
    private static String function(String name,String result,String buildId,String arguments){return "ctx.native.function('"+MODULE+"','"+name+"',{argumentTypes:["+arguments+"],returnType:'"+result+"',buildId:'"+buildId+"'})";}
    public static JSONObject run(String library,String nativeSha,String apkSha,String generation,String audit)throws Exception {
        File file=new File(library);AtomicBoolean live=new AtomicBoolean(true);
        Map<String,Object> identity=map("space","owned-native-space","installGeneration","owned-native-install","descriptorSha256",apkSha,"processGeneration",generation,
            "pid",(double)android.os.Process.myPid(),"target",map("packageName","dev.tripoder.selfhookprobe","versionCode","1","apkSha256",apkSha));
        RuntimeNativeObservation observation=new RuntimeNativeObservation(new RuntimeNativeObservation.Inventory(){
            public List<String> names(){return List.of(MODULE);}
            public String path(String name)throws Exception{if(!MODULE.equals(name))throw new SecurityException("OWNED_NATIVE_MODULE_NAME");return file.getCanonicalPath();}
            public Map<String,Object> inspect(String name)throws Exception {
                if(!MODULE.equals(name)||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))).equals(nativeSha))throw new SecurityException("OWNED_NATIVE_ARTIFACT_CHANGED");
                return HookWire.request(RuntimeElfIdentity.inspect(file).put("moduleName",MODULE).put("path",library).put("sourcePath",library).put("sha256",nativeSha).toString());
            }
        },live::get,identity);
        RuntimeNativeHookBackend backend=new RuntimeNativeHookBackend(observation);
        SelfHookControl.Platform platform=new SelfHookControl.Platform(){
            public Map<String,Object> identity(){return identity;}
            public Set<String> capabilities(){return Set.of("native-hook");}
            public List<Map<String,Object>> modules(){return observation.modules();}
            public List<Map<String,Object>> moduleEvents(){return observation.moduleEvents();}
            public SelfHookControl.Observation observe(List<Map<String,Object>> selectors){try{return new SelfHookControl.Observation(backend.observe(selectors),observation.modules());}catch(Exception error){throw new IllegalStateException(error);}}
            public boolean evidenceLive(HookEvidence evidence){return backend.live(evidence);}
            public HookInvocationSession.PreparedBackend prepare(DraftCompiler.Plan plan){return backend.prepare(plan);}
            public void discardObservation(){backend.discard();}
        };
        SelfHookControl control=new SelfHookControl(identity,platform,android.os.SystemClock::elapsedRealtime);
        check(observation.modules().isEmpty(),"REAL_MODULE_INVENTORY_BEFORE_LOAD");
        check(NativeJniCaller.open(library,audit),"LOAD_PRODUCT_NATIVE_FIXTURE");String buildId=RuntimeElfIdentity.inspect(file).getString("buildId");
        List<Map<String,Object>> available=observation.modules();check(available.size()==1&&"AVAILABLE".equals(available.get(0).get("state")),"REAL_ATTESTED_MODULE_INVENTORY");
        Map<String,Object> outside=new LinkedHashMap<>(selector("outside","tripoder_hook_add","int32",buildId,"int32","int32"));outside.put("moduleName","liboutside.so");
        rejected(request(control,generation,"observe","selectors",List.of(outside)),"HOOK_MODULE_OUTSIDE_GUEST");
        rejected(request(control,generation,"observe","selectors",List.of(selector("wrong","tripoder_hook_add","int32","00000000","int32","int32"))),"HOOK_MODULE_BUILD_ID_STALE");
        rejected(request(control,generation,"observe","selectors",List.of(selector("a","tripoder_hook_add","int32",buildId,"int32","int32"),selector("b","tripoder_hook_add","int32",buildId,"int32","int32"))),"HOOK_NATIVE_ADDRESS_ALIAS");
        rejected(request(control,generation,"observe","selectors",List.of(selector("pointer","tripoder_hook_add","int32",buildId,"pointer","int32"))),"HOOK_NATIVE_ABI_UNSUPPORTED");
        List<Map<String,Object>> selectors=List.of(selector("add","tripoder_hook_add","int32",buildId,"int32","int32"),selector("exact","tripoder_hook_exact","uint64",buildId,"uint64"),
            selector("fraction","tripoder_hook_fraction","double",buildId,"double"),selector("error","tripoder_hook_error","int32",buildId,"int32"));
        Map<String,Object> observed=success(request(control,generation,"observe","selectors",selectors));
        String add=function("tripoder_hook_add","int32",buildId,"'int32','int32'");
        String source="import {definePlan,param} from '@abox/hook';export default definePlan({id:'ownedNative',target,params:{delta:param.integer(100,{minimum:0,maximum:1000})},setup(ctx){"
            +add+".after(({result,params})=>result+params.delta);"
            +function("tripoder_hook_exact","uint64",buildId,"'uint64'")+".after(({result})=>result+5n);"
            +function("tripoder_hook_fraction","double",buildId,"'double'")+".before(({args})=>[args[0]+1]).after(({result})=>result+1.25);"
            +function("tripoder_hook_error","int32",buildId,"'int32'")+".after(({result})=>result+200);}});";
        Map<String,Object> prepared=success(request(control,generation,"prepare","evidenceId",evidence(observed),"source",source));
        check(NativeModules.snapshot(0).references==0,"SOURCE_PREPARATION_HAS_NO_NATIVE_PUBLICATION_LEASE");
        Map<String,Object> active=success(request(control,generation,"activate","token",prepared.get("token"),"sourceSha256",prepared.get("sourceSha256")));
        check("ACTIVE".equals(active.get("state"))&&NativeModules.snapshot(0).references==4,"REAL_NATIVE_ACTIVATION_ACK");
        check(NativeJniCaller.add(3,4)==107,"SCRIPT_NATIVE_NUMBER_ORIGINAL_AFTER");
        check(NativeJniCaller.exact(0xfedcba9876543210L)==0xfedcba9876543215L,"SCRIPT_NATIVE_UINT64_BIGINT_EXACT");
        check(NativeJniCaller.fraction(2.0)==4.75,"SCRIPT_NATIVE_DOUBLE_BEFORE_AFTER");
        check(NativeJniCaller.error(9)==209&&NativeJniCaller.count(3)==1,"SCRIPT_ORIGINAL_NATIVE_SIDE_EFFECT_ONCE");
        long[] workers=NativeJniCaller.concurrent();check(workers[0]==4&&workers[1]==400&&workers[2]==0,"PRODUCT_SCRIPT_ON_FOREIGN_NATIVE_THREADS");
        String revision=(String)active.get("revision");success(request(control,generation,"updateParameters","revision",revision,"parameterVersion",1d,"parameters",Map.of("delta",250d)));
        check(NativeJniCaller.add(3,4)==257,"NATIVE_PARAMETER_UPDATE_SNAPSHOT");
        rejected(request(control,generation,"updateParameters","revision",revision,"parameterVersion",1d,"parameters",Map.of("delta",1d)),"HOOK_PARAMETER_VERSION_STALE");
        Map<String,Object> disabled=success(request(control,generation,"disable","revision",revision));
        check("DISABLED".equals(disabled.get("state"))&&NativeJniCaller.add(3,4)==7&&NativeModules.snapshot(0).references==0,"PRODUCT_NATIVE_DISABLE_RESTORES");
        observed=success(request(control,generation,"observe","selectors",List.of(selector("renamed","tripoder_hook_add","int32",buildId,"int32","int32"))));
        source="import {definePlan} from '@abox/hook';export default definePlan({id:'newNative',target,params:{},setup(ctx){"+add+".replace(({args})=>args[0]+33);}});";
        prepared=success(request(control,generation,"prepare","evidenceId",evidence(observed),"source",source));
        active=success(request(control,generation,"activate","token",prepared.get("token"),"sourceSha256",prepared.get("sourceSha256")));
        int count=NativeJniCaller.count(0);check(NativeJniCaller.add(3,4)==36&&NativeJniCaller.count(0)==count,"NEW_IMMUTABLE_NATIVE_ACTIVATION_REPLACE_NO_ORIGINAL");
        success(request(control,generation,"disable","revision",active.get("revision")));
        observed=success(request(control,generation,"observe","selectors",List.of(selector("stale","tripoder_hook_add","int32",buildId,"int32","int32"))));
        check(NativeJniCaller.close(),"UNLOAD_NATIVE_TARGET_AFTER_DISABLE");
        rejected(request(control,generation,"prepare","evidenceId",evidence(observed),"source",source),"HOOK_EVIDENCE_STALE");
        List<Map<String,Object>> events=observation.moduleEvents();check(events.stream().anyMatch(event->"added".equals(event.get("event")))&&events.stream().anyMatch(event->"removed".equals(event.get("event"))),"REAL_NATIVE_NOTIFIER_EVENTS");
        return new JSONObject().put("checks",checks).put("exports",4).put("activations",2).put("foreignCalls",400).put("actualProductNativeBackend",true)
            .put("actualSourceCompiler",true).put("realModuleEvents",true).put("productAuthorityConnected",false).put("javaBackendConnected",false).put("productAccepted",false);
    }
}
