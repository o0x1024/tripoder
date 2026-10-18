package dev.tripoder.controltest;

import android.os.*;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.util.UUID;

/** Original target xlog query, attested against its APK; the result is forwarded unchanged. */
final class TargetNativeHookChecks {
    private static final String SYMBOL="__xlogger_Level_impl";
    private final String moduleName,sha,prefix;
    private final ControlTestBase test;private final IBinder session;private final JSONObject target;private final String process;
    TargetNativeHookChecks(ControlTestBase test,IBinder session,JSONObject target,String process,boolean wechat){this.test=test;this.session=session;this.target=target;this.process=process;
        moduleName=wechat?"libwechatxlog.so":"libmarsxlog.so";prefix=wechat?"wechat-":"honor-";
        sha=wechat?"691053c6b8e120d989f4f3d03bff62f3e32113b2508b4fd3acdbbadcda077cec":"6e5bfb02cecfe516c392795f8d4d01964b218ed3bb8cca4711a08125e73e3225";
    }
    private JSONObject hook(String command,JSONObject fields)throws Exception{
        fields.put("command",command).put("processGeneration",process);String operation=UUID.randomUUID().toString();
        Bundle reply=ControlWire.call(session,ControlWire.EXECUTE,ControlWire.request("hook","operation",operation,"project",target.getString("project"),"bindingOperation",target.getString("binding"),"generation",target.getString("generation"),"processGeneration",process,"request",fields.toString()));
        Bundle receipt=test.execute(session,"query","operation",operation);
        if(!"SUCCEEDED".equals(receipt.getString("state")))throw new IllegalStateException("TARGET_HOOK_RECEIPT_NOT_SUCCEEDED");
        return new JSONObject(reply.getString("result")).getJSONObject("result");
    }
    private JSONObject module()throws Exception{
        JSONArray modules=hook("modules",new JSONObject()).getJSONArray("modules");JSONObject found=null;
        for(int i=0;i<modules.length();i++)if(moduleName.equals(modules.getJSONObject(i).getString("moduleName"))){if(found!=null)throw new IllegalStateException("TARGET_MODULE_AMBIGUOUS");found=modules.getJSONObject(i);}
        if(found==null)throw new IllegalStateException("TARGET_MODULE_NOT_LOADED");return found;
    }
    void run()throws Exception{
        JSONObject module=module();test.report.put("realNativeModule",module);
        test.check(prefix+"native-module-is-original-artifact",sha.equals(module.optString("sha256"))&&"AVAILABLE".equals(module.optString("state")),module);
        String build=module.getString("buildId");JSONArray selectors=new JSONArray().put(new JSONObject().put("id","level").put("kind","native").put("moduleName",moduleName).put("symbol",SYMBOL).put("argumentTypes",new JSONArray()).put("returnType","int32").put("buildId",build).put("signatureSource","confirmed"));
        JSONObject observed=hook("observe",new JSONObject().put("selectors",selectors));test.report.put("realNativeObservation",observed);
        String source="import {definePlan} from '@abox/hook';export default definePlan({id:'target-xlog-original',target,params:{},setup(ctx){ctx.native.function(symbols.level.moduleName,symbols.level.symbol,{argumentTypes:[],returnType:'int32',buildId:symbols.level.buildId}).after(({result})=>result);}});";
        JSONObject prepared=hook("prepare",new JSONObject().put("evidenceId",observed.getJSONObject("evidence").getString("id")).put("source",source));
        test.check(prefix+"native-source-prepared","PREPARED".equals(prepared.getString("state")),prepared.getString("sourceSha256"));
        JSONObject active=hook("activate",new JSONObject().put("token",prepared.getString("token")).put("sourceSha256",prepared.getString("sourceSha256")));String revision=active.getString("revision");
        Exception primary=null;long hits=0;
        try{
            test.check(prefix+"native-hook-active","ACTIVE".equals(active.getString("state")),active);
            JSONObject status;long deadline=SystemClock.elapsedRealtime()+30000;
            do{status=hook("status",new JSONObject());if(status.getInt("errors")!=0||status.getLong("hits")>0)break;SystemClock.sleep(1000);}while(SystemClock.elapsedRealtime()<deadline);
            test.report.put("realNativeActiveStatus",status);hits=status.getLong("hits");
            test.check(prefix+"native-real-original-forwarded-calls",hits>0&&status.getInt("errors")==0,status);
        }catch(Exception error){primary=error;throw error;}
        finally{
            try{JSONObject disabled=hook("disable",new JSONObject().put("revision",revision));test.report.put("realNativeDisabledStatus",disabled);test.check(prefix+"native-disable-confirmed","DISABLED".equals(disabled.getString("state")),disabled);}
            catch(Exception error){if(primary!=null)primary.addSuppressed(error);else throw error;}
        }
        // DISABLED is the terminal receipt returned by disable; subsequent
        // status is IDLE because the controller has released that revision.
        JSONObject idle=hook("status",new JSONObject());
        test.check(prefix+"native-disabled-revision-released","IDLE".equals(idle.getString("state")),idle);
        SystemClock.sleep(5000);JSONObject restored=hook("status",new JSONObject());test.report.put("realNativeRestoredStatus",restored);
        test.check(prefix+"native-disabled-hook-stays-inactive","IDLE".equals(restored.getString("state")),restored);
        JSONObject restoredModule=module();test.check(prefix+"native-module-lease-released",restoredModule.getLong("activeReferences")==module.getLong("activeReferences"),restoredModule);
    }
    void managedJni()throws Exception{
        if(!"wechat-".equals(prefix))throw new IllegalArgumentException("MANAGED_JNI_TARGET_NOT_DEFINED");
        // The original classes13.dex declares this virtual native method as (J)I.
        JSONArray selectors=new JSONArray().put(new JSONObject().put("id","javaLevel").put("kind","java").put("className","com.tencent.mars.xlog.Xlog").put("methodName","getLogLevel").put("argumentTypes",new JSONArray().put("long")));
        JSONObject evidence=hook("observe",new JSONObject().put("selectors",selectors));test.report.put("realManagedJniObservation",evidence);
        String source="import {definePlan} from '@abox/hook';export default definePlan({id:'wechat-managed-jni-original',target,params:{},setup(ctx){ctx.java.method(symbols.javaLevel.className,symbols.javaLevel.methodName,['long']).after(({result})=>result);}});";
        JSONObject draft=hook("prepare",new JSONObject().put("evidenceId",evidence.getJSONObject("evidence").getString("id")).put("source",source));
        test.check("wechat-managed-jni-prepared","PREPARED".equals(draft.getString("state")),draft.getString("sourceSha256"));
        JSONObject active=hook("activate",new JSONObject().put("token",draft.getString("token")).put("sourceSha256",draft.getString("sourceSha256")));
        String revision=active.getString("revision");Exception primary=null;
        try{
            test.check("wechat-managed-jni-active","ACTIVE".equals(active.getString("state")),active);
            JSONObject status;long deadline=SystemClock.elapsedRealtime()+30000;
            do{status=hook("status",new JSONObject());if(status.getInt("errors")!=0||status.getLong("hits")>0)break;SystemClock.sleep(1000);}while(SystemClock.elapsedRealtime()<deadline);
            test.report.put("realManagedJniActiveStatus",status);
            test.check("wechat-managed-jni-real-original-forwarded-calls",status.getLong("hits")>0&&status.getInt("errors")==0,status);
        }catch(Exception error){primary=error;throw error;}
        finally{
            try{JSONObject disabled=hook("disable",new JSONObject().put("revision",revision));test.report.put("realManagedJniDisabledStatus",disabled);test.check("wechat-managed-jni-disable-confirmed","DISABLED".equals(disabled.getString("state")),disabled);}
            catch(Exception error){if(primary!=null)primary.addSuppressed(error);else throw error;}
        }
        JSONObject idle=hook("status",new JSONObject());test.check("wechat-managed-jni-released","IDLE".equals(idle.getString("state")),idle);
    }
}
