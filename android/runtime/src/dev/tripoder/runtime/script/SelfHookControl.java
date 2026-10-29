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
        default Map<String,Object> screenshot(int maxDimension){throw new ScriptFault("HOOK_SCREENSHOT_UNSUPPORTED");}
        default Map<String,Object> dumpUiHierarchy(boolean includeInvisible,int limit){return Map.of("windows",List.of());}
        default Map<String,Object> dispatchUiAction(Map<String,Object> action){return Map.of("dispatched",false);}
        default List<Map<String,Object>> diagnostics(){return List.of();}
        default Map<String,Object> persistNetworkCapture(String tag,Object payload,String sourceSha,String revision){throw new UnsupportedOperationException("NETWORK_CAPTURE_PERSIST_UNSUPPORTED");}
        default Map<String,Object> listNetworkTraffic(long sinceSeq,int limit){return Map.of("exchanges",List.of(),"nextCursor",(double)sinceSeq);}
        default Map<String,Object> sendHttpRequest(Map<String,Object> request){return Map.of("statusCode",0d);}
        default Map<String,Object> personaConfigureTelephony(Map<String,Object> fields){throw new ScriptFault("HOOK_PERSONA_UNSUPPORTED");}
        default Map<String,Object> personaConfigureLocation(Map<String,Object> fields){throw new ScriptFault("HOOK_PERSONA_UNSUPPORTED");}
        default Map<String,Object> personaConfigureBattery(Integer level,Boolean charging){throw new ScriptFault("HOOK_PERSONA_UNSUPPORTED");}
        default Map<String,Object> personaConfigureLocale(String language,String country,String timeZoneId){throw new ScriptFault("HOOK_PERSONA_UNSUPPORTED");}
        default Map<String,Object> personaConfigureNetwork(boolean hideVpn){throw new ScriptFault("HOOK_PERSONA_UNSUPPORTED");}
        default Map<String,Object> personaStatus(){throw new ScriptFault("HOOK_PERSONA_UNSUPPORTED");}
        default Map<String,Object> malwareConfigure(boolean enabled,boolean blockHighRiskIntents){throw new ScriptFault("HOOK_MALWARE_PROBE_UNSUPPORTED");}
        default Map<String,Object> malwareConfigure(boolean enabled,boolean blockHighRiskIntents,boolean blockExecve,boolean blockKill,boolean networkSinkhole){return malwareConfigure(enabled,blockHighRiskIntents);}
        default Map<String,Object> malwareClear(){throw new ScriptFault("HOOK_MALWARE_PROBE_UNSUPPORTED");}
        default Map<String,Object> malwareStatus(){throw new ScriptFault("HOOK_MALWARE_PROBE_UNSUPPORTED");}
        default Map<String,Object> malwareEvents(long sinceSeq,int limit){throw new ScriptFault("HOOK_MALWARE_PROBE_UNSUPPORTED");}
        default Map<String,Object> heapChooseInstances(String className,int limit){throw new ScriptFault("HOOK_HEAP_UNSUPPORTED");}
        default Map<String,Object> heapReadField(String instanceId,String fieldName){throw new ScriptFault("HOOK_HEAP_UNSUPPORTED");}
        default Map<String,Object> heapSnapshotContainer(String instanceId,int maxEntries){throw new ScriptFault("HOOK_HEAP_UNSUPPORTED");}
        default Map<String,Object> timeWarpConfigure(long offsetMillis,double delayScale){throw new ScriptFault("HOOK_TIME_WARP_UNSUPPORTED");}
        default Map<String,Object> timeWarpReset(){throw new ScriptFault("HOOK_TIME_WARP_UNSUPPORTED");}
        default Map<String,Object> timeWarpStatus(){throw new ScriptFault("HOOK_TIME_WARP_UNSUPPORTED");}
        default Map<String,Object> honeydataConfigure(String category,List<Map<String,Object>> rows){throw new ScriptFault("HOOK_HONEYDATA_UNSUPPORTED");}
        default Map<String,Object> honeydataClear(){throw new ScriptFault("HOOK_HONEYDATA_UNSUPPORTED");}
        default Map<String,Object> honeydataStatus(){throw new ScriptFault("HOOK_HONEYDATA_UNSUPPORTED");}
        default Map<String,Object> processSnapshotHeap(){throw new ScriptFault("HOOK_PROCESS_SNAPSHOT_UNSUPPORTED");}
        default Map<String,Object> processSnapshotFreeze(String databaseName){throw new ScriptFault("HOOK_PROCESS_SNAPSHOT_UNSUPPORTED");}
        default Map<String,Object> envMockConfigureHttp(List<Map<String,Object>> rules){throw new ScriptFault("HOOK_ENV_MOCK_UNSUPPORTED");}
        default Map<String,Object> envMockConfigureNetwork(String networkState){throw new ScriptFault("HOOK_ENV_MOCK_UNSUPPORTED");}
        default Map<String,Object> envMockClear(){throw new ScriptFault("HOOK_ENV_MOCK_UNSUPPORTED");}
        default Map<String,Object> envMockStatus(){throw new ScriptFault("HOOK_ENV_MOCK_UNSUPPORTED");}
        default Map<String,Object> sendComponent(Map<String,Object> request){throw new ScriptFault("HOOK_COMPONENT_UNSUPPORTED");}
        default Map<String,Object> providerInteract(Map<String,Object> request){throw new ScriptFault("HOOK_PROVIDER_UNSUPPORTED");}
        default Map<String,Object> invokeGuestMethod(Map<String,Object> target){return Map.of("result",null);}
        default Map<String,Object> codeCommand(String command,Map<String,Object> params){throw new UnsupportedOperationException("CODE_OBSERVATION_UNSUPPORTED");}
        default Map<String,Object> requestCodeSnapshot(String dexId){throw new UnsupportedOperationException("CODE_SNAPSHOT_UNSUPPORTED");}
        default Map<String,Object> requestEvidenceSnapshot(String tag,Object payload){throw new UnsupportedOperationException("EVIDENCE_CAPTURE_UNSUPPORTED");}
        default Map<String,Object> invocationContext(){return Map.of();}
    }
    private record Prepared(String token,DraftCompiler.Plan plan,long deadline,long evidenceEpoch) {}
    private static final Map<String,Set<String>> FIELDS=Map.ofEntries(
        Map.entry("modules",Set.of()),Map.entry("observe",Set.of("selectors")),Map.entry("prepare",Set.of("evidenceId","source")),
        Map.entry("activate",Set.of("token","sourceSha256")),Map.entry("disable",Set.of("revision")),
        Map.entry("updateParameters",Set.of("revision","parameterVersion","parameters")),Map.entry("cancelPreparation",Set.of("token")),
        Map.entry("discard",Set.of()),Map.entry("status",Set.of()),Map.entry("events",Set.of("sinceSeq")),
        Map.entry("dexSearch",Set.of("packagePrefix","limit")),Map.entry("dexInspect",Set.of("className")),
        Map.entry("nativeExports",Set.of("moduleName","symbolPrefix","limit")),Map.entry("uiDump",Set.of("includeInvisible","limit")),Map.entry("uiScreenshot",Set.of("maxDimension")),
        Map.entry("uiAction",Set.of("action")),Map.entry("diagnostics",Set.of()),

        Map.entry("networkInspect",Set.of("moduleName")),Map.entry("networkStart",Set.of("moduleName","buildId","profile","signatureSource")),Map.entry("networkStop",Set.of("captureId")),Map.entry("networkStatus",Set.of()),Map.entry("networkMessages",Set.of("captureId","sinceSeq","limit")),Map.entry("networkHar",Set.of("captureId","sinceSeq","limit")),Map.entry("componentSend",Set.of("request")),Map.entry("privacyConfigureAttribution",Set.of("entries")),Map.entry("personaConfigureTelephony",Set.of("fields")),Map.entry("personaConfigureBattery",Set.of("level","charging")),Map.entry("personaConfigureLocation",Set.of("fields")),Map.entry("personaConfigureLocale",Set.of("language","country","timeZoneId")),Map.entry("personaConfigureNetwork",Set.of("hideVpn")),Map.entry("personaStatus",Set.of()),Map.entry("malwareConfigure",Set.of("enabled","blockHighRiskIntents")),Map.entry("malwareClear",Set.of()),Map.entry("malwareStatus",Set.of()),Map.entry("malwareEvents",Set.of("sinceSeq","limit")),Map.entry("heapChooseInstances",Set.of("className","limit")),Map.entry("heapReadField",Set.of("instanceId","fieldName")),Map.entry("heapSnapshotContainer",Set.of("instanceId","maxEntries")),Map.entry("timeWarpConfigure",Set.of("offsetMillis","delayScale")),Map.entry("timeWarpReset",Set.of()),Map.entry("timeWarpStatus",Set.of()),Map.entry("honeydataConfigure",Set.of("category","rows")),Map.entry("honeydataClear",Set.of()),Map.entry("honeydataStatus",Set.of()),Map.entry("processSnapshotHeap",Set.of()),Map.entry("processSnapshotFreeze",Set.of("databaseName")),Map.entry("envMockConfigureHttp",Set.of("rules")),Map.entry("envMockConfigureNetwork",Set.of("networkState")),Map.entry("envMockClear",Set.of()),Map.entry("envMockStatus",Set.of()),Map.entry("privacyMarkConsentStage",Set.of("stage","note")),Map.entry("privacyAttribution",Set.of("className")),Map.entry("privacyTimeline",Set.of("sinceSeq","limit")),Map.entry("providerInteract",Set.of("request")),Map.entry("networkConfigure",Set.of("connections","messages","totalBytes","confirmHigherLimits")),Map.entry("networkTraffic",Set.of("captureId","sinceSeq","limit")),Map.entry("httpRequest",Set.of("request")),
        Map.entry("guestInvoke",Set.of("target")),Map.entry("probeStatus",Set.of()),Map.entry("probeStop",Set.of()),Map.entry("classLoaders",Set.of()),Map.entry("classLoaderCreate",Set.of("dexId","parentClassLoaderId")),Map.entry("dexList",Set.of("classLoaderId","cursor","limit")),Map.entry("dexSnapshot",Set.of("dexId")),Map.entry("codeArtifacts",Set.of()),Map.entry("runtimeEvents",Set.of("sinceSeq","limit")),Map.entry("classInspect",Set.of("classLoaderId","className")),
        Map.entry("_networkObserve",Set.of("selectors")),Map.entry("_networkPrepare",Set.of("evidenceId","source")),Map.entry("_networkActivate",Set.of("token","sourceSha256")),Map.entry("_networkDisable",Set.of("revision")),Map.entry("_networkStatus",Set.of()));
    private final Map<String,Object> identity;
    private final String generation;
    private final Platform platform;
    private final NetworkCaptureControl network;
    private final RuntimePrivacyProbeState privacy;
    private final LongSupplier clock;
    private final Supplier<String> token;
    private final AtomicBoolean commanding=new AtomicBoolean();
    private final AtomicLong invalidation=new AtomicLong();
    private final List<HookInvocationSession> retired=new ArrayList<>();
    private HookEvidence evidence,networkEvidence;
    private Prepared prepared,networkPrepared;
    private volatile HookInvocationSession active,networkActive;
    private DraftCompiler.Plan activePlan,networkActivePlan;
    private long observedEpoch,networkObservedEpoch,lastClock=-1;
    private int activations;
    @SuppressWarnings("unchecked") public SelfHookControl(Map<String,Object> identity,Platform platform,LongSupplier elapsedClock,Supplier<String> token){
        this.identity=(Map<String,Object>)ScriptValues.freeze(identity);this.platform=Objects.requireNonNull(platform);this.clock=Objects.requireNonNull(elapsedClock);this.token=Objects.requireNonNull(token);
        for(String key:List.of("space","installGeneration","descriptorSha256","processGeneration"))text(this.identity,key);
        Object pid=this.identity.get("pid");require(pid instanceof Double&&(Double)pid>0&&(Double)pid==Math.rint((Double)pid),"HOOK_PROCESS_ID");
        object(this.identity.get("target"));generation=text(this.identity,"processGeneration");network=new NetworkCaptureControl(platform,this::command,generation,this::newToken);privacy=new RuntimePrivacyProbeState();
    }
    public SelfHookControl(Map<String,Object> identity,Platform platform,LongSupplier elapsedClock){this(identity,platform,elapsedClock,SelfHookControl::randomToken);}
    /** Lifecycle observer may invalidate preparations without waiting on a command/backend publication lock. */
    public void invalidateObservation(){invalidation.incrementAndGet();}
    /** A module/loader observer calls this only when an active target's retained ownership is lost. */
    public void invalidateActiveTarget(Throwable cause){
        invalidation.incrementAndGet();
        HookInvocationSession session=active;if(session!=null)session.invalidateTarget(cause);
        HookInvocationSession netSession=networkActive;if(netSession!=null)netSession.invalidateTarget(cause);
    }
    @Override public String handle(String raw){
        require(commanding.compareAndSet(false,true),"HOOK_CONTROL_BUSY");
        boolean dispatched=false,query=false;
        try {
            Map<String,Object> input=HookWire.request(raw);
            validateInput(input);query=HookCommands.readOnly(text(input,"command"));
            dispatched=true;
            return HookWire.encode(Map.of("ok",true,"result",command(input)));
        }
        catch(Exception error){String message=String.valueOf(error);return HookWire.encode(Map.of("ok",false,"outcome",error instanceof HookPreflightRejected?"REJECTED":dispatched?(query?"UNAVAILABLE":"UNKNOWN"):"REJECTED","error",message.substring(0,Math.min(1000,message.length()))));}
        finally {commanding.set(false);}
    }
    /** Only this phase may certify rejection without executing a command. */
    private void validateInput(Map<String,Object> input){
        String name=text(input,"command");Set<String> fields=FIELDS.get(name);require(fields!=null,"HOOK_COMMAND_UNSUPPORTED");
        Set<String> expected=new HashSet<>(fields);expected.add("command");expected.add("processGeneration");
        if(name.equals("malwareConfigure")){
            Set<String> allowed=new HashSet<>(expected);allowed.addAll(Set.of("blockExecve","blockKill","networkSinkhole"));
            require(input.keySet().containsAll(expected)&&allowed.containsAll(input.keySet()),"HOOK_COMMAND_FIELDS");
        }else{
            require(input.keySet().equals(expected),"HOOK_COMMAND_FIELDS");
        }
        require(generation.equals(input.get("processGeneration"))&&identity.equals(platform.identity()),"HOOK_PROCESS_STALE");
        if(name.equals("observe")||name.equals("_networkObserve"))selectors(input.get("selectors"));
    }
    private Map<String,Object> command(Map<String,Object> input){
        String name=text(input,"command");Set<String> fields=FIELDS.get(name);require(fields!=null,"HOOK_COMMAND_UNSUPPORTED");Set<String> expected=new HashSet<>(fields);expected.add("command");expected.add("processGeneration");
        if(name.equals("malwareConfigure")){
            Set<String> allowed=new HashSet<>(expected);allowed.addAll(Set.of("blockExecve","blockKill","networkSinkhole"));
            require(input.keySet().containsAll(expected)&&allowed.containsAll(input.keySet()),"HOOK_COMMAND_FIELDS");
        }else{
            require(input.keySet().equals(expected),"HOOK_COMMAND_FIELDS");
        }
        require(generation.equals(input.get("processGeneration"))&&identity.equals(platform.identity()),"HOOK_PROCESS_STALE");
        long now=clock.getAsLong();require(now>=lastClock&&now>=0&&now<=Long.MAX_VALUE-60000,"HOOK_CLOCK_INVALID");lastClock=now;
        if(prepared!=null&&(now>prepared.deadline||prepared.evidenceEpoch!=invalidation.get()))prepared=null;
        if(evidence!=null&&observedEpoch!=invalidation.get())evidence=null;
        if(networkPrepared!=null&&(now>networkPrepared.deadline||networkPrepared.evidenceEpoch!=invalidation.get()))networkPrepared=null;
        if(networkEvidence!=null&&networkObservedEpoch!=invalidation.get())networkEvidence=null;
        switch(name){
            case "networkInspect":return withIdentity(new LinkedHashMap<>(network.inspect(text(input,"moduleName"))));
            case "networkStart":return withIdentity(new LinkedHashMap<>(network.start(text(input,"moduleName"),text(input,"buildId"),text(input,"profile"),text(input,"signatureSource"))));
            case "networkConfigure":{
                Object c=input.get("connections"),m=input.get("messages"),t=input.get("totalBytes"),ok=input.get("confirmHigherLimits");
                require(c instanceof Double&&m instanceof Double&&t instanceof Double&&ok instanceof Boolean,"HOOK_NETWORK_CONFIGURE_ARG");
                for(Object n:new Object[]{c,m,t})require((Double)n==Math.rint((Double)n),"HOOK_NETWORK_CONFIGURE_ARG");
                return withIdentity(new LinkedHashMap<>(network.configure(((Double)c).intValue(),((Double)m).intValue(),((Double)t).intValue(),(Boolean)ok)));
            }
            case "networkStop":return withIdentity(new LinkedHashMap<>(network.stop(text(input,"captureId"))));
            case "networkStatus":return withIdentity(new LinkedHashMap<>(network.status()));
            case "probeStatus":case "probeStop":case "classLoaders":case "classLoaderCreate":case "dexList":case "dexSnapshot":case "codeArtifacts":case "runtimeEvents":case "classInspect":{
                Map<String,Object> params=new LinkedHashMap<>(input);params.remove("command");params.remove("processGeneration");
                if(name.equals("dexList")){require(params.get("classLoaderId") instanceof String&&((String)params.get("classLoaderId")).length()<=80&&params.get("cursor") instanceof String&&((String)params.get("cursor")).length()<=80,"CLASS_LOADER_REFERENCE");Object limit=params.get("limit");require(limit instanceof Double&&(Double)limit>=1&&(Double)limit<=128&&(Double)limit==Math.rint((Double)limit),"DEX_PAGE_LIMIT");}
                if(name.equals("dexSnapshot"))require(text(params,"dexId").matches("[A-Za-z0-9_-]{1,80}"),"DEX_REFERENCE");
                if(name.equals("classInspect")){text(params,"classLoaderId");text(params,"className");}
                if(name.equals("classLoaderCreate")){require(text(params,"dexId").matches("[A-Za-z0-9_-]{1,80}")&&text(params,"parentClassLoaderId").matches("[A-Za-z0-9_-]{1,80}"),"CODE_LOADER_REFERENCE");}
                if(name.equals("runtimeEvents")){Object since=params.get("sinceSeq"),limit=params.get("limit");require(since instanceof Double&&(Double)since>=0&&(Double)since==Math.rint((Double)since)&&limit instanceof Double&&(Double)limit>=1&&(Double)limit<=64&&(Double)limit==Math.rint((Double)limit),"RUNTIME_EVENT_RANGE");}
                return withIdentity(platform.codeCommand(name,params));
            }
            case "modules":return map("modules",platform.modules(),"identity",identity);
            case "_networkObserve":{
                require(networkActive==null&&networkPrepared==null,"HOOK_CONTEXT_IN_USE");networkEvidence=null;
                List<Map<String,Object>> selectors=selectors(input.get("selectors"));long epoch=invalidation.get();Observation observed=platform.observe(selectors);
                require(epoch==invalidation.get(),"HOOK_EVIDENCE_STALE");String id=newToken();
                Map<String,Object> wire=map("id",id,"source","guest-agent","target",identity.get("target"),"processInstanceId",ScriptValues.text(identity.get("pid"))+":"+generation,"abi","arm64-v8a","capabilities",new ArrayList<>(platform.capabilities()),"symbols",observed.symbols());
                networkEvidence=new HookEvidence(wire);networkObservedEpoch=epoch;require(platform.evidenceLive(networkEvidence),"HOOK_EVIDENCE_STALE");
                return map("evidence",networkEvidence.wire,"identity",identity,"nativeModules",observed.nativeModules());
            }
            case "_networkPrepare":{
                require(networkActive==null&&networkPrepared==null,"HOOK_CONTEXT_IN_USE");require(networkEvidence!=null&&networkEvidence.id.equals(input.get("evidenceId"))&&platform.evidenceLive(networkEvidence),"HOOK_EVIDENCE_STALE");
                String source=text(input,"source");long epoch=invalidation.get();DraftCompiler.Plan plan=DraftCompiler.compile(source,networkEvidence,platform::requestCodeSnapshot,platform::requestEvidenceSnapshot,privacy::attributionOf,privacy::currentStage);
                require(epoch==invalidation.get()&&platform.evidenceLive(networkEvidence),"HOOK_EVIDENCE_STALE");networkPrepared=new Prepared(newToken(),plan,now+60000,epoch);
                return map("state","PREPARED","token",networkPrepared.token,"sourceSha256",plan.sourceSha256(),"evidenceId",networkEvidence.id,"rules",plan.rules().size(),"identity",identity);
            }
            case "_networkActivate":{
                require(networkActive==null&&networkPrepared!=null&&networkPrepared.token.equals(input.get("token"))&&networkPrepared.plan.sourceSha256().equals(input.get("sourceSha256")),"HOOK_PREPARATION_STALE");
                Prepared candidate=networkPrepared;networkPrepared=null;require(platform.evidenceLive(candidate.plan.evidence())&&candidate.evidenceEpoch==invalidation.get(),"HOOK_EVIDENCE_STALE");
                HookInvocationSession.PreparedBackend backend=platform.prepare(candidate.plan);
                require(candidate.evidenceEpoch==invalidation.get()&&platform.evidenceLive(candidate.plan.evidence()),"HOOK_EVIDENCE_STALE");
                networkActivePlan=candidate.plan;networkActive=new HookInvocationSession(generation,newToken(),candidate.plan.rules(),candidate.plan.specs(),candidate.plan.parameters(),backend,platform::invocationContext,candidate.plan.sourceSha256());
                networkActive.observeEvents(network::event);
                try{networkActive.activate(generation,networkActive.status().revision());}
                catch(IllegalStateException failure){
                    if(failure.getCause() instanceof HookPublicationRejected){
                        networkActive=null;networkActivePlan=null;networkEvidence=null;
                    }
                    throw failure;
                }
                return withIdentity(networkHookStatus());
            }
            case "_networkDisable":{
                require(networkActive!=null,"HOOK_REVISION_STALE");networkActive.disable(generation,text(input,"revision"));return withIdentity(networkHookStatus());
            }
            case "_networkStatus":return withIdentity(networkHookStatus());
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
                require(activations<128,"HOOK_ACTIVATION_BUDGET");String source=text(input,"source");long epoch=invalidation.get();DraftCompiler.Plan plan=DraftCompiler.compile(source,evidence,platform::requestCodeSnapshot,platform::requestEvidenceSnapshot,privacy::attributionOf,privacy::currentStage);
                require(epoch==invalidation.get()&&platform.evidenceLive(evidence),"HOOK_EVIDENCE_STALE");prepared=new Prepared(newToken(),plan,now+60000,epoch);
                return map("state","PREPARED","token",prepared.token,"sourceSha256",plan.sourceSha256(),"evidenceId",evidence.id,"rules",plan.rules().size(),"identity",identity);
            }
            case "activate":{
                require(active==null&&prepared!=null&&prepared.token.equals(input.get("token"))&&prepared.plan.sourceSha256().equals(input.get("sourceSha256")),"HOOK_PREPARATION_STALE");
                Prepared candidate=prepared;prepared=null;require(platform.evidenceLive(candidate.plan.evidence())&&candidate.evidenceEpoch==invalidation.get(),"HOOK_EVIDENCE_STALE");
                HookInvocationSession.PreparedBackend backend=platform.prepare(candidate.plan);
                require(candidate.evidenceEpoch==invalidation.get()&&platform.evidenceLive(candidate.plan.evidence()),"HOOK_EVIDENCE_STALE");
                activePlan=candidate.plan;active=new HookInvocationSession(generation,newToken(),candidate.plan.rules(),candidate.plan.specs(),candidate.plan.parameters(),backend,platform::invocationContext,candidate.plan.sourceSha256());activations++;
                active.observeEvents(network::event);
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
                int limit=((Double)rawLimit).intValue();List<String> classes=platform.searchDexClasses((String)rawPrefix,limit+1);return withIdentity(map("classes",classes.subList(0,Math.min(limit,classes.size())),"truncated",classes.size()>limit,"source","LIVE_GUEST_CLASS_VIEW_AND_APK_SET"));
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
                Object rawLimit=input.get("limit");require(rawLimit instanceof Double&&(Double)rawLimit==Math.rint((Double)rawLimit)&&(Double)rawLimit>=1&&(Double)rawLimit<=4096,"HOOK_UI_DUMP_LIMIT");
                return withIdentity(new LinkedHashMap<>(platform.dumpUiHierarchy((Boolean)inc,((Double)rawLimit).intValue())));
            }
            case "uiScreenshot":{
                Object raw=input.get("maxDimension");require(raw instanceof Double&&(Double)raw==Math.rint((Double)raw)&&(Double)raw>=64&&(Double)raw<=2048,"HOOK_SCREENSHOT_DIMENSION");
                return withIdentity(new LinkedHashMap<>(platform.screenshot(((Double)raw).intValue())));
            }
            case "uiAction":{
                return withIdentity(new LinkedHashMap<>(platform.dispatchUiAction(object(input.get("action")))));
            }
            case "diagnostics":{
                return withIdentity(map("failures",platform.diagnostics()));
            }
            case "networkHar":{
                Object rawSince=input.get("sinceSeq"),rawLimit=input.get("limit");
                require(rawSince instanceof Double&&(Double)rawSince==Math.rint((Double)rawSince)&&(Double)rawSince>=0,"HOOK_TRAFFIC_SEQ");
                require(rawLimit instanceof Double&&(Double)rawLimit==Math.rint((Double)rawLimit)&&(Double)rawLimit>=1&&(Double)rawLimit<=16,"HOOK_TRAFFIC_LIMIT");
                return withIdentity(new LinkedHashMap<>(network.har(text(input,"captureId"),((Double)rawSince).longValue(),((Double)rawLimit).intValue())));
            }
            case "networkMessages":case "networkTraffic":{
                Object rawSince=input.get("sinceSeq");require(rawSince instanceof Double&&(Double)rawSince==Math.rint((Double)rawSince)&&(Double)rawSince>=0,"HOOK_TRAFFIC_SEQ");
                Object rawLimit=input.get("limit");require(rawLimit instanceof Double&&(Double)rawLimit==Math.rint((Double)rawLimit)&&(Double)rawLimit>=1&&(Double)rawLimit<=100,"HOOK_TRAFFIC_LIMIT");
                return withIdentity(new LinkedHashMap<>(name.equals("networkMessages")?network.messages(text(input,"captureId"),((Double)rawSince).longValue(),((Double)rawLimit).intValue()):network.query(text(input,"captureId"),((Double)rawSince).longValue(),((Double)rawLimit).intValue())));
            }
            case "componentSend":return withIdentity(new LinkedHashMap<>(platform.sendComponent(object(input.get("request")))));
            case "personaConfigureTelephony":{
                Object raw=input.get("fields");require(raw instanceof Map<?,?>,"PERSONA_TELEPHONY_FIELDS_ARG");
                return withIdentity(new LinkedHashMap<>(platform.personaConfigureTelephony(object(raw))));
            }
            case "personaConfigureLocation":{
                Object raw=input.get("fields");require(raw instanceof Map<?,?>,"PERSONA_LOCATION_FIELDS_ARG");
                return withIdentity(new LinkedHashMap<>(platform.personaConfigureLocation(object(raw))));
            }
            case "personaConfigureBattery":{
                Object rawLevel=input.get("level"),rawCharging=input.get("charging");
                require(rawLevel==null||rawLevel instanceof Double,"PERSONA_BATTERY_LEVEL_ARG");require(rawCharging==null||rawCharging instanceof Boolean,"PERSONA_BATTERY_CHARGING_ARG");
                Integer level=rawLevel==null?null:((Double)rawLevel).intValue();Boolean charging=(Boolean)rawCharging;
                return withIdentity(new LinkedHashMap<>(platform.personaConfigureBattery(level,charging)));
            }
            case "personaConfigureLocale":{
                Object rawLang=input.get("language"),rawCtry=input.get("country"),rawTz=input.get("timeZoneId");
                require(rawLang instanceof String&&rawCtry instanceof String&&rawTz instanceof String,"PERSONA_LOCALE_ARG");
                return withIdentity(new LinkedHashMap<>(platform.personaConfigureLocale((String)rawLang,(String)rawCtry,(String)rawTz)));
            }
            case "personaConfigureNetwork":{
                Object rawHide=input.get("hideVpn");require(rawHide instanceof Boolean,"PERSONA_NETWORK_ARG");
                return withIdentity(new LinkedHashMap<>(platform.personaConfigureNetwork((Boolean)rawHide)));
            }
            case "personaStatus":return withIdentity(new LinkedHashMap<>(platform.personaStatus()));
            case "malwareConfigure":{
                Object rawEn=input.get("enabled"),rawBlock=input.get("blockHighRiskIntents");
                Object rawExec=input.getOrDefault("blockExecve",Boolean.FALSE),rawKill=input.getOrDefault("blockKill",Boolean.FALSE),rawSink=input.getOrDefault("networkSinkhole",Boolean.FALSE);
                require(rawEn instanceof Boolean&&rawBlock instanceof Boolean&&rawExec instanceof Boolean&&rawKill instanceof Boolean&&rawSink instanceof Boolean,"MALWARE_CONFIGURE_ARG");
                return withIdentity(new LinkedHashMap<>(platform.malwareConfigure((Boolean)rawEn,(Boolean)rawBlock,(Boolean)rawExec,(Boolean)rawKill,(Boolean)rawSink)));
            }
            case "malwareClear":return withIdentity(new LinkedHashMap<>(platform.malwareClear()));
            case "malwareStatus":return withIdentity(new LinkedHashMap<>(platform.malwareStatus()));
            case "malwareEvents":{
                Object rawSince=input.get("sinceSeq"),rawLimit=input.get("limit");
                require(rawSince instanceof Double&&(Double)rawSince==Math.rint((Double)rawSince)&&(Double)rawSince>=0,"MALWARE_EVENTS_SEQ");
                require(rawLimit instanceof Double&&(Double)rawLimit==Math.rint((Double)rawLimit)&&(Double)rawLimit>=1&&(Double)rawLimit<=64,"MALWARE_EVENTS_LIMIT");
                return withIdentity(new LinkedHashMap<>(platform.malwareEvents(((Double)rawSince).longValue(),((Double)rawLimit).intValue())));
            }
            case "heapChooseInstances":{
                String cls=text(input,"className");Object rawLimit=input.get("limit");
                require(rawLimit instanceof Double&&(Double)rawLimit==Math.rint((Double)rawLimit)&&(Double)rawLimit>=1&&(Double)rawLimit<=64,"HEAP_CHOOSE_LIMIT");
                return withIdentity(new LinkedHashMap<>(platform.heapChooseInstances(cls,((Double)rawLimit).intValue())));
            }
            case "heapReadField":{
                String instId=text(input,"instanceId"),field=text(input,"fieldName");
                return withIdentity(new LinkedHashMap<>(platform.heapReadField(instId,field)));
            }
            case "heapSnapshotContainer":{
                String instId=text(input,"instanceId");Object rawMax=input.get("maxEntries");
                require(rawMax instanceof Double&&(Double)rawMax==Math.rint((Double)rawMax)&&(Double)rawMax>=1&&(Double)rawMax<=64,"HEAP_SNAPSHOT_LIMIT");
                return withIdentity(new LinkedHashMap<>(platform.heapSnapshotContainer(instId,((Double)rawMax).intValue())));
            }
            case "timeWarpConfigure":{
                Object rawOff=input.get("offsetMillis"),rawScale=input.get("delayScale");
                require(rawOff instanceof Double&&(Double)rawOff==Math.rint((Double)rawOff)&&rawScale instanceof Double,"TIME_WARP_CONFIGURE_ARG");
                return withIdentity(new LinkedHashMap<>(platform.timeWarpConfigure(((Double)rawOff).longValue(),(Double)rawScale)));
            }
            case "timeWarpReset":return withIdentity(new LinkedHashMap<>(platform.timeWarpReset()));
            case "timeWarpStatus":return withIdentity(new LinkedHashMap<>(platform.timeWarpStatus()));
            case "honeydataConfigure":{
                String cat=text(input,"category");Object rawRows=input.get("rows");
                require(rawRows instanceof List<?>,"HONEYDATA_ROWS_ARG");
                List<Map<String,Object>> rows=new ArrayList<>();for(Object item:(List<?>)rawRows){require(item instanceof Map<?,?>,"HONEYDATA_ROW_ARG");rows.add(object(item));}
                return withIdentity(new LinkedHashMap<>(platform.honeydataConfigure(cat,rows)));
            }
            case "honeydataClear":return withIdentity(new LinkedHashMap<>(platform.honeydataClear()));
            case "honeydataStatus":return withIdentity(new LinkedHashMap<>(platform.honeydataStatus()));
            case "processSnapshotHeap":return withIdentity(new LinkedHashMap<>(platform.processSnapshotHeap()));
            case "processSnapshotFreeze":return withIdentity(new LinkedHashMap<>(platform.processSnapshotFreeze(text(input,"databaseName"))));
            case "envMockConfigureHttp":{
                Object rawRules=input.get("rules");require(rawRules instanceof List<?>,"ENV_MOCK_RULES_ARG");
                List<Map<String,Object>> rules=new ArrayList<>();for(Object item:(List<?>)rawRules){require(item instanceof Map<?,?>,"ENV_MOCK_RULE_ARG");rules.add(object(item));}
                return withIdentity(new LinkedHashMap<>(platform.envMockConfigureHttp(rules)));
            }
            case "envMockConfigureNetwork":return withIdentity(new LinkedHashMap<>(platform.envMockConfigureNetwork(text(input,"networkState"))));
            case "envMockClear":return withIdentity(new LinkedHashMap<>(platform.envMockClear()));
            case "envMockStatus":return withIdentity(new LinkedHashMap<>(platform.envMockStatus()));
            case "privacyConfigureAttribution":{
                Object raw=input.get("entries");require(raw instanceof List<?>,"PRIVACY_ATTRIBUTION_LIST");
                List<Map<String,Object>> entries=new ArrayList<>();for(Object item:(List<?>)raw){require(item instanceof Map<?,?>,"PRIVACY_ATTRIBUTION_LIST");entries.add(object(item));}
                return withIdentity(new LinkedHashMap<>(privacy.configureAttribution(entries)));
            }
            case "privacyMarkConsentStage":return withIdentity(new LinkedHashMap<>(privacy.markConsentStage(text(input,"stage"),text(input,"note"),clock.getAsLong())));
            case "privacyAttribution":return withIdentity(new LinkedHashMap<>(privacy.attributionOf(text(input,"className"))));
            case "privacyTimeline":{
                Object rawSince=input.get("sinceSeq"),rawLimit=input.get("limit");
                require(rawSince instanceof Double&&(Double)rawSince==Math.rint((Double)rawSince)&&(Double)rawSince>=0,"PRIVACY_TIMELINE_SEQ");
                require(rawLimit instanceof Double&&(Double)rawLimit==Math.rint((Double)rawLimit)&&(Double)rawLimit>=1&&(Double)rawLimit<=64,"PRIVACY_TIMELINE_LIMIT");
                return withIdentity(new LinkedHashMap<>(privacy.timelinePage(((Double)rawSince).longValue(),((Double)rawLimit).intValue())));
            }
            case "providerInteract":return withIdentity(new LinkedHashMap<>(platform.providerInteract(object(input.get("request")))));
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
            if(snapshot.state()==HookInvocationSession.State.DISABLED){Map<String,Object> result=map("state","DISABLED","revision",snapshot.revision(),"hits",snapshot.hits(),"errors",snapshot.errors(),"firstInvocation",active.firstInvocation());retired.add(active);active=null;activePlan=null;evidence=null;platform.discardObservation();return result;}
            require(snapshot.state()!=HookInvocationSession.State.FAILED,"HOOK_PROCESS_INVALIDATED");
            return map("state",snapshot.state().name(),"revision",snapshot.revision(),"sourceSha256",activePlan.sourceSha256(),"parameterVersion",snapshot.parameterVersion(),"parameters",snapshot.parameters(),"hits",snapshot.hits(),"errors",snapshot.errors(),"inFlight",snapshot.inFlight(),"firstInvocation",active.firstInvocation());
        }
        if(prepared!=null)return map("state","PREPARED","sourceSha256",prepared.plan.sourceSha256(),"deadlineElapsedMs",prepared.deadline);
        if(evidence!=null)return map("state","OBSERVED","evidenceId",evidence.id);return map("state","IDLE");
    }
    private Map<String,Object> networkHookStatus(){
        if(networkActive!=null){HookInvocationSession.Snapshot snapshot=networkActive.status();
            if(snapshot.state()==HookInvocationSession.State.DISABLED){Map<String,Object> result=map("state","DISABLED","revision",snapshot.revision(),"hits",snapshot.hits(),"errors",snapshot.errors(),"firstInvocation",networkActive.firstInvocation());networkActive=null;networkActivePlan=null;networkEvidence=null;return result;}
            require(snapshot.state()!=HookInvocationSession.State.FAILED,"HOOK_PROCESS_INVALIDATED");
            return map("state",snapshot.state().name(),"revision",snapshot.revision(),"sourceSha256",networkActivePlan.sourceSha256(),"parameterVersion",snapshot.parameterVersion(),"parameters",snapshot.parameters(),"hits",snapshot.hits(),"errors",snapshot.errors(),"inFlight",snapshot.inFlight(),"firstInvocation",networkActive.firstInvocation());
        }
        if(networkPrepared!=null)return map("state","PREPARED","sourceSha256",networkPrepared.plan.sourceSha256(),"deadlineElapsedMs",networkPrepared.deadline);
        if(networkEvidence!=null)return map("state","OBSERVED","evidenceId",networkEvidence.id);return map("state","IDLE");
    }
    private static List<Map<String,Object>> selectors(Object value){
        List<?> array=array(value);require(!array.isEmpty()&&array.size()<=128,"HOOK_SELECTOR_COUNT_OR_KIND");List<Map<String,Object>> selected=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(Object entry:array){Map<String,Object> selector=object(entry);String kind=text(selector,"kind"),id=text(selector,"id");require(id.matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}")&&ids.add(id),"HOOK_SYMBOL_ID");
            Set<String> keys=kind.equals("java")?Set.of("id","kind","className","methodName","argumentTypes"):Set.of("id","kind","moduleName","symbol","argumentTypes","returnType","buildId","signatureSource");
            if(kind.equals("java")&&selector.containsKey("classLoaderId")){keys=new HashSet<>(keys);keys.add("classLoaderId");require(text(selector,"classLoaderId").matches("[A-Za-z0-9_-]{1,80}"),"CLASS_LOADER_REFERENCE");}
            require(Set.of("java","native").contains(kind)&&selector.keySet().equals(keys),"HOOK_SELECTOR_FIELDS");require(strings(selector.get("argumentTypes")).size()<=16,"HOOK_ARGUMENT_COUNT");
            if(kind.equals("java")){require(text(selector,"className").length()<=512&&text(selector,"methodName").length()<=128&&(!text(selector,"methodName").startsWith("<")||"<init>".equals(text(selector,"methodName"))),"HOOK_METHOD_NAME");}
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
