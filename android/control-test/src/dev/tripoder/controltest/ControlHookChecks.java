package dev.tripoder.controltest;

import android.os.*;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.util.UUID;

/** Production project/lease isolation; real behavior is covered by the owned UI guest gate. */
final class ControlHookChecks {
    private final ControlTestBase test;private IBinder session;private String project,binding,install,process;private JSONObject prepared;
    ControlHookChecks(ControlTestBase test,IBinder session){this.test=test;this.session=session;}
    void run()throws Exception{
        project=test.report.getString("project");binding=test.report.getString("bindingOperation");install=test.report.getString("installGeneration");
        Bundle targets=test.execute(session,"hookProcesses","project",project);JSONArray processes=new JSONArray(targets.getString("processes"));
        test.check("hook-lists-real-project-process",processes.length()==1&&binding.equals(targets.getString("bindingOperation")),test.json(targets));process=processes.getJSONObject(0).getString("processGeneration");
        test.report.put("workerPid",processes.getJSONObject(0).getInt("pid")).put("processGeneration",process);
        String other=project+"-other";Bundle ref=test.execute(session,"bindProject","operation",id(),"project",other,"space",targets.getString("space"),"package",targets.getString("package"),"generation",install);
        Bundle mismatch=request("status",new JSONObject());mismatch.putString("generation",id());
        test.check("hook-rejects-stale-install",test.rejected(()->ControlWire.call(session,ControlWire.EXECUTE,mismatch),IllegalStateException.class,"HOOK_PROJECT_BINDING_STALE"),true);
        JSONObject evidence=hook("observe",new JSONObject().put("selectors",selectors()));
        test.check("hook-observation-binds-actual-worker",evidence.getJSONObject("identity").getInt("pid")==processes.getJSONObject(0).getInt("pid"),evidence);
        Bundle conflict=request("observe",new JSONObject().put("selectors",selectors()));conflict.putString("project",other);conflict.putString("bindingOperation",ref.getString("bindingOperation"));
        test.check("another-project-cannot-replace-hook-context",test.rejected(()->ControlWire.call(session,ControlWire.EXECUTE,conflict),SecurityException.class,"HOOK_PROJECT_OWNER_CONFLICT"),true);
        test.check("project-cannot-rebind-while-hook-context-owned",test.rejected(()->test.execute(session,"bindProject","operation",id(),"project",project,"expectedBinding",binding,"space",targets.getString("space"),"package",targets.getString("package"),"generation",install),IllegalStateException.class,"PROJECT_HOOK_CONTEXT_IN_USE"),true);
        prepared=hook("prepare",new JSONObject().put("evidenceId",evidence.getJSONObject("evidence").getString("id")).put("source",source()));
        test.check("project-script-prepared",prepared.getString("state").equals("PREPARED"),prepared.getString("sourceSha256"));
        JSONObject active=hook("activate",new JSONObject().put("token",prepared.getString("token")).put("sourceSha256",prepared.getString("sourceSha256")));
        test.check("project-script-ack-is-worker-active",active.getString("state").equals("ACTIVE")&&active.getJSONObject("identity").getString("processGeneration").equals(process),active);
        test.report.put("runtimeStatus",hook("status",new JSONObject()));
        test.check("project-script-disable-confirmed",hook("disable",new JSONObject().put("revision",active.getString("revision"))).getString("state").equals("DISABLED"),true);
        int cycles=Integer.parseInt(test.args.getString("nativeCycles","1"));
        if(cycles<1||cycles>24)throw new IllegalArgumentException("OWNED_NATIVE_CYCLE_LIMIT");
        for(int cycle=1;cycle<cycles;cycle++){
            evidence=hook("observe",new JSONObject().put("selectors",selectors()));
            String exercisedSource=source()+"\n/* owned controller pressure "+cycle+" "+"x".repeat(3000)+" */";
            JSONObject draft=hook("prepare",new JSONObject().put("evidenceId",evidence.getJSONObject("evidence").getString("id")).put("source",exercisedSource));
            JSONObject running=hook("activate",new JSONObject().put("token",draft.getString("token")).put("sourceSha256",draft.getString("sourceSha256")));
            test.check("repeated-native-declaration-active-"+cycle,"ACTIVE".equals(running.getString("state")),running.getString("revision"));
            test.check("repeated-native-declaration-disabled-"+cycle,"DISABLED".equals(hook("disable",new JSONObject().put("revision",running.getString("revision"))).getString("state")),true);
        }
        test.report.put("nativeActivationCycles",cycles);
        evidence=hook("observe",new JSONObject().put("selectors",selectors()));
        prepared=hook("prepare",new JSONObject().put("evidenceId",evidence.getJSONObject("evidence").getString("id")).put("source",source()));
    }
    void reconnected(IBinder next)throws Exception{
        session=next;
        test.check("new-lease-cannot-activate-old-preparation",test.rejected(()->hook("activate",new JSONObject().put("token",prepared.getString("token")).put("sourceSha256",prepared.getString("sourceSha256"))),SecurityException.class,"HOOK_PREPARATION_LEASE_REVOKED"),true);
        test.check("new-lease-can-query-old-preparation",hook("status",new JSONObject()).getString("state").equals("PREPARED"),true);
        test.check("new-lease-explicitly-discards-old-preparation",hook("discard",new JSONObject()).getString("state").equals("IDLE"),true);
        test.check("discarded-project-context-cannot-activate",test.rejected(()->hook("activate",new JSONObject().put("token",prepared.getString("token")).put("sourceSha256",prepared.getString("sourceSha256"))),IllegalStateException.class,"HOOK_PROJECT_OBSERVATION_REQUIRED"),true);
    }
    private Bundle request(String command,JSONObject fields)throws Exception{JSONObject raw=new JSONObject(fields.toString()).put("command",command).put("processGeneration",process);return ControlWire.request("hook","operation",id(),"project",project,"bindingOperation",binding,"generation",install,"processGeneration",process,"request",raw.toString());}
    private JSONObject hook(String command,JSONObject fields)throws Exception{
        Bundle request=request(command,fields),reply=ControlWire.call(session,ControlWire.EXECUTE,request);Bundle receipt=test.execute(session,"query","operation",request.getString("operation"));
        if(!receipt.getString("state").equals("SUCCEEDED")||new JSONObject(receipt.getString("result")).has("token"))throw new IllegalStateException("HOOK_CONTROL_RECEIPT_INVALID");
        return new JSONObject(reply.getString("result")).getJSONObject("result");
    }
    private static JSONArray selectors()throws Exception{return new JSONArray().put(new JSONObject().put("id","read").put("kind","java").put("className","dev.tripoder.p0.shared.NativeProbe").put("methodName","canRead").put("argumentTypes",new JSONArray().put("java.lang.String")));}
    private static String source(){return "import {definePlan} from '@abox/hook';export default definePlan({id:'control-owned',target,params:{},setup(ctx){ctx.java.method(symbols.read.className,symbols.read.methodName,['java.lang.String']).replace(()=>false);}});";}
    private static String id(){return UUID.randomUUID().toString();}
}
