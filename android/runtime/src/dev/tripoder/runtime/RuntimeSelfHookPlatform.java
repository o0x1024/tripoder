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
    private final RuntimeNativeObservation nativeObservation;
    private List<Map<String,Object>> metadata=List.of();
    @SuppressWarnings("unchecked") RuntimeSelfHookPlatform(Map<String,Object> identity,RuntimeJavaObservation javaObservation,RuntimeNativeObservation nativeObservation){
        this.identity=(Map<String,Object>)ScriptValues.freeze(identity);this.javaBackend=new RuntimeJavaHookBackend(javaObservation);
        this.nativeBackend=new RuntimeNativeHookBackend(nativeObservation);this.nativeObservation=nativeObservation;
    }
    public Map<String,Object> identity(){return identity;}
    public Set<String> capabilities(){return Set.of("java-hook","native-hook");}
    public List<Map<String,Object>> modules(){return nativeObservation.modules();}
    public List<Map<String,Object>> moduleEvents(){return nativeObservation.moduleEvents();}
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
