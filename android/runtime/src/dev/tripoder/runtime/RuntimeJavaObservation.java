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
                for(int n=0;n<parameters.length;n++){
                    String expected=args.getString(n),actual=parameters[n].getName();
                    matches&=actual.equals(expected)||(parameters[n]==byte[].class&&"byte[]".equals(expected));
                }
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
    synchronized List<String> searchClasses(String packagePrefix,int limit)throws Exception {
        if(!ownerLive.getAsBoolean())throw new IllegalStateException("HOOK_PROCESS_STALE");
        java.util.TreeSet<String> found=new java.util.TreeSet<>();
        for(Method m:methods.values()){
            String cls=m.getDeclaringClass().getName();
            if(cls.startsWith(packagePrefix))found.add(cls);
        }
        RuntimeEngine engine=RuntimeEngine.current;
        if(engine!=null&&engine.info!=null&&engine.info.sourceDir!=null){
            java.io.File apk=new java.io.File(engine.info.sourceDir);
            if(apk.isFile()){
                try(java.util.zip.ZipFile zip=new java.util.zip.ZipFile(apk)){
                    var entries=zip.entries();
                    while(entries.hasMoreElements()&&found.size()<limit){
                        java.util.zip.ZipEntry entry=entries.nextElement();
                        String entryName=entry.getName();
                        if(!entryName.startsWith("classes")||!entryName.endsWith(".dex")||entryName.contains("/"))continue;
                        byte[] dex=zip.getInputStream(entry).readAllBytes();
                        scanDexClasses(dex,packagePrefix,limit,found);
                    }
                }
            }
        }
        List<String> list=new ArrayList<>(found);
        return List.copyOf(list.subList(0,Math.min(limit,list.size())));
    }
    private static void scanDexClasses(byte[] dex,String prefix,int limit,java.util.Set<String> out){
        if(dex.length<112||dex[0]!='d'||dex[1]!='e'||dex[2]!='x'||dex[3]!='\n')return;
        java.nio.ByteBuffer buf=java.nio.ByteBuffer.wrap(dex).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int stringIdsSize=buf.getInt(56),stringIdsOff=buf.getInt(60);
        int typeIdsSize=buf.getInt(64),typeIdsOff=buf.getInt(68);
        int classDefsSize=buf.getInt(96),classDefsOff=buf.getInt(100);
        if(stringIdsSize<=0||typeIdsSize<=0||classDefsSize<=0)return;
        for(int i=0;i<classDefsSize&&out.size()<limit;i++){
            int defOff=classDefsOff+i*32;if(defOff+32>dex.length)break;
            int classIdx=buf.getInt(defOff);if(classIdx<0||classIdx>=typeIdsSize)continue;
            int typeOff=typeIdsOff+classIdx*4;if(typeOff+4>dex.length)break;
            int descIdx=buf.getInt(typeOff);if(descIdx<0||descIdx>=stringIdsSize)continue;
            int strIdOff=stringIdsOff+descIdx*4;if(strIdOff+4>dex.length)break;
            int dataOff=buf.getInt(strIdOff);if(dataOff<=0||dataOff>=dex.length)continue;
            int pos=dataOff;while(pos<dex.length&&(dex[pos]&0x80)!=0)pos++;pos++;
            int end=pos;while(end<dex.length&&dex[end]!=0&&end-pos<=514)end++;
            if(end<=pos+2||dex[pos]!='L'||dex[end-1]!=';')continue;
            String name=new String(dex,pos+1,end-pos-2,java.nio.charset.StandardCharsets.UTF_8).replace('/','.');
            if(name.startsWith(prefix))out.add(name);
        }
    }
    synchronized Map<String,Object> inspectClass(String className)throws Exception {
        if(!ownerLive.getAsBoolean())throw new IllegalStateException("HOOK_PROCESS_STALE");
        if(className==null||className.isEmpty()||className.length()>512)throw new IllegalArgumentException("HOOK_CLASS_NAME");
        ClassLoader resolution=selectedLoader.get();
        Class<?> owner=Class.forName(className,false,resolution);
        if(!ownsLoader.test(owner.getClassLoader()))throw new SecurityException("HOOK_CLASS_OUTSIDE_GUEST");
        List<Map<String,Object>> methodList=new ArrayList<>();
        Method[] declaredMethods=owner.getDeclaredMethods();
        for(int i=0;i<Math.min(128,declaredMethods.length);i++){
            Method m=declaredMethods[i];
            List<String> params=new ArrayList<>();for(Class<?> p:m.getParameterTypes())params.add(p.getName());
            methodList.add(Map.of("methodName",m.getName(),"argumentTypes",List.copyOf(params),"returnType",m.getReturnType().getName(),"static",Modifier.isStatic(m.getModifiers()),"native",Modifier.isNative(m.getModifiers())));
        }
        List<Map<String,Object>> fieldList=new ArrayList<>();
        java.lang.reflect.Field[] declaredFields=owner.getDeclaredFields();
        for(int i=0;i<Math.min(128,declaredFields.length);i++){
            java.lang.reflect.Field f=declaredFields[i];
            fieldList.add(Map.of("fieldName",f.getName(),"type",f.getType().getName(),"static",Modifier.isStatic(f.getModifiers())));
        }
        return (Map<String,Object>)ScriptValues.freeze(Map.of("className",owner.getName(),"methods",List.copyOf(methodList),"fields",List.copyOf(fieldList)));
    }
}
