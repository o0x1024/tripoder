package dev.tripoder.runtime.script;

import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.*;
import static dev.tripoder.runtime.script.ScriptContractTest.*;

/** Exercises unchanged source commands through the real parser/compiler and a non-instrumenting backend. */
public final class SourceControlTest {
    static final String SOURCE="import { definePlan, param } from '@abox/hook'; export default definePlan({id:'owned-plan',target,params:{delta:param.integer(2,{minimum:0,maximum:10})},setup(ctx){ctx.java.method('owned.Fixture','value',['int']).before(({args})=>[args[0]+1]).after(({result,params})=>result+params.delta);}});";
    static final Map<String,Object> TARGET=m("packageName","owned.fixture","versionCode","1","apkSha256","a".repeat(64));
    static final Map<String,Object> IDENTITY=m("space","owned-space","installGeneration","install-1","descriptorSha256","b".repeat(64),"processGeneration","process-1","pid",123d,"target",TARGET);
    static final Map<String,Object> SYMBOL=m("id","owned","kind","java","loaded",true,"className","owned.Fixture","methodName","value","classLoaderId","owned-loader","argumentTypes",l("int"),"returnType","int");
    static final Map<String,Object> SELECTOR=m("id","owned","kind","java","className","owned.Fixture","methodName","value","argumentTypes",l("int"));
    static HookEvidence evidence(){return new HookEvidence(m("id","observed-1","source","fixture","target",TARGET,"processInstanceId","123:process-1","abi","arm64-v8a","capabilities",l("java-hook","native-hook"),"symbols",l(SYMBOL)));}
    static final class Platform implements SelfHookControl.Platform {
        final Map<String,Object> identity=new LinkedHashMap<>(IDENTITY);final Backend backend=new Backend();boolean live=true;int prepares,observations,contextCalls;String phase="BEFORE_APPLICATION";DraftCompiler.Plan prepared;
        public Map<String,Object> identity(){return identity;}
        public List<Map<String,Object>> modules(){return List.of();}
        public List<Map<String,Object>> moduleEvents(){return List.of();}
        public Set<String> capabilities(){return Set.of("java-hook");}
        public SelfHookControl.Observation observe(List<Map<String,Object>> selectors){observations++;return new SelfHookControl.Observation(List.of(SYMBOL),List.of());}
        public boolean evidenceLive(HookEvidence e){return live;}
        public HookInvocationSession.PreparedBackend prepare(DraftCompiler.Plan p){prepares++;prepared=p;return backend;}
        public void discardObservation(){}
        public Map<String,Object> invocationContext(){contextCalls++;return m("runtimePhase",phase);}
    }
    static Map<String,Object> command(SelfHookControl control,String name,Object... fields){Map<String,Object> request=m("command",name,"processGeneration","process-1");for(int i=0;i<fields.length;i+=2)request.put((String)fields[i],fields[i+1]);return HookWire.request(control.handle(HookWire.encode(request)));}
    static Map<String,Object> result(Map<String,Object> response){check(Boolean.TRUE.equals(response.get("ok")),"command success "+response);return HookEvidence.object(response.get("result"));}
    static void failed(Map<String,Object> response,String code){check(Boolean.FALSE.equals(response.get("ok"))&&String.valueOf(response.get("error")).contains(code),"command rejection "+code+" got "+response);}
    static String observe(SelfHookControl control){return (String)HookEvidence.object(result(command(control,"observe","selectors",l(SELECTOR))).get("evidence")).get("id");}
    static SelfHookControl control(Platform p,AtomicLong clock){AtomicInteger tokens=new AtomicInteger();return new SelfHookControl(IDENTITY,p,clock::get,()->String.format("%064x",tokens.incrementAndGet()));}
    public static void run()throws Throwable {
        compilation();protocol();firstInvocationRetention();expiryAndFailures();wire();
    }
    static void compilation()throws Throwable {
        String captureSource="import {definePlan} from '@abox/hook';export default definePlan({id:'code',target,params:{},setup(ctx){ctx.java.method('owned.Fixture','value',['int']).replace(({args})=>{const capture=ctx.code.snapshotDex('dex-1');return args[0];});}});";
        AtomicInteger captures=new AtomicInteger();DraftCompiler.Plan capturePlan=DraftCompiler.compile(captureSource,evidence(),dex->{check(dex.equals("dex-1"),"code snapshot exact reference");captures.incrementAndGet();return m("artifactId","artifact-1","state","QUEUED");});
        check(captures.get()==0,"code snapshot not executed while compiling");check(capturePlan.rules().get(0).execute(Map.of(),l(7d),args->0d).equals(7d)&&captures.get()==1,"compiled callback delegates one code snapshot");
        rejects(()->DraftCompiler.compile(captureSource.replace("setup(ctx){","setup(ctx){ctx.code.snapshotDex('dex-1');"),evidence()),"CODE_CAPTURE_OUTSIDE_INVOCATION");
        DraftCompiler.Plan plan=DraftCompiler.compile(SOURCE,evidence());check(plan.rules().size()==1&&plan.parameters().get("delta").equals(2d),"source plan params");
        check(plan.rules().get(0).execute(plan.parameters(),l(3d),args->(Double)args.get(0)*2).equals(10d),"compiled before original after");
        check(plan.sourceSha256().equals(DraftCompiler.digest(SOURCE)),"hash actual source");
        rejects(()->DraftCompiler.compile(SOURCE.replace("['int']","['long']"),evidence()),"TARGET_NOT_FOUND");
        rejects(()->DraftCompiler.compile(SOURCE.replace("id:'owned-plan'","id:'invalid plan'"),evidence()),"PLAN_ID");
        rejects(()->DraftCompiler.compile(SOURCE.replace("maximum:10","maximum:1"),evidence()),"PARAM_RANGE");
        rejects(()->DraftCompiler.compile(SOURCE.replace("target,params","target:{packageName:'other',versionCode:'1',apkSha256:'a'},params"),evidence()),"TARGET_MISMATCH");
        rejects(()->DraftCompiler.compile(SOURCE.replace("'@abox/hook'","'other'"),evidence()),"DRAFT_IMPORT_SOURCE");
        rejects(()->DraftCompiler.compile(SOURCE.replace("definePlan, param","definePlan, missing"),evidence()),"DRAFT_IMPORT_NAME");
        rejects(()->DraftCompiler.compile("return 1;"+SOURCE,evidence()),"DRAFT_TOP_LEVEL_RETURN");
        rejects(()->DraftCompiler.compile(SOURCE.replace("args[0]+1","args[0] ?? 1 || 2"),evidence()),"DRAFT_NULLISH_MIX");
        rejects(()->DraftCompiler.compile(SOURCE.replace("({args})=>","({args})\n=>"),evidence()),"DRAFT_ARROW_NEWLINE");
        rejects(()->DraftCompiler.compile(SOURCE.replace("args[0]+1","new Object()"),evidence()),"DRAFT_UNSUPPORTED_EXPRESSION");
        rejects(()->DraftCompiler.compile(SOURCE.replace("['int']).before","['int']).replace(()=>3).before"),evidence()),"HOOK_CONFLICT");
        String typed="import {definePlan as plan} from '@abox/hook'; type NumberAlias=number; interface Owned { value:number; } export default plan({id:'typed',target,params:{},setup:(ctx)=>ctx.java.method('owned.Fixture','value',['int']).replace((call):number=>{const x:number=0xFE+1;return x+(call.args[0] as number);})});";
        check(DraftCompiler.compile(typed,evidence()).rules().get(0).execute(Map.of(),l(1d),a->0d).equals(256d),"parsed types alias and hex token boundary");
        String template=SOURCE.replace("[args[0]+1]","[Number(`${args[0]}`)]");
        check(DraftCompiler.compile(template,evidence()).rules().get(0).execute(Map.of("delta",2d),l(3d),a->a.get(0)).equals(5d),"template source interpolation");
        HookEvidence ambiguous=new HookEvidence(m("id","ambiguous","source","fixture","target",TARGET,"processInstanceId","123:process-1","abi","arm64-v8a","capabilities",l("java-hook"),"symbols",l(SYMBOL,new LinkedHashMap<>(SYMBOL){{put("id","other");put("classLoaderId","other-loader");}})));
        rejects(()->DraftCompiler.compile(SOURCE,ambiguous),"TARGET_AMBIGUOUS");
        check(DraftCompiler.compile(SOURCE.replace("['int'])","['int'],{classLoaderId:'owned-loader'})"),ambiguous).rules().size()==1,"explicit loader selection");
        String nativeSource="import {definePlan,param} from '@abox/hook'; export default definePlan({id:'native',target,params:{n:param.integer(2)},setup(ctx){ctx.native.function('libowned.so','owned_value',{argumentTypes:['int64'],returnType:'int64',buildId:'aabb'}).replace(({args,params})=>args[0]+BigInt(params.n));}});";
        Map<String,Object> nativeSymbol=m("id","ownedNative","kind","native","loaded",true,"moduleName","libowned.so","symbol","owned_value","buildId","aabb","argumentTypes",l("int64"),"returnType","int64","signatureSource","confirmed");
        Map<String,Object> nativeWire=m("id","native-evidence","source","fixture","target",TARGET,"processInstanceId","123:process-1","abi","arm64-v8a","capabilities",l("native-hook"),"symbols",l(nativeSymbol));HookEvidence nativeEvidence=new HookEvidence(nativeWire);
        DraftCompiler.Plan nativePlan=DraftCompiler.compile(nativeSource,nativeEvidence);
        check(nativePlan.rules().get(0).execute(nativePlan.parameters(),l(new java.math.BigInteger("9007199254740993")),a->{throw new AssertionError("native replace original");}).equals(new java.math.BigInteger("9007199254740995")),"compiled native exact int64");
        rejects(()->DraftCompiler.compile(nativeSource.replace("'aabb'","'ccdd'"),nativeEvidence),"BUILD_ID_MISMATCH");
        rejects(()->DraftCompiler.compile(nativeSource.replace("['int64']","['int32']"),nativeEvidence),"SIGNATURE_MISMATCH");
        nativeSymbol.put("signatureSource","unknown");rejects(()->DraftCompiler.compile(nativeSource,new HookEvidence(nativeWire)),"SIGNATURE_UNKNOWN");
        String closed=SOURCE.replace(".before(({args})=>[args[0]+1]).after(({result,params})=>result+params.delta)",".replace(()=>{ctx.java.method('owned.Fixture','value',['int']).replace(()=>2);return 1;})");
        DraftCompiler.Plan closedPlan=DraftCompiler.compile(closed,evidence());rejects(()->closedPlan.rules().get(0).execute(closedPlan.parameters(),l(1d),a->0d),"SETUP_CLOSED");
        rejects(()->DraftCompiler.compile(SOURCE.replace("setup(ctx){","setup(ctx){ctx.debug.emitEvent('setup.tag',1);"),evidence()),"HOOK_EMIT_OUTSIDE_INVOCATION");
        String emitSource="import {definePlan} from '@abox/hook'; export default definePlan({id:'emit',target,params:{},setup(ctx){ctx.java.method('owned.Fixture','value',['int']).after(({args,result})=>{ctx.debug.emitEvent('trace.value',{arg:args[0],out:result});return result;});}});";
        DraftCompiler.Plan emitPlan=DraftCompiler.compile(emitSource,evidence());
        check(emitPlan.rules().get(0).execute(Map.of(),l(5d),a->10d).equals(10d),"emitEvent outside session is safe no-op");
        Map<String,Object> bytesSymbol=m("id","ownedBytes","kind","java","loaded",true,"className","owned.Crypto","methodName","hmac","classLoaderId","owned-loader","argumentTypes",l("[B"),"returnType","[B");
        HookEvidence bytesEvidence=new HookEvidence(m("id","bytes-ev","source","fixture","target",TARGET,"processInstanceId","123:process-1","abi","arm64-v8a","capabilities",l("java-hook"),"symbols",l(bytesSymbol)));
        String bytesSource="import {definePlan} from '@abox/hook'; export default definePlan({id:'bytes-plan',target,params:{},setup(ctx){ctx.java.method('owned.Crypto','hmac',['byte[]'],{returnType:'byte[]'}).before(({args})=>[args[0]]).after(({result})=>result);}});";
        DraftCompiler.Plan bytesPlan=DraftCompiler.compile(bytesSource,bytesEvidence);
        check(bytesPlan.rules().get(0).execute(Map.of(),l("deadbeef"),a->"cafebabe").equals("cafebabe"),"byte[] normalized Java hook execution");
        rejects(()->bytesPlan.rules().get(0).execute(Map.of(),l("bad_hex"),a->"00"),"HOOK_TYPE");
    }
    static void protocol()throws Throwable {
        Platform platform=new Platform();SelfHookControl control=control(platform,new AtomicLong(100));String id=observe(control);
        String sourceWithEvents="import { definePlan, param } from '@abox/hook'; export default definePlan({id:'owned-plan',target,params:{delta:param.integer(2,{minimum:0,maximum:10})},setup(ctx){ctx.java.method('owned.Fixture','value',['int']).before(({args})=>[args[0]+1]).after(({args,result,params})=>{ctx.debug.emitEvent('value.after',{arg:args[0],result,delta:params.delta});return result+params.delta;});}});";
        Map<String,Object> prepared=result(command(control,"prepare","evidenceId",id,"source",sourceWithEvents));
        check(platform.prepares==0&&platform.backend.published==0,"prepare source does not mutate backend");
        failed(command(control,"activate","token",prepared.get("token"),"sourceSha256","c".repeat(64)),"HOOK_PREPARATION_STALE");
        Map<String,Object> active=result(command(control,"activate","token",prepared.get("token"),"sourceSha256",prepared.get("sourceSha256")));
        check(active.get("state").equals("ACTIVE")&&platform.backend.published==1&&platform.prepared.sourceSha256().equals(prepared.get("sourceSha256")),"activate compiled exact source");
        check(platform.backend.call(l(3d),a->(Double)a.get(0)*2).equals(10d),"protocol installed compiled handlers");
        Map<String,Object> events1=result(command(control,"events","sinceSeq",0d));
        check(HookEvidence.array(events1.get("events")).size()==1&&events1.get("nextCursor").equals(1d)&&events1.get("droppedEvents").equals(0d),"protocol events cursor 1");
        failed(command(control,"activate","token",prepared.get("token"),"sourceSha256",prepared.get("sourceSha256")),"HOOK_PREPARATION_STALE");check(platform.backend.published==1,"duplicate activate never replays");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Object> inFlight=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        Thread worker=new Thread(()->{try{inFlight.set(platform.backend.call(l(3d),a->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("control original deadline");return (Double)a.get(0)*2;}));}catch(Throwable failure){error.set(failure);}});worker.start();check(entered.await(5,TimeUnit.SECONDS),"protocol original in flight");
        String revision=(String)active.get("revision");result(command(control,"updateParameters","revision",revision,"parameterVersion",1d,"parameters",m("delta",5d)));
        check(platform.backend.call(l(3d),a->(Double)a.get(0)*2).equals(13d),"protocol parameter version applied");
        Map<String,Object> events2=result(command(control,"events","sinceSeq",1d));
        check(HookEvidence.array(events2.get("events")).size()==1&&events2.get("nextCursor").equals(2d),"protocol events incremental sinceSeq");
        failed(command(control,"updateParameters","revision",revision,"parameterVersion",1d,"parameters",m("delta",1d)),"HOOK_PARAMETER_VERSION_STALE");
        check(result(command(control,"disable","revision",revision)).get("state").equals("DRAINING")&&platform.backend.retired==0,"protocol draining not disabled early");
        check(platform.backend.call(l(3d),a->a.get(0)).equals(3d),"protocol draining new callback original only");
        release.countDown();worker.join(5000);check(!worker.isAlive()&&error.get()==null&&inFlight.get().equals(10d),"protocol old parameter snapshot retained");
        check(result(command(control,"status")).get("state").equals("DISABLED")&&platform.backend.retired==1,"protocol disable ack after retire");
        check(result(command(control,"status")).get("state").equals("IDLE"),"disabled next status idle");
        check(result(command(control,"manifestInspect")).get("activities") instanceof List,"manifestInspect wire command");
        check("all".equals(result(command(control,"storageInspect","category","all","limit",20d)).get("category")),"storageInspect wire command");
        check(result(command(control,"networkTraffic","sinceSeq",0d,"limit",20d)).get("exchanges") instanceof List,"networkTraffic wire command");
        failed(command(control,"prepare","evidenceId",id,"source",SOURCE),"HOOK_EVIDENCE_STALE");
        failed(command(control,"status","ast",m()),"HOOK_COMMAND_FIELDS");
        platform.identity.put("installGeneration","changed");failed(command(control,"status"),"HOOK_PROCESS_STALE");
    }
    static void expiryAndFailures()throws Throwable {
        AtomicLong clock=new AtomicLong(1);Platform platform=new Platform();SelfHookControl control=control(platform,clock);String id=observe(control);
        Map<String,Object> prep=result(command(control,"prepare","evidenceId",id,"source",SOURCE));clock.set(60002);
        failed(command(control,"activate","token",prep.get("token"),"sourceSha256",prep.get("sourceSha256")),"HOOK_PREPARATION_STALE");check(platform.backend.published==0,"expired no mutation");
        prep=result(command(control,"prepare","evidenceId",id,"source",SOURCE));control.invalidateObservation();
        failed(command(control,"activate","token",prep.get("token"),"sourceSha256",prep.get("sourceSha256")),"HOOK_PREPARATION_STALE");
        id=observe(control);prep=result(command(control,"prepare","evidenceId",id,"source",SOURCE));platform.live=false;
        failed(command(control,"activate","token",prep.get("token"),"sourceSha256",prep.get("sourceSha256")),"HOOK_EVIDENCE_STALE");check(platform.prepares==0,"stale backend never prepared");
        Platform unloaded=new Platform();SelfHookControl status=control(unloaded,new AtomicLong(0));String selected=observe(status);Map<String,Object> pending=result(command(status,"prepare","evidenceId",selected,"source",SOURCE));unloaded.live=false;
        check(result(command(status,"status")).get("state").equals("IDLE")&&unloaded.backend.published==0,"status invalidates unloaded prepared target without publication");
        failed(command(status,"activate","token",pending.get("token"),"sourceSha256",pending.get("sourceSha256")),"HOOK_PREPARATION_STALE");
        Platform uncertain=new Platform();uncertain.backend.failPublish=true;SelfHookControl bad=control(uncertain,new AtomicLong(0));String evidenceId=observe(bad);Map<String,Object> candidate=result(command(bad,"prepare","evidenceId",evidenceId,"source",SOURCE));
        try{command(bad,"activate","token",candidate.get("token"),"sourceSha256",candidate.get("sourceSha256"));throw new AssertionError("uncertain acknowledged");}catch(HookBackendUncertain expected){check(uncertain.backend.fatal==1,"uncertain source command propagates no ACK");}
        failed(command(bad,"status"),"HOOK_PROCESS_INVALIDATED");check(uncertain.backend.published==1,"unknown status no replay");
        Platform lost=new Platform();SelfHookControl active=control(lost,new AtomicLong(0));String lostEvidence=observe(active);Map<String,Object> owned=result(command(active,"prepare","evidenceId",lostEvidence,"source",SOURCE));result(command(active,"activate","token",owned.get("token"),"sourceSha256",owned.get("sourceSha256")));
        try{active.invalidateActiveTarget(new IllegalStateException("OWNED_MODULE_UNLOADED"));throw new AssertionError("active module loss ignored");}catch(HookBackendUncertain expected){check(lost.backend.fatal==1,"active target identity loss failstop");}
    }
    static void firstInvocationRetention()throws Throwable{
        Platform platform=new Platform();SelfHookControl control=control(platform,new AtomicLong(1));String observed=observe(control);
        String source="import {definePlan} from '@abox/hook';export default definePlan({id:'first',target,params:{},setup(ctx){ctx.java.method('owned.Fixture','value',['int']).after(({result})=>{ctx.debug.emitEvent('hit',result);return result;});}});";
        Map<String,Object> prepared=result(command(control,"prepare","evidenceId",observed,"source",source));result(command(control,"activate","token",prepared.get("token"),"sourceSha256",prepared.get("sourceSha256")));
        check(platform.contextCalls==0,"first invocation context is lazy");platform.backend.call(l(1d),args->1d);platform.phase="APPLICATION_CREATED";
        for(int i=0;i<300;i++)platform.backend.call(l(1d),args->1d);
        Map<String,Object> status=result(command(control,"status")),first=HookEvidence.object(status.get("firstInvocation"));
        check(first.get("runtimePhase").equals("BEFORE_APPLICATION")&&first.get("symbol").equals("owned"),"first phase survives later callbacks");
        check(platform.contextCalls==1&&((Double)status.get("hits")).intValue()==301,"no repeated context sampling on hot callbacks");
        check(((Double)result(command(control,"events","sinceSeq",0d)).get("droppedEvents"))>0,"first invocation test overflows telemetry ring");
        Map<String,Object> disabled=result(command(control,"disable","revision",status.get("revision")));check(HookEvidence.object(disabled.get("firstInvocation")).equals(first),"disable receipt retains first invocation");
    }
    static void wire()throws Throwable {
        rejects(()->HookWire.request("{\"command\":\"status\",\"command\":\"activate\"}"),"HOOK_JSON_DUPLICATE_KEY");
        rejects(()->HookWire.request("{\"x\":900e900}"),"HOOK_JSON_NUMBER");
        rejects(()->HookWire.encode(Map.of("x",new java.math.BigInteger("9223372036854775807"))),"HOOK_JSON_VALUE");
        Map<String,Object> values=m("source",SOURCE,"unicode","😀\n\ud800","bool",true,"null",null,"number",-0d);
        check(HookWire.request(HookWire.encode(values)).equals(new LinkedHashMap<>(values){{put("number",0d);}}),"wire source and unicode roundtrip");
    }
}
