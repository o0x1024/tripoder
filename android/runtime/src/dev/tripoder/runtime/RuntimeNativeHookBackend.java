package dev.tripoder.runtime;
import android.os.Process;
import dev.tripoder.runtime.script.DraftCompiler;
import dev.tripoder.runtime.script.HookEvidence;
import dev.tripoder.runtime.script.HookInvocationSession;
import dev.tripoder.runtime.script.NativeHookValues;
import dev.tripoder.runtime.script.NativeBufferScope;
import dev.tripoder.runtime.script.HookPublicationRejected;
import dev.tripoder.selfhook.NativeHook;
import java.util.*;
/** Native export part of the product backend. Every activation captures its
 * immutable session dispatch; the core retains JNI/code/CFI lifetime roots. */
final class RuntimeNativeHookBackend {
    private final RuntimeNativeObservation observation;
    RuntimeNativeHookBackend(RuntimeNativeObservation observation){this.observation=observation;}
    List<Map<String,Object>> observe(List<Map<String,Object>> selectors)throws Exception{return observation.observe(selectors);}
    boolean live(HookEvidence evidence){return observation.live(evidence);}
    void discard(){observation.clear();}
    HookInvocationSession.PreparedBackend prepare(DraftCompiler.Plan plan){return prepare(plan,plan.bindings());}
    HookInvocationSession.PreparedBackend prepare(DraftCompiler.Plan plan,List<Map<String,Object>> subset){
        if(!live(plan.evidence()))throw new IllegalStateException("HOOK_NATIVE_EVIDENCE_STALE");
        record Binding(String id,RuntimeNativeObservation.Target target,List<String> arguments,String result){}
        List<Binding> bindings=new ArrayList<>();Set<Long> addresses=new HashSet<>();
        for(Map<String,Object> binding:subset){
            if(!"native".equals(binding.get("kind")))throw new IllegalArgumentException("HOOK_NATIVE_BACKEND_KIND");String id=(String)binding.get("id");
            RuntimeNativeObservation.Target target=observation.target(id);
            if(!target.symbol().equals(binding)||!Objects.equals(plan.evidence().symbolsById().get(id),binding)||!addresses.add(Math.addExact(target.base(),target.rva())))throw new IllegalArgumentException("HOOK_NATIVE_BINDING_CHANGED_OR_ALIAS");
            @SuppressWarnings("unchecked") List<String> types=(List<String>)binding.get("argumentTypes");bindings.add(new Binding(id,target,List.copyOf(types),(String)binding.get("returnType")));
        }
        if(bindings.isEmpty())throw new IllegalArgumentException("HOOK_NATIVE_BINDINGS_REQUIRED");
        return new HookInvocationSession.PreparedBackend(){
            private NativeHook.Handle[] handles;private boolean published,retired;
            public void publish(HookInvocationSession.Dispatch dispatch)throws Exception {
                if(published||retired)throw new IllegalStateException("HOOK_NATIVE_PUBLICATION_STATE");
                if(!live(plan.evidence()))throw new HookPublicationRejected(new IllegalStateException("HOOK_NATIVE_PUBLICATION_STALE"));
                List<NativeHook.Handle> prepared=new ArrayList<>();
                try {
                for(Binding binding:bindings){var target=binding.target();int[] types=binding.arguments().stream().mapToInt(type->NativeHookValues.type(type,false)).toArray();
                    prepared.add(NativeHook.prepare(target.library().index(),target.generation(),target.rva(),target.library().path(),NativeHookValues.type(binding.result(),true),types,call->{
                        try(var scope=new NativeBufferScope(binding.id(),binding.arguments(),binding.result(),call::snapshotArgument)){
                        int[] errorNumber={call.incomingErrno()};
                        Object value=dispatch.call(binding.id(),NativeHookValues.decodeArguments(binding.arguments(),call.arguments()),arguments->{
                            NativeHook.Result original=call.original(NativeHookValues.encodeArguments(binding.arguments(),arguments),errorNumber[0]);errorNumber[0]=original.errorNumber;
                            Object decoded=NativeHookValues.decode(binding.result(),original.bits);scope.complete(arguments,decoded);return decoded;
                        });
                        return new NativeHook.Result(NativeHookValues.encode(binding.result(),value),errorNumber[0]);
                        }
                    }));
                }
                handles=prepared.toArray(NativeHook.Handle[]::new);NativeHook.publish(handles);published=true;
                } catch(Exception failure) {
                    // Native returns only before mutation; any partial commit
                    // exits in the core. Cancel exactly the handles we own.
                    if(!prepared.isEmpty())NativeHook.retire(prepared.toArray(NativeHook.Handle[]::new));
                    retired=true;throw new HookPublicationRejected(failure);
                }
            }
            public boolean retire(){if(!published||retired)throw new IllegalStateException("HOOK_NATIVE_RETIRE_STATE");
                try{NativeHook.retire(handles);retired=true;return true;}catch(NativeHook.Busy busy){return false;}}
            public void failStop(Throwable error){android.util.Log.e("TripoderHook","NATIVE_HOOK_BACKEND_UNCERTAIN",error);Process.killProcess(Process.myPid());System.exit(72);}
            public boolean nativeSymbol(String id){return bindings.stream().anyMatch(binding->binding.id().equals(id));}
        };
    }
}
