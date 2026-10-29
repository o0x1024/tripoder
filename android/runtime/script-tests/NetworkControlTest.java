package dev.tripoder.runtime.script;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.tripoder.runtime.script.ScriptContractTest.*;
import static dev.tripoder.runtime.script.SourceControlTest.command;
import static dev.tripoder.runtime.script.SourceControlTest.result;

final class NetworkControlTest {
    static final class Platform implements SelfHookControl.Platform,HookInvocationSession.PreparedBackend {
        HookInvocationSession.Dispatch dispatch;DraftCompiler.Plan plan;int published,retired,saved;
        public Map<String,Object> identity(){return SourceControlTest.IDENTITY;}
        public Set<String> capabilities(){return Set.of("native-hook");}
        public List<Map<String,Object>> modules(){return List.of();}public List<Map<String,Object>> moduleEvents(){return List.of();}
        public List<Map<String,Object>> searchNativeExports(String module,String prefix,int limit){return List.of(m("symbol",prefix,"buildId","aabb"));}
        public SelfHookControl.Observation observe(List<Map<String,Object>> selectors){List<Map<String,Object>> symbols=new ArrayList<>();for(var selector:selectors){Map<String,Object> row=new LinkedHashMap<>(selector);row.put("loaded",true);symbols.add(row);}return new SelfHookControl.Observation(symbols,List.of());}
        public boolean evidenceLive(HookEvidence e){return true;}public void discardObservation(){}
        public HookInvocationSession.PreparedBackend prepare(DraftCompiler.Plan plan){this.plan=plan;return this;}
        public void publish(HookInvocationSession.Dispatch dispatch){this.dispatch=dispatch;published++;}public boolean retire(){retired++;return true;}
        public void failStop(Throwable error){throw new AssertionError(error);}public boolean nativeSymbol(String symbol){return true;}
        public Map<String,Object> persistNetworkCapture(String tag,Object payload,String sha,String revision){check(sha.equals(plan.sourceSha256())&&!revision.isEmpty(),"network artifact bound to managed plan");saved++;return m("state","COMPLETE","artifactId","capture");}
    }
    static void run()throws Throwable{
        Platform p=new Platform();AtomicInteger tokens=new AtomicInteger();var c=new SelfHookControl(SourceControlTest.IDENTITY,p,()->100,()->String.format("%064x",tokens.incrementAndGet()));
        check(result(command(c,"networkInspect","moduleName","libowned.so")).get("candidate").equals(true),"profile candidate needs separate ABI confirmation");
        Map<String,Object> start=result(command(c,"networkStart","moduleName","libowned.so","buildId","aabb","profile","openssl-ssl-read-write-v1","signatureSource","confirmed"));String id=(String)start.get("captureId");
        check(start.get("state").equals("ACTIVE")&&p.published==1,"managed session publishes existing backend once");
        check(command(c,"discard").get("ok").equals(true)&&result(command(c,"discard")).get("state").equals("IDLE"),"user hooks operate independently without mutating managed capture");
        check(result(command(c,"networkStatus")).get("state").equals("ACTIVE"),"managed capture remains active through user hook operations");
        List<Map<String,Object>> selectors128=new ArrayList<>();
        for(int i=0;i<128;i++)selectors128.add(m("id","s"+i,"kind","native","moduleName","libowned.so","symbol","sym"+i,"argumentTypes",l("int32"),"returnType","int32","buildId","aabb","signatureSource","confirmed"));
        check(command(c,"observe","selectors",selectors128).get("ok").equals(true),"128 selectors accepted");
        selectors128.add(m("id","s128","kind","native","moduleName","libowned.so","symbol","sym128","argumentTypes",l("int32"),"returnType","int32","buildId","aabb","signatureSource","confirmed"));
        check(command(c,"observe","selectors",selectors128).get("outcome").equals("REJECTED"),"129 selectors rejected by selector budget");
        command(c,"discard");
        p.dispatch.call("open",l(BigInteger.ONE),a->BigInteger.TEN);
        for(String direction:List.of("write","read")){
            byte[] bytes=(direction.equals("write")?NetworkCaptureTest.REQUEST:NetworkCaptureTest.RESPONSE).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            try(var scope=new NativeBufferScope(direction,List.of("uint64","uint64","int32"),"int32",(argument,length)->bytes)){
                Object original=p.dispatch.call(direction,l(BigInteger.TEN,BigInteger.valueOf(4096),(double)bytes.length),args->{scope.complete(args,(double)bytes.length);return (double)bytes.length;});check(original.equals((double)bytes.length),"managed capture preserves native result");
            }
        }
        p.dispatch.call("close",l(BigInteger.TEN),a->ScriptValues.UNDEFINED);
        var query=result(command(c,"networkTraffic","captureId",id,"sinceSeq",0d,"limit",16d));
        check(((List<?>)query.get("connections")).size()==1&&p.saved==2,"uniform query returns real captured connection with durable receipt");
        check(((List<?>)result(command(c,"networkMessages","captureId",id,"sinceSeq",0d,"limit",16d)).get("exchanges")).size()==1,"managed HTTP message query");
        check(command(c,"networkTraffic","captureId","wrong","sinceSeq",0d,"limit",16d).get("outcome").equals("REJECTED"),"capture identity never inferred");
        result(command(c,"networkStop","captureId",id));check(p.retired==1,"managed stop retires backend once");
        result(command(c,"networkStop","captureId",id));check(p.retired==1,"known stopped session is not retired twice");
        check(result(command(c,"networkStatus")).get("captureState").equals("STOPPED"),"stopped capture remains queryable");
    }
}
