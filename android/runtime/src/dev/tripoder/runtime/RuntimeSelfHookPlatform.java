package dev.tripoder.runtime;
import android.os.Process;
import dev.tripoder.runtime.script.*;
import java.util.*;
/** One product evidence boundary and one session for mixed Java/Native plans.
 * Components retain their exact metadata partitions; this layer verifies the
 * full ordered observation before delegating any binding or publication. */
final class RuntimeSelfHookPlatform implements SelfHookControl.Platform {
    private final Map<String,Object> identity;
    private final RuntimeJavaHookBackend javaBackend;private final RuntimeNativeHookBackend nativeBackend;
    private final RuntimeJavaObservation javaObservation;private final RuntimeNativeObservation nativeObservation;
    private List<Map<String,Object>> metadata=List.of();
    @SuppressWarnings("unchecked") RuntimeSelfHookPlatform(Map<String,Object> identity,RuntimeJavaObservation javaObservation,RuntimeNativeObservation nativeObservation){
        this.identity=(Map<String,Object>)ScriptValues.freeze(identity);this.javaObservation=javaObservation;this.javaBackend=new RuntimeJavaHookBackend(javaObservation);
        this.nativeBackend=new RuntimeNativeHookBackend(nativeObservation);this.nativeObservation=nativeObservation;
    }
    public Map<String,Object> identity(){return identity;}
    public Set<String> capabilities(){return Set.of("java-hook","native-hook");}
    public List<Map<String,Object>> modules(){return nativeObservation.modules();}
    public List<Map<String,Object>> moduleEvents(){return nativeObservation.moduleEvents();}
    public List<String> searchDexClasses(String packagePrefix,int limit){try{return javaObservation.searchClasses(packagePrefix,limit);}catch(Exception error){throw new IllegalStateException(error);}}
    public Map<String,Object> inspectDexClass(String className){try{return javaObservation.inspectClass(className);}catch(Exception error){throw new IllegalStateException(error);}}
    public List<Map<String,Object>> searchNativeExports(String moduleName,String symbolPrefix,int limit){try{return nativeObservation.searchExports(moduleName,symbolPrefix,limit);}catch(Exception error){throw new IllegalStateException(error);}}
    public Map<String,Object> screenshot(int maxDimension){try{return RuntimeStartupDiagnostics.screenshot(maxDimension);}catch(Exception error){throw new IllegalStateException(error);}}
    public Map<String,Object> dumpUiHierarchy(boolean includeInvisible,int limit){try{return RuntimeStartupDiagnostics.dumpUiHierarchy(includeInvisible,limit);}catch(Exception error){throw new IllegalStateException(error);}}
    public Map<String,Object> dispatchUiAction(Map<String,Object> action){try{return RuntimeStartupDiagnostics.dispatchUiAction(action);}catch(Exception error){throw new IllegalStateException(error);}}
    public List<Map<String,Object>> diagnostics(){return RuntimeStartupDiagnostics.recentFailures();}
    public Map<String,Object> persistNetworkCapture(String tag,Object payload,String sourceSha,String revision){
        try(android.os.ParcelFileDescriptor directory=RuntimeEngine.current.artifactDirectory()){
            Map<String,Object> context=Map.of("processGeneration",identity.get("processGeneration"),"sourceSha256",sourceSha,"revision",revision,"symbol","network.capture","monotonicNanos",Long.toString(System.nanoTime()));
            return HookWire.request(RuntimeCodeArtifacts.capture(directory.getFd(),new org.json.JSONObject(identity),tag,payload,context).toString());
        }catch(Exception error){throw new IllegalStateException("NETWORK_CAPTURE_PERSIST_FAILED",error);}
    }
    public Map<String,Object> listNetworkTraffic(long sinceSeq,int limit){try{return RuntimeSecurityAudit.listNetworkTraffic(sinceSeq,limit);}catch(Exception error){throw new IllegalStateException(error);}}
    public Map<String,Object> sendHttpRequest(Map<String,Object> request){try{return RuntimeSecurityAudit.sendHttpRequest(request);}catch(HookPreflightRejected rejected){throw rejected;}catch(Exception error){throw new IllegalStateException(error);}}
    public Map<String,Object> personaConfigureTelephony(Map<String,Object> fields){try{return RuntimeSandboxPersona.configureTelephony(fields);}catch(IllegalArgumentException invalid){throw new HookPreflightRejected(String.valueOf(invalid.getMessage()));}}
    public Map<String,Object> personaConfigureLocation(Map<String,Object> fields){try{return RuntimeSandboxPersona.configureLocation(fields);}catch(IllegalArgumentException invalid){throw new HookPreflightRejected(String.valueOf(invalid.getMessage()));}}
    public Map<String,Object> personaConfigureBattery(Integer level,Boolean charging){try{return RuntimeSandboxPersona.configureBattery(level,charging);}catch(IllegalArgumentException invalid){throw new HookPreflightRejected(String.valueOf(invalid.getMessage()));}}
    public Map<String,Object> personaStatus(){return RuntimeSandboxPersona.status();}
    public Map<String,Object> sendComponent(Map<String,Object> request){try{return RuntimeComponentDelivery.send(request);}catch(HookPreflightRejected rejected){throw rejected;}catch(Exception error){throw new IllegalStateException(error);}}
    public Map<String,Object> providerInteract(Map<String,Object> request){try{return RuntimeComponentDelivery.provider(request);}catch(HookPreflightRejected rejected){throw rejected;}catch(Exception error){throw new IllegalStateException(error);}}
    public Map<String,Object> invokeGuestMethod(Map<String,Object> target){try{return RuntimeSecurityAudit.invokeGuestMethod(target);}catch(HookPreflightRejected rejected){throw rejected;}catch(Exception error){throw new IllegalStateException(error);}}
    public Map<String,Object> codeCommand(String command,Map<String,Object> params){try{return RuntimeEngine.current.codeProbes().command(command,params);}catch(Exception error){throw new IllegalStateException(error);}}
    public Map<String,Object> requestCodeSnapshot(String dexId){try{return dev.tripoder.runtime.script.HookWire.request(RuntimeEngine.current.codeProbes().capture(dexId).toString());}catch(Exception error){throw new IllegalStateException(error);}}
    public Map<String,Object> requestEvidenceSnapshot(String tag,Object payload){try(android.os.ParcelFileDescriptor directory=RuntimeEngine.current.artifactDirectory()){return HookWire.request(RuntimeCodeArtifacts.capture(directory.getFd(),new org.json.JSONObject(identity),tag,payload,HookInvocationSession.evidenceContext()).toString());}catch(Exception error){throw new IllegalStateException("EVIDENCE_CAPTURE_FAILED:"+error.getClass().getSimpleName()+":"+error.getMessage(),error);}}
    public Map<String,Object> invocationContext(){RuntimeEngine engine=RuntimeEngine.current;RuntimeCodeProbes probes=engine.existingCodeProbes();return Map.of("runtimePhase",probes==null?(engine.ready?"APPLICATION_CREATED":"BEFORE_APPLICATION"):probes.currentStage(),"elapsedRealtimeNanos",Long.toString(android.os.SystemClock.elapsedRealtimeNanos()));}
    public SelfHookControl.Observation observe(List<Map<String,Object>> selectors){
        discardObservation();List<Map<String,Object>> java=new ArrayList<>(),nativeSymbols=new ArrayList<>();
        try {
            for(Map<String,Object> selector:selectors){
                if("java".equals(selector.get("kind")))java.add(selector);
                else if("native".equals(selector.get("kind")))nativeSymbols.add(selector);
                else throw new IllegalArgumentException("HOOK_SELECTOR_KIND");
            }
            Map<String,Map<String,Object>> symbols=new HashMap<>();
            if(!java.isEmpty())for(Map<String,Object> symbol:javaBackend.observe(java))if(symbols.put((String)symbol.get("id"),symbol)!=null)throw new IllegalArgumentException("HOOK_SYMBOL_ID");
            if(!nativeSymbols.isEmpty())for(Map<String,Object> symbol:nativeBackend.observe(nativeSymbols))if(symbols.put((String)symbol.get("id"),symbol)!=null)throw new IllegalArgumentException("HOOK_SYMBOL_ID");
            List<Map<String,Object>> ordered=new ArrayList<>();for(Map<String,Object> selector:selectors){Map<String,Object> symbol=symbols.get(selector.get("id"));if(symbol==null)throw new IllegalStateException("HOOK_OBSERVATION_INCOMPLETE");ordered.add(symbol);}
            metadata=List.copyOf(ordered);return new SelfHookControl.Observation(metadata,nativeObservation.modules());
        }catch(Exception error){discardObservation();throw new IllegalStateException(error);}
        catch(Error error){discardObservation();throw error;}
    }
    public boolean evidenceLive(HookEvidence evidence){
        if(metadata.isEmpty()||!metadata.equals(evidence.symbols))return false;
        boolean java=metadata.stream().anyMatch(symbol->"java".equals(symbol.get("kind")));
        boolean nativeSymbols=metadata.stream().anyMatch(symbol->"native".equals(symbol.get("kind")));
        return (!java||javaBackend.live(evidence))&&(!nativeSymbols||nativeBackend.live(evidence));
    }
    public HookInvocationSession.PreparedBackend prepare(DraftCompiler.Plan plan){
        if(!evidenceLive(plan.evidence()))throw new IllegalStateException("HOOK_EVIDENCE_STALE");
        List<Map<String,Object>> java=new ArrayList<>(),nativeSymbols=new ArrayList<>();
        for(Map<String,Object> binding:plan.bindings()){
            if(!Objects.equals(plan.evidence().symbolsById().get(binding.get("id")),binding))throw new IllegalArgumentException("HOOK_BINDING_CHANGED");
            if("java".equals(binding.get("kind")))java.add(binding);else if("native".equals(binding.get("kind")))nativeSymbols.add(binding);else throw new IllegalArgumentException("HOOK_BINDING_KIND");
        }
        List<HookInvocationSession.PreparedBackend> parts=new ArrayList<>();
        if(!java.isEmpty())parts.add(javaBackend.prepare(plan,java));if(!nativeSymbols.isEmpty())parts.add(nativeBackend.prepare(plan,nativeSymbols));
        if(parts.isEmpty())throw new IllegalArgumentException("HOOK_BINDINGS_REQUIRED");
        return new HookInvocationSession.PreparedBackend(){
            private boolean published,retired;private int retirementIndex=parts.size()-1;
            public void publish(HookInvocationSession.Dispatch dispatch)throws Exception {
                if(published||retired||!evidenceLive(plan.evidence()))throw new IllegalStateException("HOOK_PUBLICATION_STALE");
                int completed=0;
                try{for(var part:parts){part.publish(dispatch);completed++;}published=true;}
                catch(HookPublicationRejected failure){if(completed!=0)throw new IllegalStateException("MIXED_PARTIAL_PUBLICATION",failure);throw failure;}
            }
            public boolean retire()throws Exception {if(!published||retired)throw new IllegalStateException("HOOK_RETIRE_STATE");
                while(retirementIndex>=0){if(!parts.get(retirementIndex).retire())return false;retirementIndex--;}
                retired=true;return true;}
            public void failStop(Throwable error){android.util.Log.e("TripoderHook","MIXED_HOOK_BACKEND_UNCERTAIN",error);Process.killProcess(Process.myPid());System.exit(73);}
            public boolean nativeSymbol(String id){return parts.stream().anyMatch(part->part.nativeSymbol(id));}
        };
    }
    public void discardObservation(){metadata=List.of();javaBackend.discard();nativeBackend.discard();}
}
