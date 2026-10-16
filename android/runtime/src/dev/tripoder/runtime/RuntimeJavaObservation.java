package dev.tripoder.runtime;

import dev.tripoder.runtime.script.HookEvidence;
import dev.tripoder.runtime.script.ScriptValues;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** The Bridge's sole Java observation record, shared by legacy transport and self backend. */
final class RuntimeJavaObservation {
    private final Supplier<ClassLoader> selectedLoader;
    private final Predicate<ClassLoader> ownsLoader;
    private final BooleanSupplier ownerLive;
    private final Map<String,Object> identity;
    private final Map<String,Method> methods=new HashMap<>();
    private List<Map<String,Object>> metadata=List.of();
    @SuppressWarnings("unchecked") RuntimeJavaObservation(Supplier<ClassLoader> selectedLoader,Predicate<ClassLoader> ownsLoader,
        BooleanSupplier ownerLive,Map<String,Object> identity){
        this.selectedLoader=selectedLoader;this.ownsLoader=ownsLoader;this.ownerLive=ownerLive;
        this.identity=(Map<String,Object>)ScriptValues.freeze(identity);
    }
    Map<String,Object> identity(){return identity;}
    synchronized Method method(String id){Method m=methods.get(id);if(m==null)throw new IllegalArgumentException("HOOK_SYMBOL_STALE");return m;}
    synchronized Class<?> type(String id){return method(id).getDeclaringClass();}
    synchronized boolean nativeMethod(String id){return Modifier.isNative(method(id).getModifiers());}
    synchronized void clear(){methods.clear();metadata=List.of();}
    synchronized boolean live(HookEvidence evidence){
        List<Map<String,Object>> javaSymbols=new ArrayList<>();for(Map<String,Object> symbol:evidence.symbols)if("java".equals(symbol.get("kind")))javaSymbols.add(symbol);
        if(!ownerLive.getAsBoolean()||!evidence.target.equals(identity.get("target"))||
            !evidence.processInstanceId.equals(ScriptValues.text(identity.get("pid"))+":"+identity.get("processGeneration"))||!javaSymbols.equals(metadata))return false;
        for(Method m:methods.values())if(!ownsLoader.test(m.getDeclaringClass().getClassLoader()))return false;
        return !methods.isEmpty();
    }
    synchronized List<Map<String,Object>> observe(List<Map<String,Object>> selectors)throws Exception {inspect(new JSONArray(selectors));return metadata;}
    synchronized JSONArray inspect(JSONArray selectors)throws Exception {
        if(!ownerLive.getAsBoolean())throw new IllegalStateException("HOOK_PROCESS_STALE");
        if(selectors.length()>32)throw new IllegalArgumentException("HOOK_SELECTOR_COUNT");
        Map<String,Method> selected=new HashMap<>();IdentityHashMap<ClassLoader,String> loaders=new IdentityHashMap<>();
        List<Map<String,Object>> symbols=new ArrayList<>();JSONArray out=new JSONArray();
        ClassLoader resolution=selectedLoader.get();
        for(int i=0;i<selectors.length();i++) {
            JSONObject selector=selectors.getJSONObject(i);
            if(selector.length()!=5||!"java".equals(selector.getString("kind"))||!selector.has("id")||!selector.has("className")||!selector.has("methodName")||!selector.has("argumentTypes"))throw new IllegalArgumentException("HOOK_SELECTOR_FIELDS");
            String id=selector.getString("id"),name=selector.getString("className"),methodName=selector.getString("methodName");
            if(!id.matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}")||selected.containsKey(id))throw new IllegalArgumentException("HOOK_SYMBOL_ID");
            if(name.length()>512||methodName.length()>128||methodName.startsWith("<"))throw new IllegalArgumentException("HOOK_METHOD_NAME");
            Class<?> owner=Class.forName(name,false,resolution);ClassLoader loader=owner.getClassLoader();
            if(!ownsLoader.test(loader))throw new SecurityException("HOOK_CLASS_OUTSIDE_GUEST");
            JSONArray args=selector.getJSONArray("argumentTypes");if(args.length()>16)throw new IllegalArgumentException("HOOK_ARGUMENT_COUNT");
            Method found=null;
            for(Method method:owner.getDeclaredMethods()) {
                if(!method.getName().equals(methodName)||method.getParameterCount()!=args.length())continue;
                Class<?>[] parameters=method.getParameterTypes();boolean matches=true;
                for(int n=0;n<parameters.length;n++)matches&=parameters[n].getName().equals(args.getString(n));
                if(matches){if(found!=null)throw new IllegalArgumentException("HOOK_METHOD_AMBIGUOUS");found=method;}
            }
            if(found==null)throw new IllegalArgumentException("HOOK_METHOD_NOT_FOUND");
            if(Modifier.isAbstract(found.getModifiers()))throw new IllegalArgumentException("HOOK_ABSTRACT_METHOD");
            List<String> types=new ArrayList<>();for(Class<?> type:found.getParameterTypes())types.add(type.getName());
            Map<String,Object> symbol=Map.of("id",id,"kind","java","loaded",true,"className",name,"methodName",methodName,
                "classLoaderId",loaders.computeIfAbsent(loader,key->UUID.randomUUID().toString()),"argumentTypes",List.copyOf(types),"returnType",found.getReturnType().getName());
            selected.put(id,found);symbols.add(symbol);out.put(new JSONObject(symbol));
        }
        if(!ownerLive.getAsBoolean()||resolution!=selectedLoader.get())throw new IllegalStateException("HOOK_PROCESS_STALE");
        methods.clear();methods.putAll(selected);metadata=List.copyOf(symbols);return out;
    }
}
