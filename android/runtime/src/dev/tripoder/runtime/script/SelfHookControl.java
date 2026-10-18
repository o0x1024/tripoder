package dev.tripoder.runtime.script;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static dev.tripoder.runtime.script.ScriptFault.require;
import static dev.tripoder.runtime.script.HookEvidence.*;

/** In-process implementation of the existing Worker command protocol; Authority still owns approvals/journal. */
public final class SelfHookControl implements HookCommandDispatcher.Handler {
    public record Observation(List<Map<String,Object>> symbols,List<Map<String,Object>> nativeModules) {}
    public interface Platform {
        Map<String,Object> identity();
        List<Map<String,Object>> modules();
        List<Map<String,Object>> moduleEvents();
        Set<String> capabilities();
        /** Resolve guest-owned classes without initialization and attest owned modules/generations. */
        Observation observe(List<Map<String,Object>> selectors);
        boolean evidenceLive(HookEvidence evidence);
        /** Pure binding/preflight. Publish must recheck live identity while establishing its ownership lease. */
        HookInvocationSession.PreparedBackend prepare(DraftCompiler.Plan plan);
        /** Clear observation references only; retired callbacks must retain their required roots. */
        void discardObservation();
        default List<String> searchDexClasses(String packagePrefix,int limit){return List.of();}
        default Map<String,Object> inspectDexClass(String className){return Map.of("className",className,"methods",List.of(),"fields",List.of());}
        default List<Map<String,Object>> searchNativeExports(String moduleName,String symbolPrefix,int limit){return List.of();}
        default Map<String,Object> dumpUiHierarchy(boolean includeInvisible){return Map.of("windows",List.of());}
        default Map<String,Object> dispatchUiAction(Map<String,Object> action){return Map.of("dispatched",false);}
        default List<Map<String,Object>> diagnostics(){return List.of();}
        default Map<String,Object> inspectManifest(){return Map.of("activities",List.of(),"services",List.of(),"receivers",List.of(),"providers",List.of());}
        default Map<String,Object> inspectStorage(String category,int limit){return Map.of("category",category);}
        default Map<String,Object> listNetworkTraffic(long sinceSeq,int limit){return Map.of("exchanges",List.of(),"nextCursor",(double)sinceSeq);}
        default Map<String,Object> sendHttpRequest(Map<String,Object> request){return Map.of("statusCode",0d);}
        default Map<String,Object> invokeGuestMethod(Map<String,Object> target){return Map.of("result",null);}
    }
    private record Prepared(String token,DraftCompiler.Plan plan,long deadline,long evidenceEpoch) {}
    private static final Map<String,Set<String>> FIELDS=Map.ofEntries(
        Map.entry("modules",Set.of()),Map.entry("observe",Set.of("selectors")),Map.entry("prepare",Set.of("evidenceId","source")),
        Map.entry("activate",Set.of("token","sourceSha256")),Map.entry("disable",Set.of("revision")),
        Map.entry("updateParameters",Set.of("revision","parameterVersion","parameters")),Map.entry("cancelPreparation",Set.of("token")),
        Map.entry("discard",Set.of()),Map.entry("status",Set.of()),Map.entry("events",Set.of("sinceSeq")),
        Map.entry("dexSearch",Set.of("packagePrefix","limit")),Map.entry("dexInspect",Set.of("className")),
        Map.entry("nativeExports",Set.of("moduleName","symbolPrefix","limit")),Map.entry("uiDump",Set.of("includeInvisible")),
        Map.entry("uiAction",Set.of("action")),Map.entry("diagnostics",Set.of()),
        Map.entry("manifestInspect",Set.of()),Map.entry("storageInspect",Set.of("category","limit")),
        Map.entry("networkTraffic",Set.of("sinceSeq","limit")),Map.entry("httpRequest",Set.of("request")),
        Map.entry("guestInvoke",Set.of("target")));
    private final Map<String,Object> identity;
    private final String generation;
    private final Platform platform;
    private final LongSupplier clock;
    private final Supplier<String> token;
    private final AtomicBoolean commanding=new AtomicBoolean();
    private final AtomicLong invalidation=new AtomicLong();
    private final List<HookInvocationSession> retired=new ArrayList<>();
    private HookEvidence evidence;
    private Prepared prepared;
    private volatile HookInvocationSession active;
    private DraftCompiler.Plan activePlan;
    private long observedEpoch,lastClock=-1;
    private int activations;
    @SuppressWarnings("unchecked") public SelfHookControl(Map<String,Object> identity,Platform platform,LongSupplier elapsedClock,Supplier<String> token){
        this.identity=(Map<String,Object>)ScriptValues.freeze(identity);this.platform=Objects.requireNonNull(platform);this.clock=Objects.requireNonNull(elapsedClock);this.token=Objects.requireNonNull(token);
        for(String key:List.of("space","installGeneration","descriptorSha256","processGeneration"))text(this.identity,key);
        Object pid=this.identity.get("pid");require(pid instanceof Double&&(Double)pid>0&&(Double)pid==Math.rint((Double)pid),"HOOK_PROCESS_ID");
        object(this.identity.get("target"));generation=text(this.identity,"processGeneration");
    }
    public SelfHookControl(Map<String,Object> identity,Platform platform,LongSupplier elapsedClock){this(identity,platform,elapsedClock,SelfHookControl::randomToken);}
    /** Lifecycle observer may invalidate preparations without waiting on a command/backend publication lock. */
    public void invalidateObservation(){invalidation.incrementAndGet();}
    /** A module/loader observer calls this only when an active target's retained ownership is lost. */
    public void invalidateActiveTarget(Throwable cause){invalidation.incrementAndGet();HookInvocationSession session=active;if(session!=null)session.invalidateTarget(cause);}
    @Override public String handle(String raw){
        require(commanding.compareAndSet(false,true),"HOOK_CONTROL_BUSY");
        try {return HookWire.encode(Map.of("ok",true,"result",command(HookWire.request(raw))));}
        catch(Exception error){String message=String.valueOf(error);return HookWire.encode(Map.of("ok",false,"error",message.substring(0,Math.min(1000,message.length()))));}
        finally {commanding.set(false);}
    }
    private Map<String,Object> command(Map<String,Object> input){
        String name=text(input,"command");Set<String> fields=FIELDS.get(name);require(fields!=null,"HOOK_COMMAND_UNSUPPORTED");Set<String> expected=new HashSet<>(fields);expected.add("command");expected.add("processGeneration");require(input.keySet().equals(expected),"HOOK_COMMAND_FIELDS");
        require(generation.equals(input.get("processGeneration"))&&identity.equals(platform.identity()),"HOOK_PROCESS_STALE");
        long now=clock.getAsLong();require(now>=lastClock&&now>=0&&now<=Long.MAX_VALUE-60000,"HOOK_CLOCK_INVALID");lastClock=now;
        if(prepared!=null&&(now>prepared.deadline||prepared.evidenceEpoch!=invalidation.get()))prepared=null;
        if(evidence!=null&&observedEpoch!=invalidation.get())evidence=null;
        switch(name){
            case "modules":return map("modules",platform.modules(),"identity",identity);
            case "observe":{
                require(active==null&&prepared==null,"HOOK_CONTEXT_IN_USE");evidence=null;platform.discardObservation();
                List<Map<String,Object>> selectors=selectors(input.get("selectors"));long epoch=invalidation.get();Observation observed=platform.observe(selectors);
                require(epoch==invalidation.get(),"HOOK_EVIDENCE_STALE");String id=newToken();
                Map<String,Object> wire=map("id",id,"source","guest-agent","target",identity.get("target"),"processInstanceId",ScriptValues.text(identity.get("pid"))+":"+generation,"abi","arm64-v8a","capabilities",new ArrayList<>(platform.capabilities()),"symbols",observed.symbols());
                evidence=new HookEvidence(wire);observedEpoch=epoch;require(platform.evidenceLive(evidence),"HOOK_EVIDENCE_STALE");
                return map("evidence",evidence.wire,"identity",identity,"nativeModules",observed.nativeModules());
            }
            case "prepare":{
                require(active==null&&prepared==null,"HOOK_CONTEXT_IN_USE");require(evidence!=null&&evidence.id.equals(input.get("evidenceId"))&&platform.evidenceLive(evidence),"HOOK_EVIDENCE_STALE");
                require(activations<32,"HOOK_ACTIVATION_BUDGET");String source=text(input,"source");long epoch=invalidation.get();DraftCompiler.Plan plan=DraftCompiler.compile(source,evidence);
                require(epoch==invalidation.get()&&platform.evidenceLive(evidence),"HOOK_EVIDENCE_STALE");prepared=new Prepared(newToken(),plan,now+60000,epoch);
                return map("state","PREPARED","token",prepared.token,"sourceSha256",plan.sourceSha256(),"evidenceId",evidence.id,"rules",plan.rules().size(),"identity",identity);
            }
            case "activate":{
                require(active==null&&prepared!=null&&prepared.token.equals(input.get("token"))&&prepared.plan.sourceSha256().equals(input.get("sourceSha256")),"HOOK_PREPARATION_STALE");
                Prepared candidate=prepared;prepared=null;require(platform.evidenceLive(candidate.plan.evidence())&&candidate.evidenceEpoch==invalidation.get(),"HOOK_EVIDENCE_STALE");
                HookInvocationSession.PreparedBackend backend=platform.prepare(candidate.plan);
                require(candidate.evidenceEpoch==invalidation.get()&&platform.evidenceLive(candidate.plan.evidence()),"HOOK_EVIDENCE_STALE");
                activePlan=candidate.plan;active=new HookInvocationSession(generation,newToken(),candidate.plan.rules(),candidate.plan.specs(),candidate.plan.parameters(),backend);activations++;
                try{active.activate(generation,active.status().revision());}
                catch(IllegalStateException failure){
                    if(failure.getCause() instanceof HookPublicationRejected){
                        // All preparations were cancelled before publication.
                        // No callback can hold this session; a new observation
                        // and token are required for any subsequent attempt.
                        active=null;activePlan=null;evidence=null;platform.discardObservation();
                    }
                    throw failure;
                }
                return withIdentity(status());
            }
            case "disable":{
                require(active!=null,"HOOK_REVISION_STALE");active.disable(generation,text(input,"revision"));return withIdentity(status());
            }
            case "updateParameters":{
                require(active!=null,"HOOK_PARAMETER_VERSION_STALE");Object version=input.get("parameterVersion");require(version instanceof Double&&(Double)version==Math.rint((Double)version)&&(Double)version>=1&&(Double)version<=10000,"HOOK_PARAMETER_VERSION_STALE");
                active.update(generation,text(input,"revision"),((Double)version).intValue(),object(input.get("parameters")));return withIdentity(status());
            }
            case "cancelPreparation":require(prepared!=null&&prepared.token.equals(input.get("token")),"HOOK_PREPARATION_STALE");prepared=null;return map("state","CANCELED");
            case "discard":require(active==null,"HOOK_CONTEXT_IN_USE");prepared=null;evidence=null;platform.discardObservation();return map("state","IDLE");
            case "status":{Map<String,Object> result=withIdentity(status());result.put("moduleEvents",platform.moduleEvents());return result;}
            case "events":{
                Object rawSince=input.get("sinceSeq");require(rawSince instanceof Double&&(Double)rawSince==Math.rint((Double)rawSince)&&(Double)rawSince>=0,"HOOK_EVENT_SEQ");
                long sinceSeq=((Double)rawSince).longValue();
                HookInvocationSession session=active;
                if(session==null&&!retired.isEmpty())session=retired.get(retired.size()-1);
                if(session==null)return withIdentity(map("events",List.of(),"droppedEvents",0d,"nextCursor",0d));
                HookInvocationSession.EventSnapshot snap=session.events(sinceSeq);
                return withIdentity(map("events",snap.events(),"droppedEvents",(double)snap.droppedEvents(),"nextCursor",(double)snap.nextCursor()));
            }
            case "dexSearch":{
                Object rawPrefix=input.get("packagePrefix");require(rawPrefix instanceof String&&((String)rawPrefix).length()<=256,"HOOK_DEX_PREFIX");
                Object rawLimit=input.get("limit");require(rawLimit instanceof Double&&(Double)rawLimit==Math.rint((Double)rawLimit)&&(Double)rawLimit>=1&&(Double)rawLimit<=200,"HOOK_DEX_LIMIT");
                return withIdentity(map("classes",platform.searchDexClasses((String)rawPrefix,((Double)rawLimit).intValue())));
            }
            case "dexInspect":{
                String cls=text(input,"className");require(cls.length()<=512,"HOOK_CLASS_NAME");
                return withIdentity(new LinkedHashMap<>(platform.inspectDexClass(cls)));
            }
            case "nativeExports":{
                String mod=text(input,"moduleName");require(mod.matches("lib[a-zA-Z0-9_+.$-]+\\.so"),"HOOK_NATIVE_MODULE");
                Object rawPrefix=input.get("symbolPrefix");require(rawPrefix instanceof String&&((String)rawPrefix).length()<=256,"HOOK_NATIVE_PREFIX");
                Object rawLimit=input.get("limit");require(rawLimit instanceof Double&&(Double)rawLimit==Math.rint((Double)rawLimit)&&(Double)rawLimit>=1&&(Double)rawLimit<=200,"HOOK_NATIVE_LIMIT");
                return withIdentity(map("exports",platform.searchNativeExports(mod,(String)rawPrefix,((Double)rawLimit).intValue())));
            }
            case "uiDump":{
                Object inc=input.get("includeInvisible");require(inc instanceof Boolean,"HOOK_UI_DUMP_ARG");
                return withIdentity(new LinkedHashMap<>(platform.dumpUiHierarchy((Boolean)inc)));
            }
            case "uiAction":{
                return withIdentity(new LinkedHashMap<>(platform.dispatchUiAction(object(input.get("action")))));
            }
            case "diagnostics":{
                return withIdentity(map("failures",platform.diagnostics()));
            }
            case "manifestInspect":{
                return withIdentity(new LinkedHashMap<>(platform.inspectManifest()));
            }
            case "storageInspect":{
                String cat=text(input,"category");require(Set.of("all","shared_prefs","databases","files").contains(cat),"HOOK_STORAGE_CATEGORY");
                Object rawLimit=input.get("limit");require(rawLimit instanceof Double&&(Double)rawLimit==Math.rint((Double)rawLimit)&&(Double)rawLimit>=1&&(Double)rawLimit<=100,"HOOK_STORAGE_LIMIT");
                return withIdentity(new LinkedHashMap<>(platform.inspectStorage(cat,((Double)rawLimit).intValue())));
            }
            case "networkTraffic":{
                Object rawSince=input.get("sinceSeq");require(rawSince instanceof Double&&(Double)rawSince==Math.rint((Double)rawSince)&&(Double)rawSince>=0,"HOOK_TRAFFIC_SEQ");
                Object rawLimit=input.get("limit");require(rawLimit instanceof Double&&(Double)rawLimit==Math.rint((Double)rawLimit)&&(Double)rawLimit>=1&&(Double)rawLimit<=100,"HOOK_TRAFFIC_LIMIT");
                return withIdentity(new LinkedHashMap<>(platform.listNetworkTraffic(((Double)rawSince).longValue(),((Double)rawLimit).intValue())));
            }
            case "httpRequest":{
                return withIdentity(new LinkedHashMap<>(platform.sendHttpRequest(object(input.get("request")))));
            }
            case "guestInvoke":{
                return withIdentity(new LinkedHashMap<>(platform.invokeGuestMethod(object(input.get("target")))));
            }
            default:throw new ScriptFault("HOOK_COMMAND_UNSUPPORTED");
        }
    }
    private Map<String,Object> status(){
        if(active==null&&prepared!=null&&!platform.evidenceLive(prepared.plan.evidence())){
            prepared=null;evidence=null;platform.discardObservation();
        }
        if(active!=null){HookInvocationSession.Snapshot snapshot=active.status();
            if(snapshot.state()==HookInvocationSession.State.DISABLED){Map<String,Object> result=map("state","DISABLED","revision",snapshot.revision(),"hits",snapshot.hits(),"errors",snapshot.errors());retired.add(active);active=null;activePlan=null;evidence=null;platform.discardObservation();return result;}
            require(snapshot.state()!=HookInvocationSession.State.FAILED,"HOOK_PROCESS_INVALIDATED");
            return map("state",snapshot.state().name(),"revision",snapshot.revision(),"sourceSha256",activePlan.sourceSha256(),"parameterVersion",snapshot.parameterVersion(),"parameters",snapshot.parameters(),"hits",snapshot.hits(),"errors",snapshot.errors(),"inFlight",snapshot.inFlight());
        }
        if(prepared!=null)return map("state","PREPARED","sourceSha256",prepared.plan.sourceSha256(),"deadlineElapsedMs",prepared.deadline);
        if(evidence!=null)return map("state","OBSERVED","evidenceId",evidence.id);return map("state","IDLE");
    }
    private static List<Map<String,Object>> selectors(Object value){
        List<?> array=array(value);require(!array.isEmpty()&&array.size()<=32,"HOOK_SELECTOR_COUNT_OR_KIND");List<Map<String,Object>> selected=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(Object entry:array){Map<String,Object> selector=object(entry);String kind=text(selector,"kind"),id=text(selector,"id");require(id.matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}")&&ids.add(id),"HOOK_SYMBOL_ID");
            Set<String> keys=kind.equals("java")?Set.of("id","kind","className","methodName","argumentTypes"):Set.of("id","kind","moduleName","symbol","argumentTypes","returnType","buildId","signatureSource");
            require(Set.of("java","native").contains(kind)&&selector.keySet().equals(keys),"HOOK_SELECTOR_FIELDS");require(strings(selector.get("argumentTypes")).size()<=16,"HOOK_ARGUMENT_COUNT");
            if(kind.equals("java")){require(text(selector,"className").length()<=512&&text(selector,"methodName").length()<=128&&!text(selector,"methodName").startsWith("<"),"HOOK_METHOD_NAME");}
            else require(text(selector,"moduleName").matches("lib[a-zA-Z0-9_+.$-]+\\.so")&&text(selector,"symbol").length()<=256&&"confirmed".equals(selector.get("signatureSource")),"HOOK_NATIVE_SELECTOR_FIELDS");
            selected.add(selector);
        }
        return List.copyOf(selected);
    }
    private Map<String,Object> withIdentity(Map<String,Object> result){result.put("identity",identity);return result;}
    private String newToken(){String value=token.get();require(value!=null&&value.matches("[a-f0-9]{64}"),"HOOK_TOKEN_SOURCE");return value;}
    private static String randomToken(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return HexFormat.of().formatHex(bytes);}
    private static Map<String,Object> map(Object... pairs){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
}
