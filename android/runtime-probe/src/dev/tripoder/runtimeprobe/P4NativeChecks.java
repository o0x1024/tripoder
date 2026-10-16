package dev.tripoder.runtimeprobe;

import org.json.*;

/** Real dlopen/dlclose and C ABI observations from the owned guest. */
final class P4NativeChecks {
    private static final String LIB="libtripoderownedhooks.so";
    static void run(P4ProbeActivity probe)throws Exception{
        probe.reject("native-system-module-rejected",()->probe.hook("observe",new JSONObject().put("selectors",new JSONArray().put(selector("system","getpid","int32",new JSONArray(),"0").put("moduleName","libc.so")))),"HOOK_MODULE_OUTSIDE_GUEST");
        probe.reject("native-unloaded-module-rejected",()->probe.hook("observe",new JSONObject().put("selectors",new JSONArray().put(selector("missing","tripoder_hook_add","int32",new JSONArray().put("int32").put("int32"),"0")))),"HOOK_MODULE_NOT_UNIQUELY_LOADED");
        probe.check("native-owned-library-loaded",probe.activity("native-load").getJSONObject("native").getBoolean("loaded"),LIB);
        JSONObject baselineUnload=probe.activity("native-unload").getJSONObject("native");
        probe.check("native-baseline-unload-removes-mapping",baselineUnload.getInt("mappedCount")==0,baselineUnload);
        probe.activity("native-load");
        JSONObject module=module(probe);String build=module.getString("buildId");
        probe.check("native-live-module-identity",build.matches("[a-f0-9]{40}")&&module.getString("sha256").matches("[a-f0-9]{64}")&&module.getString("path").endsWith("/lib/"+LIB),module);
        JSONArray selectors=selectors(build);
        probe.reject("native-unknown-signature-rejected",()->probe.hook("observe",new JSONObject().put("selectors",new JSONArray().put(new JSONObject(selectors.getJSONObject(0).toString()).put("signatureSource","unknown")))),"HOOK_NATIVE_SELECTOR_FIELDS");
        probe.reject("native-build-id-mismatch",()->probe.hook("observe",new JSONObject().put("selectors",new JSONArray().put(new JSONObject(selectors.getJSONObject(0).toString()).put("buildId","0")))),"HOOK_MODULE_BUILD_ID_STALE");
        JSONObject prepared=prepare(probe,selectors,source(false));baseline(probe,"native-prepare-no-effects",probe.activity("native-calls").getJSONObject("native"));
        JSONObject active=activate(probe,prepared);probe.check("mixed-java-native-activation-ack",active.getString("state").equals("ACTIVE"),active);
        JSONObject result=probe.activity("native-calls").getJSONObject("native");
        probe.check("mixed-java-hook-real-call",result.getInt("javaValue")==70&&result.getInt("javaCalls")==0,result);
        probe.check("native-before-original-after",result.getInt("sum")==30&&result.getInt("sumCalls")==1,result);
        probe.check("native-uint64-exact",result.getString("exact").equals("18446744073709551613")&&result.getInt("exactCalls")==0,result);
        probe.check("native-double-abi",result.getDouble("fraction")==5.5&&result.getInt("fractionCalls")==1,result);
        JSONObject status=probe.hook("status",new JSONObject());probe.check("native-real-hit-count",status.getInt("hits")==4&&status.getInt("errors")==0,status);
        probe.check("native-disable-ack",probe.hook("disable",new JSONObject().put("revision",active.getString("revision"))).getString("state").equals("DISABLED"),"all four methods restored");
        baseline(probe,"native-disabled-originals",probe.activity("native-calls").getJSONObject("native"));
        JSONObject stale=prepare(probe,selectors,source(false));JSONObject unloaded=probe.activity("native-unload").getJSONObject("native");
        JSONObject afterUnload=probe.hook("status",new JSONObject());
        probe.check("native-unload-invalidates-preparation",unloaded.getInt("mappedCount")==0&&afterUnload.getString("state").equals("IDLE"),new JSONObject().put("guest",unloaded).put("hook",afterUnload));
        probe.activity("native-load");JSONObject reloaded=module(probe);
        probe.check("native-reload-new-generation",reloaded.getInt("moduleGeneration")!=module.getInt("moduleGeneration")&&reloaded.getString("buildId").equals(build),reloaded);
        probe.reject("native-old-prepare-token-after-reload",()->activate(probe,stale),"HOOK_PREPARATION_STALE");
        activate(probe,prepare(probe,selectors,source(true)));probe.expectDeath("native-failure");probe.process();
        probe.check("native-script-error-no-original-no-invented-return",probe.activity("native-audit").getJSONObject("native").getInt("originalCount")==0,"durable C original-call counter remains zero after process death");
        probe.check("native-restart-no-automatic-hook",probe.hook("status",new JSONObject()).getString("state").equals("IDLE"),"fresh process");
        probe.activity("native-load");activate(probe,prepare(probe,selectors,source(false)));
        JSONObject leased=module(probe);probe.check("native-active-module-has-real-core-references",leased.getLong("activeReferences")>0,leased);
        probe.expectDeath("native-unload");probe.process();
        probe.check("native-active-unload-process-invalidated",probe.hook("status",new JSONObject()).getString("state").equals("IDLE"),"explicitly started fresh process after observed death");
    }
    private static JSONObject module(P4ProbeActivity probe)throws Exception{JSONArray modules=probe.hook("modules",new JSONObject()).getJSONArray("modules");for(int i=0;i<modules.length();i++)if(modules.getJSONObject(i).getString("moduleName").equals(LIB))return modules.getJSONObject(i);throw new IllegalStateException("OWNED_MODULE_NOT_OBSERVED");}
    private static JSONObject selector(String id,String symbol,String result,JSONArray args,String build)throws Exception{return new JSONObject().put("id",id).put("kind","native").put("moduleName",LIB).put("symbol",symbol).put("returnType",result).put("argumentTypes",args).put("buildId",build).put("signatureSource","confirmed");}
    private static JSONArray selectors(String build)throws Exception{return new JSONArray()
        .put(selector("nativeSum","tripoder_hook_add","int32",new JSONArray().put("int32").put("int32"),build))
        .put(selector("nativeExact","tripoder_hook_exact","uint64",new JSONArray().put("uint64"),build))
        .put(selector("nativeFraction","tripoder_hook_fraction","double",new JSONArray().put("double"),build))
        .put(selector("nativeError","tripoder_hook_error","int32",new JSONArray().put("int32"),build)).put(P4Scripts.selectors().getJSONObject(0));}
    private static JSONObject prepare(P4ProbeActivity probe,JSONArray selectors,String source)throws Exception{JSONObject evidence=probe.hook("observe",new JSONObject().put("selectors",selectors)).getJSONObject("evidence");return probe.hook("prepare",new JSONObject().put("evidenceId",evidence.getString("id")).put("source",source));}
    private static JSONObject activate(P4ProbeActivity probe,JSONObject prepared)throws Exception{return probe.hook("activate",new JSONObject().put("token",prepared.getString("token")).put("sourceSha256",prepared.getString("sourceSha256")));}
    private static String source(boolean failure){String head="import {definePlan} from '@abox/hook';export default definePlan({id:'owned-native',target,params:{},setup(ctx){";
        if(failure)return head+nativeMethod("nativeError","['int32']","int32")+".replace(()=> 'invalid-int');}});";
        return head+nativeMethod("nativeSum","['int32','int32']","int32")+".before(({args})=>[args[0]+10,args[1]]).after(({result})=>result*2);"
            +nativeMethod("nativeExact","['uint64']","uint64")+".replace(({args})=>args[0]-1n);"
            +nativeMethod("nativeFraction","['double']","double")+".after(({result})=>result*2);"
            +P4Scripts.method("add","['int','int']")+".replace(()=>70);}});";}
    private static String nativeMethod(String id,String args,String result){return "ctx.native.function(symbols."+id+".moduleName,symbols."+id+".symbol,{argumentTypes:"+args+",returnType:'"+result+"',buildId:symbols."+id+".buildId})";}
    private static void baseline(P4ProbeActivity probe,String name,JSONObject result)throws Exception{probe.check(name,result.getInt("javaValue")==5&&result.getInt("javaCalls")==1&&result.getInt("sum")==5&&result.getInt("sumCalls")==1&&result.getString("exact").equals("18446744073709551614")&&result.getInt("exactCalls")==1&&result.getDouble("fraction")==2.75&&result.getInt("fractionCalls")==1,result);}
    private P4NativeChecks(){}
}
