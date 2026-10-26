package dev.tripoder.runtime;

import android.os.Process;
import dev.tripoder.runtime.script.DraftCompiler;
import dev.tripoder.runtime.script.HookEvidence;
import dev.tripoder.runtime.script.HookInvocationSession;
import dev.tripoder.runtime.script.JavaHookValues;
import dev.tripoder.runtime.script.JavaObjectScope;
import dev.tripoder.selfhook.ManagedHook;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Java part of the product backend. Native bindings remain a separate required part. */
final class RuntimeJavaHookBackend {
    private final RuntimeJavaObservation observation;
    private final Map<Method,ManagedHook.Handle> handles=new HashMap<>();
    RuntimeJavaHookBackend(RuntimeJavaObservation observation){this.observation=observation;}
    List<Map<String,Object>> observe(List<Map<String,Object>> selectors)throws Exception {
        List<Map<String,Object>> symbols=observation.observe(selectors);HashSet<Method> unique=new HashSet<>();
        try {
            for(Map<String,Object> symbol:symbols){Method method=observation.method((String)symbol.get("id"));validate(method);
                if(!unique.add(method))throw new IllegalArgumentException("HOOK_DUPLICATE_METHOD_IDENTITY");}
            return symbols;
        } catch(Exception|Error error){observation.clear();throw error;}
    }
    private void validate(Method method){
        if(method.getDeclaringClass().isInterface()||Proxy.isProxyClass(method.getDeclaringClass())||Modifier.isAbstract(method.getModifiers()))throw new IllegalArgumentException("HOOK_MANAGED_METHOD_REQUIRED");
        for(var annotation:method.getDeclaredAnnotations())if(annotation.annotationType().getName().endsWith(".CallerSensitive"))
            throw new IllegalArgumentException("HOOK_CALLER_SENSITIVE_UNSUPPORTED");
        for(Class<?> parameter:method.getParameterTypes())JavaHookValues.requireType(parameter,false);JavaHookValues.requireType(method.getReturnType(),true);
        if(ManagedHook.ownsTarget(method)&&!handles.containsKey(method))throw new SecurityException("HOOK_RUNTIME_TARGET_RESERVED");
    }
    boolean live(HookEvidence evidence){return observation.live(evidence);}
    void discard(){observation.clear();}
    HookInvocationSession.PreparedBackend prepare(DraftCompiler.Plan plan){
        return prepare(plan,plan.bindings());
    }
    HookInvocationSession.PreparedBackend prepare(DraftCompiler.Plan plan,List<Map<String,Object>> subset){
        if(!live(plan.evidence()))throw new IllegalStateException("HOOK_EVIDENCE_STALE");
        record Binding(String symbol,Method method){}List<Binding> selected=new ArrayList<>();HashSet<Method> unique=new HashSet<>();int needed=0;
        for(Map<String,Object> binding:subset) {
            if(!"java".equals(binding.get("kind")))throw new IllegalArgumentException("HOOK_JAVA_BACKEND_KIND");
            String id=(String)binding.get("id");Method method=observation.method(id);validate(method);
            if(!unique.add(method))throw new IllegalArgumentException("HOOK_DUPLICATE_METHOD_IDENTITY");
            if(!java.util.Objects.equals(plan.evidence().symbolsById().get(id),binding))throw new IllegalArgumentException("HOOK_BINDING_CHANGED");
            selected.add(new Binding(id,method));if(!handles.containsKey(method))needed++;
        }
        if(needed>ManagedHook.remainingCapacity())throw new IllegalStateException("HOOK_PROCESS_CAPACITY");
        return new HookInvocationSession.PreparedBackend(){
            private boolean published,retired;
            public void publish(HookInvocationSession.Dispatch dispatch)throws Exception {
                if(published||retired||!live(plan.evidence()))throw new IllegalStateException("HOOK_PUBLICATION_STALE");
                for(Binding binding:selected){validate(binding.method());if(!handles.containsKey(binding.method()))
                    handles.put(binding.method(),ManagedHook.prepare(binding.method(),new Method[0]));}
                for(Binding binding:selected){Method method=binding.method();Class<?>[] types=method.getParameterTypes();Class<?> result=method.getReturnType();
                    handles.get(method).enable(call->{try(JavaObjectScope scope=JavaObjectScope.open(plan.evidence().processInstanceId,plan.sourceSha256(),types,result)){
                        return JavaHookValues.encode(result,dispatch.call(binding.symbol(),JavaHookValues.decodeArguments(types,call.arguments()),
                            args->JavaHookValues.decode(result,call.original(JavaHookValues.encodeArguments(types,args)))));
                    }});}
                published=true;
            }
            public boolean retire(){if(!published||retired)throw new IllegalStateException("HOOK_RETIRE_STATE");for(Binding binding:selected)handles.get(binding.method()).disable();retired=true;return true;}
            public void failStop(Throwable error){android.util.Log.e("TripoderHook","SELF_HOOK_BACKEND_UNCERTAIN",error);Process.killProcess(Process.myPid());System.exit(71);}
            public boolean nativeSymbol(String symbol){return false;}
        };
    }
}
