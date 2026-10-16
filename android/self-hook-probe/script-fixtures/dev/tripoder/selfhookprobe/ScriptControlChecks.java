package dev.tripoder.selfhookprobe;

import dev.tripoder.runtime.script.*;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import static dev.tripoder.selfhookprobe.ScriptFixturePlatform.map;

/** Real ManagedHook integration, restricted to four explicit owned methods and their known direct callers. */
final class ScriptControlChecks {
    private final ScriptFixturePlatform platform;
    private final SelfHookControl control;
    private final File directory;
    private final String compilation,generation;
    private final JSONArray commands=new JSONArray(),compiled=new JSONArray(),sources=new JSONArray();
    private int checks,commandNumber;
    ScriptControlChecks(ScriptFixturePlatform platform,File directory,String compilation,String generation){
        this.platform=platform;this.directory=directory;this.compilation=compilation;this.generation=generation;
        control=new SelfHookControl(platform.identity(),platform,android.os.SystemClock::elapsedRealtime);
    }
    private void check(boolean value,String reason){checks++;ProbeFiles.require(value,reason);}
    private Map<String,Object> command(String name,Object... fields)throws Exception{
        Map<String,Object> request=map("command",name,"processGeneration",generation);for(int i=0;i<fields.length;i+=2)request.put((String)fields[i],fields[i+1]);
        String raw=HookWire.encode(request),response=control.handle(raw);
        commands.put(new JSONObject().put("sequence",++commandNumber).put("request",new JSONObject(raw)).put("response",new JSONObject(response)));
        return HookWire.request(response);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> result(Map<String,Object> response){check(Boolean.TRUE.equals(response.get("ok")),"SCRIPT_COMMAND_FAILED:"+response);return (Map<String,Object>)response.get("result");}
    private void rejected(Map<String,Object> response,String reason){check(Boolean.FALSE.equals(response.get("ok"))&&String.valueOf(response.get("error")).contains(reason),"SCRIPT_EXPECTED_REJECTION:"+reason+":"+response);}
    @SuppressWarnings("unchecked") private String observe()throws Exception{return (String)((Map<String,Object>)result(command("observe","selectors",platform.selectors())).get("evidence")).get("id");}
    private static Object invoke(Method method,Object value)throws Throwable{try{return method.invoke(null,value);}catch(InvocationTargetException error){throw error.getCause();}}
    private Object call(String id,Object value)throws Throwable{return invoke(platform.target(id).caller(),value);}
    private String activate(String phase,String source)throws Exception{
        String evidence=observe();File file=new File(directory,"script-source-"+phase+".ts");ProbeFiles.require(file.createNewFile(),"SCRIPT_SOURCE_FILE_EXISTS");ProbeFiles.write(file,source);
        Map<String,Object> prepared=result(command("prepare","evidenceId",evidence,"source",source));
        check(DraftCompiler.digest(source).equals(prepared.get("sourceSha256")),"SCRIPT_PREPARED_SOURCE_SHA");sources.put(new JSONObject().put("phase",phase).put("file",file.getName()).put("sha256",ProbeFiles.sha(file)).put("compilerVersion",DraftCompiler.VERSION));
        Map<String,Object> active=result(command("activate","token",prepared.get("token"),"sourceSha256",prepared.get("sourceSha256")));check("ACTIVE".equals(active.get("state")),"SCRIPT_ACTIVATION_ACK");return (String)active.get("revision");
    }
    private void disable(String revision)throws Exception{check("DISABLED".equals(result(command("disable","revision",revision)).get("state")),"SCRIPT_DISABLE_AFTER_DRAIN");}
    private static String plan(String body){return "import {definePlan,param} from '@abox/hook'; export default definePlan({id:'owned-script',target,params:{delta:param.integer(2,{minimum:0,maximum:10})},setup(ctx){"+body+"}});";}
    private static String method(String id,String name,String type){return "ctx.java.method(symbols."+id+".className,'"+name+"',['"+type+"'])";}
    JSONObject run()throws Throwable{
        for(ScriptFixturePlatform.Target target:platform.targets())awaitCompilation(target);
        rejected(command("observe","selectors",List.of(map("id","integer","kind","java","className","java.lang.String","methodName","length","argumentTypes",List.of()))),"OWNED_SCRIPT_TARGET_REQUIRED");
        rejected(command("activate","token","0".repeat(64),"sourceSha256","0".repeat(64)),"HOOK_PREPARATION_STALE");
        check(platform.publishes==0&&platform.nativeReports().length()==0,"SCRIPT_UNKNOWN_TARGET_NEVER_INSTALLED");
        String evidence=observe();String source=plan(
            method("integer","scriptIntOwned","int")+".before(({args})=>[args[0]+1]).after(({result,params})=>result+params.delta);"+
            method("wide","scriptLongOwned","long")+".after(({result})=>result+1n);"+
            method("single","scriptFloatOwned","float")+".before(()=>[16777217]).after(({result,args})=>args[0]===16777217?result:0);"+
            method("failure","scriptFailureOwned","int")+".before(({args})=>args).after(({result})=>result);");
        File primary=new File(directory,"script-source-primary.ts");ProbeFiles.require(primary.createNewFile(),"SCRIPT_SOURCE_FILE_EXISTS");ProbeFiles.write(primary,source);
        Map<String,Object> prepared=result(command("prepare","evidenceId",evidence,"source",source));
        check(platform.prepares==0&&platform.publishes==0&&platform.nativeReports().length()==0,"SOURCE_PREPARE_HAS_NO_MANAGED_HOOK_SIDE_EFFECT");
        int before=ScriptOwnedFixture.intCalls.get();check(call("integer",7).equals(8)&&ScriptOwnedFixture.intCalls.get()==before+1,"PREPARED_TARGET_REMAINS_ORIGINAL");
        check(prepared.get("sourceSha256").equals(DraftCompiler.digest(source)),"PRIMARY_SOURCE_SHA_BOUND");
        sources.put(new JSONObject().put("phase","primary").put("file",primary.getName()).put("sha256",ProbeFiles.sha(primary)).put("compilerVersion",DraftCompiler.VERSION));
        rejected(command("activate","token",prepared.get("token"),"sourceSha256","0".repeat(64)),"HOOK_PREPARATION_STALE");
        Map<String,Object> active=result(command("activate","token",prepared.get("token"),"sourceSha256",prepared.get("sourceSha256")));String revision=(String)active.get("revision");
        check("ACTIVE".equals(active.get("state"))&&platform.publishes==1,"PRIMARY_ACTUAL_MANAGED_PUBLICATION");
        rejected(command("activate","token",prepared.get("token"),"sourceSha256",prepared.get("sourceSha256")),"HOOK_PREPARATION_STALE");check(platform.publishes==1,"DUPLICATE_ACTIVATE_NO_REPLAY");
        int ints=ScriptOwnedFixture.intCalls.get(),longs=ScriptOwnedFixture.longCalls.get(),floats=ScriptOwnedFixture.floatCalls.get(),failures=ScriptOwnedFixture.failureCalls.get();
        for(int i=0;i<100;i++){
            check(call("integer",7).equals(11),"SCRIPT_INT_BEFORE_ORIGINAL_AFTER");
            check(call("wide",9007199254740993L).equals(9007199254740994L),"SCRIPT_LONG_EXACT_BEYOND_2_53");
            check(call("single",0.0f).equals(16777216.0f)&&ScriptOwnedFixture.lastFloatBits==Float.floatToRawIntBits(16777216.0f),"SCRIPT_FLOAT_ABI_AND_AFTER_SNAPSHOT");
            check(call("failure",2).equals(2),"SCRIPT_FAILURE_TARGET_NORMAL");
        }
        check(ScriptOwnedFixture.intCalls.get()==ints+100&&ScriptOwnedFixture.longCalls.get()==longs+100&&ScriptOwnedFixture.floatCalls.get()==floats+100&&ScriptOwnedFixture.failureCalls.get()==failures+100,"ALL_ORIGINALS_EXACTLY_ONCE");
        try{call("failure",-1);check(false,"SCRIPT_ORIGINAL_EXCEPTION_MISSING");}catch(Throwable error){check(error==ScriptOwnedFixture.MARKER,"SCRIPT_ORIGINAL_THROWABLE_IDENTITY");}
        check(ScriptOwnedFixture.failureCalls.get()==failures+101&&((Number)result(command("status")).get("errors")).longValue()==0,"ORIGINAL_THROW_NOT_SCRIPT_ERROR_OR_RETRY");
        concurrent();
        check(ScriptOwnedFixture.intCalls.get()==ints+500,"CONCURRENT_SCRIPT_ORIGINAL_EXACTLY_ONCE");
        ScriptOwnedFixture.entered=new CountDownLatch(1);ScriptOwnedFixture.release=new CountDownLatch(1);AtomicReference<Object> returned=new AtomicReference<>();AtomicReference<Throwable> thrown=new AtomicReference<>();
        Thread worker=new Thread(()->{try{returned.set(call("integer",41));}catch(Throwable error){thrown.set(error);}},"owned-script-inflight");worker.start();
        try{
            check(ScriptOwnedFixture.entered.await(5,TimeUnit.SECONDS),"SCRIPT_ORIGINAL_IN_FLIGHT");
            Map<String,Object> updated=result(command("updateParameters","revision",revision,"parameterVersion",1d,"parameters",map("delta",5d)));check(((Number)updated.get("parameterVersion")).intValue()==2,"SCRIPT_PARAMETER_CAS");
            rejected(command("updateParameters","revision",revision,"parameterVersion",1d,"parameters",map("delta",3d)),"HOOK_PARAMETER_VERSION_STALE");
            check(call("integer",7).equals(14),"SCRIPT_NEW_PARAMETER_VERSION");
            check("DRAINING".equals(result(command("disable","revision",revision)).get("state"))&&platform.retires==0,"SCRIPT_INFLIGHT_NOT_RETIRED");
            check(call("integer",7).equals(8),"SCRIPT_DRAINING_NEW_CALL_ORIGINAL");
        }finally{ScriptOwnedFixture.release.countDown();worker.join(5000);ScriptOwnedFixture.entered=null;ScriptOwnedFixture.release=null;}
        check(!worker.isAlive()&&thrown.get()==null&&Integer.valueOf(45).equals(returned.get()),"SCRIPT_OLD_PARAMETER_SNAPSHOT_RETAINED");
        check("DISABLED".equals(result(command("status")).get("state"))&&platform.retires==1,"SCRIPT_RETIRED_AFTER_OLD_CALL");
        check(call("integer",7).equals(8)&&ScriptOwnedFixture.intCalls.get()==ints+504,"SCRIPT_DISABLED_PHYSICAL_ORIGINAL");
        rejected(command("prepare","evidenceId",evidence,"source",source),"HOOK_EVIDENCE_STALE");
        String replacement=plan(method("wide","scriptLongOwned","long")+".replace(({args})=>args[0]+2n);"+method("single","scriptFloatOwned","float")+".replace(()=>-0);");
        revision=activate("replace",replacement);int longBefore=ScriptOwnedFixture.longCalls.get(),floatBefore=ScriptOwnedFixture.floatCalls.get();
        check(call("wide",Long.MAX_VALUE-2).equals(Long.MAX_VALUE),"SCRIPT_REPLACE_LONG_BOUNDARY");
        check(Float.floatToRawIntBits((Float)call("single",1f))==0x80000000,"SCRIPT_REPLACE_NEGATIVE_ZERO");
        check(ScriptOwnedFixture.longCalls.get()==longBefore&&ScriptOwnedFixture.floatCalls.get()==floatBefore,"SCRIPT_REPLACE_NEVER_CALLS_ORIGINAL");disable(revision);
        revision=activate("bad-before",plan(method("integer","scriptIntOwned","int")+".before(()=>['bad']);"));int count=ScriptOwnedFixture.intCalls.get();
        scriptFailure("integer",7);check(ScriptOwnedFixture.intCalls.get()==count,"SCRIPT_BEFORE_ERROR_NO_ORIGINAL");disable(revision);
        revision=activate("bad-after",plan(method("integer","scriptIntOwned","int")+".after(()=>'bad');"));count=ScriptOwnedFixture.intCalls.get();
        scriptFailure("integer",7);check(ScriptOwnedFixture.intCalls.get()==count+1,"SCRIPT_AFTER_ERROR_ORIGINAL_ONCE_NO_RETRY");disable(revision);
        check(platform.publishes==4&&platform.retires==4,"SCRIPT_ALL_OWNED_INSTALLATIONS_RETIRED");
        JSONArray reports=platform.nativeReports();for(int i=0;i<reports.length();i++){
            JSONObject item=reports.getJSONObject(i);String symbol=item.getString("symbol");long expected=symbol.equals("integer")?504:symbol.equals("failure")?101:100;
            check(item.getJSONObject("native").getLong("originals")==expected,"SCRIPT_NATIVE_ORIGINAL_COUNTER_"+symbol);
        }
        return new JSONObject().put("checks",checks).put("scope","owned-four-methods-four-direct-callers").put("productBackend",false).put("fullCallerCoverage",false)
            .put("authorityApprovalPath",false).put("compilerVersion",DraftCompiler.VERSION).put("sourceFiles",sources).put("commands",commands).put("compilation",compiled)
            .put("callerEvidence",platform.callerEvidence).put("native",reports).put("publications",platform.publicationEvents)
            .put("primaryRounds",100).put("concurrentThreads",4).put("concurrentCalls",400).put("originalThrowableIdentity",true).put("inFlightOldParameterSnapshot",true)
            .put("physicalBodyDeltas",new JSONObject().put("integer",ScriptOwnedFixture.intCalls.get()-ints).put("wide",ScriptOwnedFixture.longCalls.get()-longs).put("single",ScriptOwnedFixture.floatCalls.get()-floats).put("failure",ScriptOwnedFixture.failureCalls.get()-failures))
            .put("prepareDidNotPublish",true).put("unknownTargetRejected",true).put("allRetired",true).put("requestedCompilation",compilation);
    }
    private void scriptFailure(String id,Object argument)throws Throwable{
        try{call(id,argument);check(false,"SCRIPT_ERROR_MISSING");}catch(IllegalStateException error){check(error.getMessage().startsWith("HOOK_EXECUTION:"),"SCRIPT_ERROR_EXPLICIT");}
        check(((Number)result(command("status")).get("errors")).longValue()==1,"SCRIPT_ERROR_COUNT");
    }
    private void concurrent()throws Throwable{
        AtomicInteger errors=new AtomicInteger(),completed=new AtomicInteger();Thread[] workers=new Thread[4];
        for(int t=0;t<workers.length;t++){workers[t]=new Thread(()->{for(int i=0;i<100;i++){try{if(!Integer.valueOf(i+4).equals(call("integer",i)))errors.incrementAndGet();}catch(Throwable error){errors.incrementAndGet();}completed.incrementAndGet();}},"owned-script-worker-"+t);workers[t].start();}
        for(Thread worker:workers){worker.join(10000);check(!worker.isAlive(),"SCRIPT_WORKER_JOINED");}check(errors.get()==0&&completed.get()==400,"SCRIPT_CONCURRENT_EXACT_RESULTS");
    }
    private void awaitCompilation(ScriptFixturePlatform.Target target)throws Throwable{
        Object value;if(target.type().equals("long"))value=Long.valueOf(7);else if(target.type().equals("float"))value=Float.valueOf(7);else value=Integer.valueOf(7);
        long deadline=android.os.SystemClock.elapsedRealtime()+10000;JSONObject original,caller;
        do{
            for(int i=0;i<10000;i++){invoke(target.method(),value);invoke(target.caller(),value);}
            original=ArtProbe.sample(target.method());caller=ArtProbe.sample(target.caller());
            if(compilation.equals(ArtCompilation.kind(original))&&compilation.equals(ArtCompilation.kind(caller)))break;
            ProbeFiles.require(android.os.SystemClock.elapsedRealtime()<deadline,"SCRIPT_COMPILATION_NOT_OBSERVED:"+target.id());Thread.sleep(10);
        }while(true);
        compiled.put(new JSONObject().put("symbol",target.id()).put("target",original).put("caller",caller).put("observedKind",compilation));
        ProbeFiles.write(new File(directory,"script-compilation-"+target.id()+".json"),new JSONObject().put("target",original).put("caller",caller).toString(2)+"\n");
    }
}
