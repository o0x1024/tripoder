package dev.tripoder.runtime;
import dev.tripoder.runtime.script.HookEvidence;
import dev.tripoder.runtime.script.ScriptValues;
import dev.tripoder.runtime.script.NativeHookValues;
import dev.tripoder.selfhook.NativeModules;
import dev.tripoder.runtime.RuntimeNativeLibraryCatalog.Artifact;
import java.io.File;
import java.util.*;
import java.util.function.BooleanSupplier;
/** The artifact catalog owns the allowed files; the fixed linker owns live
 * identity. No global symbol search or dlopen pin is used for observation. */
final class RuntimeNativeObservation {
    record Library(int index,String name,String path,String buildId,long size,Map<String,Object> metadata){}
    record Target(Library library,long generation,long base,long rva,Map<String,Object> symbol){}
    private final RuntimeNativeLibraryCatalog catalog;private final BooleanSupplier ownerLive;private final Map<String,Object> identity;
    private final Map<String,Artifact> artifacts;private final Map<String,Target> targets=new HashMap<>();
    private List<Map<String,Object>> metadata=List.of();
    @SuppressWarnings("unchecked") RuntimeNativeObservation(RuntimeNativeLibraryCatalog catalog,BooleanSupplier ownerLive,Map<String,Object> identity){
        catalog.requireReady();this.catalog=catalog;this.artifacts=catalog.artifacts();
        this.ownerLive=ownerLive;this.identity=(Map<String,Object>)ScriptValues.freeze(identity);
    }
    /** Loading an allowed JNI library and granting Native Hook authority are
     * different boundaries. Every selected target still requires the complete
     * original GNU Build ID, file digest, canonical path and live ELF checks. */
    private Library inspect(Artifact artifact)throws Exception{
        Map<String,Object> info=immutable(catalog.inspect(artifact.name()));String path=text(info,"path"),buildId=text(info,"buildId");
        if(!artifact.path().equals(path)||!artifact.name().equals(info.get("moduleName"))||!text(info,"sha256").matches("[a-f0-9]{64}")||!buildId.matches("(?:[a-f0-9]{2}){4,64}"))throw new SecurityException("HOOK_MODULE_ARTIFACT_IDENTITY");
        Object rawSize=info.get("virtualSize");if(!(rawSize instanceof Number))throw new IllegalArgumentException("HOOK_MODULE_SIZE");
        long size=((Number)rawSize).longValue();if(size<=0||size>Integer.MAX_VALUE||((Number)rawSize).doubleValue()!=size)throw new IllegalArgumentException("HOOK_MODULE_SIZE");
        return new Library(artifact.index(),artifact.name(),path,buildId,size,info);
    }
    synchronized Target target(String id){Target value=targets.get(id);if(value==null)throw new IllegalArgumentException("HOOK_NATIVE_SYMBOL_STALE");return value;}
    synchronized void clear(){targets.clear();metadata=List.of();}
    synchronized List<Map<String,Object>> observe(List<Map<String,Object>> selectors)throws Exception {
        clear();
        if(!ownerLive.getAsBoolean()||selectors.size()>128)throw new IllegalStateException("HOOK_NATIVE_OBSERVATION_STATE");
        Map<String,Target> selected=new HashMap<>();List<Map<String,Object>> symbols=new ArrayList<>();Set<Long> addresses=new HashSet<>();
        for(Map<String,Object> selector:selectors){
            if(!selector.keySet().equals(Set.of("id","kind","moduleName","symbol","argumentTypes","returnType","buildId","signatureSource"))||!"native".equals(selector.get("kind"))||!"confirmed".equals(selector.get("signatureSource")))throw new IllegalArgumentException("HOOK_NATIVE_SELECTOR_FIELDS");
            String id=text(selector,"id"),module=text(selector,"moduleName"),name=text(selector,"symbol");
            if(!id.matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}")||selected.containsKey(id)||name.length()>256)throw new IllegalArgumentException("HOOK_NATIVE_SELECTOR_IDENTITY");
            List<String> types=strings(selector.get("argumentTypes"));if(types.size()>16)throw new IllegalArgumentException("HOOK_NATIVE_ARGUMENT_COUNT");
            for(String type:types)NativeHookValues.type(type,false);NativeHookValues.type(text(selector,"returnType"),true);
            Artifact artifact=artifacts.get(module);if(artifact==null)throw new SecurityException("HOOK_MODULE_OUTSIDE_GUEST");
            NativeModules.Snapshot live=NativeModules.snapshot(artifact.index());if(!live.live||live.ambiguous)throw new IllegalStateException("HOOK_MODULE_NOT_UNIQUELY_LOADED");
            Library library=inspect(artifact);if(!library.buildId().equals(selector.get("buildId")))throw new IllegalArgumentException("HOOK_MODULE_BUILD_ID_STALE");
            long rva=RuntimeElfExports.address(new File(library.path()),name);
            if(rva<=0||rva>=library.size()||(rva&3)!=0||!NativeModules.attestExport(library.index(),live.generation,rva,library.buildId()))throw new IllegalArgumentException("HOOK_NATIVE_EXPORT_NOT_ADMITTED");
            if(!addresses.add(Math.addExact(live.base,rva)))throw new IllegalArgumentException("HOOK_NATIVE_ADDRESS_ALIAS");
            Map<String,Object> symbol=new LinkedHashMap<>(selector);symbol.put("loaded",true);symbol=immutable(symbol);
            selected.put(id,new Target(library,live.generation,live.base,rva,symbol));symbols.add(symbol);
        }
        if(!ownerLive.getAsBoolean())throw new IllegalStateException("HOOK_PROCESS_STALE");
        for(Target target:selected.values())if(!live(target))throw new IllegalStateException("HOOK_MODULE_GENERATION_STALE");
        targets.clear();targets.putAll(selected);metadata=List.copyOf(symbols);return metadata;
    }
    synchronized boolean live(HookEvidence evidence){
        if(!ownerLive.getAsBoolean()||!evidence.target.equals(identity.get("target"))||!evidence.processInstanceId.equals(ScriptValues.text(identity.get("pid"))+":"+identity.get("processGeneration")))return false;
        List<Map<String,Object>> nativeSymbols=new ArrayList<>();for(Map<String,Object> symbol:evidence.symbols)if("native".equals(symbol.get("kind")))nativeSymbols.add(symbol);
        if(!nativeSymbols.equals(metadata))return false;
        try {Set<Library> verified=new HashSet<>();for(Target target:targets.values()){
            if(verified.add(target.library()))verify(target.library());if(!live(target))return false;
        }return true;}catch(Exception error){return false;}
    }
    private boolean live(Target target){
        NativeModules.Snapshot now=NativeModules.snapshot(target.library().index());
        return now.live&&!now.ambiguous&&now.generation==target.generation()&&now.base==target.base()
            &&NativeModules.attestExport(target.library().index(),target.generation(),target.rva(),target.library().buildId());
    }
    private void verify(Library library)throws Exception {if(!library.metadata().equals(immutable(catalog.inspect(library.name()))))throw new SecurityException("HOOK_MODULE_ARTIFACT_CHANGED");}
    synchronized List<Map<String,Object>> modules(){
        List<Map<String,Object>> out=new ArrayList<>();for(Artifact artifact:artifacts.values()){
            NativeModules.Snapshot now=NativeModules.snapshot(artifact.index());if(!now.live)continue;
            Library library;
            try{library=inspect(artifact);}catch(Exception error){
                out.add(Map.of("moduleName",artifact.name(),"path",artifact.path(),"state","UNAVAILABLE","reason",String.valueOf(error).substring(0,Math.min(500,String.valueOf(error).length())),"moduleGeneration",(double)now.generation));continue;
            }
            Map<String,Object> item=new LinkedHashMap<>(library.metadata());
            boolean available=!now.ambiguous&&NativeModules.attest(library.index(),now.generation,0,library.buildId());
            item.put("state",available?"AVAILABLE":"UNAVAILABLE");item.put("moduleGeneration",(double)now.generation);item.put("base","0x"+Long.toHexString(now.base));item.put("size",(double)library.size());
            item.put("activeReferences",(double)now.references);
            if(!available)item.put("reason",now.ambiguous?"HOOK_MODULE_NOT_UNIQUELY_LOADED":"HOOK_LIVE_BUILD_ID_MISMATCH");out.add(immutable(item));
        }return List.copyOf(out);
    }
    synchronized List<Map<String,Object>> moduleEvents(){
        long[] raw=NativeModules.events();List<Artifact> ordered=new ArrayList<>(artifacts.values());List<Map<String,Object>> out=new ArrayList<>();
        for(int i=0;i<raw.length;i+=5){
            int index=Math.toIntExact(raw[i+1]);if(index<0||index>=ordered.size())throw new IllegalStateException("HOOK_MODULE_EVENT_OWNER");
            String event=switch((int)raw[i+4]){case 1->"added";case 2->"removed";case 3->"ambiguous";case 4->"unique";default->throw new IllegalStateException("HOOK_MODULE_EVENT_KIND");};
            Artifact library=ordered.get(index);out.add(Map.of("event",event,"moduleName",library.name(),"path",library.path(),"base","0x"+Long.toHexString(raw[i+3]),"generation",(double)raw[i+2],"sequence",Long.toUnsignedString(raw[i])));
        }return List.copyOf(out);
    }
    synchronized List<Map<String,Object>> searchExports(String moduleName,String symbolPrefix,int limit)throws Exception {
        if(!ownerLive.getAsBoolean())throw new IllegalStateException("HOOK_PROCESS_STALE");
        Artifact artifact=artifacts.get(moduleName);if(artifact==null)throw new SecurityException("HOOK_MODULE_OUTSIDE_GUEST");
        Library library=inspect(artifact);
        List<Map<String,Object>> raw=RuntimeElfExports.search(new File(library.path()),symbolPrefix,limit);
        List<Map<String,Object>> out=new ArrayList<>(raw.size());
        for(Map<String,Object> item:raw){
            Map<String,Object> copy=new LinkedHashMap<>(item);copy.put("moduleName",moduleName);copy.put("buildId",library.buildId());out.add(immutable(copy));
        }
        return List.copyOf(out);
    }
    private static String text(Map<String,Object> value,String key){Object raw=value.get(key);if(!(raw instanceof String)||((String)raw).isEmpty())throw new IllegalArgumentException("HOOK_NATIVE_FIELD:"+key);return (String)raw;}
    private static List<String> strings(Object input){if(!(input instanceof List<?>))throw new IllegalArgumentException("HOOK_NATIVE_TYPES");List<String> values=new ArrayList<>();for(Object value:(List<?>)input){if(!(value instanceof String))throw new IllegalArgumentException("HOOK_NATIVE_TYPE");values.add((String)value);}return List.copyOf(values);}
    @SuppressWarnings("unchecked") private static Map<String,Object> immutable(Map<String,Object> value){return (Map<String,Object>)ScriptValues.freeze(value);}
}
