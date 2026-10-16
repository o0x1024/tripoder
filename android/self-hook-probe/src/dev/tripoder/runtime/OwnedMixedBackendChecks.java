package dev.tripoder.runtime;
import dalvik.system.DexClassLoader;
import dev.tripoder.runtime.script.*;
import dev.tripoder.selfhook.ManagedHook;
import dev.tripoder.selfhook.NativeModules;
import dev.tripoder.selfhookprobe.NativeJniCaller;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;
/** Both real product backends under one actual product Platform and session. */
public final class OwnedMixedBackendChecks {
    private static final String TARGET="dev.tripoder.selfhookprobe.OwnedProductJavaTarget",MODULE="libtripoderownedhooks.so";
    private static int checks;
    private static void check(boolean value,String name){checks++;if(!value)throw new IllegalStateException(name);}
    private static Map<String,Object> map(Object... args){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<args.length;i+=2)out.put((String)args[i],args[i+1]);return out;}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return (Map<String,Object>)value;}
    private static Map<String,Object> request(SelfHookControl control,String generation,String command,Object... args){Map<String,Object> input=map("command",command,"processGeneration",generation);input.putAll(map(args));return HookWire.request(control.handle(HookWire.encode(input)));}
    private static Map<String,Object> success(Map<String,Object> reply){check(Boolean.TRUE.equals(reply.get("ok")),"MIXED_CONTROL:"+reply);return object(reply.get("result"));}
    public static JSONObject run(String apk,String sha,String generation,String library,String nativeSha,String audit)throws Exception {
        ManagedHook.initializeProcess();check(System.getProperty("tripoder.owned.product-java-initialized")==null,"MIXED_TARGET_NOT_INITIALIZED");
        ClassLoader loader=new DexClassLoader(apk,null,null,ClassLoader.getSystemClassLoader().getParent());File file=new File(library);
        Map<String,Object> identity=map("space","mixed-owned-space","installGeneration","mixed-owned-install","descriptorSha256",sha,"processGeneration",generation,
            "pid",(double)android.os.Process.myPid(),"target",map("packageName","dev.tripoder.selfhookprobe","versionCode","1","apkSha256",sha));
        RuntimeJavaObservation javaObservation=new RuntimeJavaObservation(()->loader,owner->owner==loader,()->true,identity);
        RuntimeNativeObservation nativeObservation=new RuntimeNativeObservation(new RuntimeNativeObservation.Inventory(){
            public List<String> names(){return List.of(MODULE);}
            public String path(String name)throws Exception{if(!MODULE.equals(name))throw new SecurityException("MIXED_NATIVE_MODULE_NAME");return file.getCanonicalPath();}
            public Map<String,Object> inspect(String name)throws Exception {
                if(!MODULE.equals(name)||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))).equals(nativeSha))throw new SecurityException("MIXED_NATIVE_ARTIFACT");
                return HookWire.request(RuntimeElfIdentity.inspect(file).put("moduleName",MODULE).put("path",library).put("sourcePath",library).put("sha256",nativeSha).toString());
            }
        },()->true,identity);
        RuntimeSelfHookPlatform platform=new RuntimeSelfHookPlatform(identity,javaObservation,nativeObservation);SelfHookControl control=new SelfHookControl(identity,platform,android.os.SystemClock::elapsedRealtime);
        check(NativeJniCaller.open(library,audit),"MIXED_NATIVE_LOADED");String buildId=RuntimeElfIdentity.inspect(file).getString("buildId");
        List<Map<String,Object>> selectors=List.of(
            map("id","nativeAdd","kind","native","moduleName",MODULE,"symbol","tripoder_hook_add","argumentTypes",List.of("int32","int32"),"returnType","int32","buildId",buildId,"signatureSource","confirmed"),
            map("id","javaValue","kind","java","className",TARGET,"methodName","increment","argumentTypes",List.of("int")),
            map("id","nativeExact","kind","native","moduleName",MODULE,"symbol","tripoder_hook_exact","argumentTypes",List.of("uint64"),"returnType","uint64","buildId",buildId,"signatureSource","confirmed"));
        Map<String,Object> observed=success(request(control,generation,"observe","selectors",selectors)),wire=object(observed.get("evidence"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> symbols=(List<Map<String,Object>>)wire.get("symbols");
        check(symbols.stream().map(symbol->symbol.get("id")).toList().equals(List.of("nativeAdd","javaValue","nativeExact")),"FULL_OBSERVATION_ORDER");
        check(platform.evidenceLive(new HookEvidence(wire)),"EXACT_FULL_MIXED_EVIDENCE");
        Map<String,Object> forged=new LinkedHashMap<>(wire);List<Map<String,Object>> changed=new ArrayList<>(symbols);
        Map<String,Object> altered=new LinkedHashMap<>(symbols.get(0));altered.put("buildId","00000000");changed.set(0,altered);forged.put("symbols",changed);
        check(!platform.evidenceLive(new HookEvidence(forged)),"NATIVE_PARTITION_CHANGE_REJECTED");
        changed=new ArrayList<>(symbols);altered=new LinkedHashMap<>(symbols.get(1));altered.put("methodName","other");changed.set(1,altered);forged.put("symbols",changed);
        check(!platform.evidenceLive(new HookEvidence(forged)),"JAVA_PARTITION_CHANGE_REJECTED");
        forged.put("symbols",List.of(symbols.get(0),symbols.get(1)));check(!platform.evidenceLive(new HookEvidence(forged)),"MISSING_PARTITION_SYMBOL_REJECTED");
        String source="import {definePlan,param} from '@abox/hook';export default definePlan({id:'mixed',target,params:{delta:param.integer(100,{minimum:0,maximum:1000})},setup(ctx){"
            +"ctx.java.method('"+TARGET+"','increment',['int']).after(({result,params})=>result+params.delta);"
            +"ctx.native.function('"+MODULE+"','tripoder_hook_add',{argumentTypes:['int32','int32'],returnType:'int32',buildId:'"+buildId+"'}).after(({result,params})=>result+params.delta);"
            +"ctx.native.function('"+MODULE+"','tripoder_hook_exact',{argumentTypes:['uint64'],returnType:'uint64',buildId:'"+buildId+"'}).after(({result})=>result+5n);}});";
        int before=ManagedHook.remainingCapacity();Map<String,Object> prepared=success(request(control,generation,"prepare","evidenceId",wire.get("id"),"source",source));
        check(System.getProperty("tripoder.owned.product-java-initialized")==null&&NativeModules.snapshot(0).references==0&&before==ManagedHook.remainingCapacity(),"MIXED_PREPARE_HAS_NO_PUBLICATION");
        Map<String,Object> active=success(request(control,generation,"activate","token",prepared.get("token"),"sourceSha256",prepared.get("sourceSha256")));
        check("ACTIVE".equals(active.get("state"))&&NativeModules.snapshot(0).references==2&&before-ManagedHook.remainingCapacity()==1,"BOTH_BACKENDS_ACTIVE_BEFORE_ACK");
        Class<?> target=Class.forName(TARGET,false,loader);Method increment=target.getDeclaredMethod("increment",int.class);
        check((Integer)increment.invoke(null,7)==108&&NativeJniCaller.add(3,4)==107,"SAME_SESSION_JAVA_AND_NATIVE_RULES");
        check(NativeJniCaller.exact(0xfedcba9876543210L)==0xfedcba9876543215L,"MIXED_NATIVE_BIGINT");
        String revision=(String)active.get("revision");success(request(control,generation,"updateParameters","revision",revision,"parameterVersion",1d,"parameters",Map.of("delta",250d)));
        check((Integer)increment.invoke(null,7)==258&&NativeJniCaller.add(3,4)==257,"ONE_PARAMETER_VERSION_FOR_BOTH_BACKENDS");
        Map<String,Object> disabled=success(request(control,generation,"disable","revision",revision));
        check("DISABLED".equals(disabled.get("state"))&&(Integer)increment.invoke(null,7)==8&&NativeJniCaller.add(3,4)==7&&NativeModules.snapshot(0).references==0,"BOTH_RESTORED_BEFORE_DISABLED_ACK");
        check(NativeJniCaller.close()&&platform.modules().isEmpty(),"MIXED_NATIVE_MODULE_UNLOAD");
        return new JSONObject().put("checks",checks).put("javaMethods",1).put("nativeExports",2).put("actualProductComposite",true).put("fullEvidencePartitions",true)
            .put("sameInvocationSession",true).put("productAuthorityConnected",false).put("productAccepted",false);
    }
}
