package dev.tripoder.runtime.script;

import java.util.*;
import static dev.tripoder.runtime.script.ScriptFault.require;

/** Immutable observed identity. Native evidence still requires a live generation check by its backend. */
public final class HookEvidence {
    public final String id,processInstanceId;
    public final Map<String,Object> target;
    public final List<Map<String,Object>> symbols;
    public final Set<String> capabilities;
    public final Map<String,Object> wire;
    @SuppressWarnings("unchecked") public HookEvidence(Map<String,Object> input){
        wire=(Map<String,Object>)ScriptValues.freeze(input);id=text(wire,"id");processInstanceId=text(wire,"processInstanceId");
        require(Set.of("fixture","guest-agent").contains(text(wire,"source"))&&"arm64-v8a".equals(text(wire,"abi")),"HOOK_EVIDENCE_PLATFORM");
        target=object(wire.get("target"));require(text(target,"versionCode").matches("[0-9]+")&&text(target,"apkSha256").matches("[a-f0-9]{64}"),"HOOK_TARGET_IDENTITY");text(target,"packageName");
        capabilities=Set.copyOf(strings(wire.get("capabilities")));require(Set.of("java-hook","native-hook").containsAll(capabilities),"HOOK_CAPABILITY");
        List<Map<String,Object>> selected=new ArrayList<>();Set<String> ids=new HashSet<>();
        List<?> values=array(wire.get("symbols"));require(!values.isEmpty()&&values.size()<=32,"HOOK_SYMBOL_COUNT");
        for(Object item:values){Map<String,Object> symbol=object(item);String symbolId=text(symbol,"id"),kind=text(symbol,"kind");require(symbolId.matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}")&&ids.add(symbolId),"HOOK_SYMBOL_ID");
            require(symbol.get("loaded") instanceof Boolean,"HOOK_SYMBOL_LOADED");List<String> arguments=strings(symbol.get("argumentTypes"));require(arguments.size()<=16,"HOOK_ARGUMENT_COUNT");
            Set<String> types;
            if(kind.equals("java")){text(symbol,"className");text(symbol,"methodName");text(symbol,"classLoaderId");types=Set.of("boolean","byte","short","int","long","char","float","double","java.lang.String","[B","byte[]");}
            else {require(kind.equals("native"),"HOOK_SYMBOL_KIND");text(symbol,"moduleName");text(symbol,"symbol");text(symbol,"buildId");require(Set.of("debug-info","confirmed","unknown").contains(text(symbol,"signatureSource")),"HOOK_SIGNATURE_SOURCE");types=Set.of("int32","uint32","int64","uint64","float","double");}
            require((kind.equals("java")?arguments.stream().allMatch(t->JavaTypes.valid(t,false))&&JavaTypes.valid(text(symbol,"returnType"),true):types.containsAll(arguments)&&(types.contains(text(symbol,"returnType"))||"void".equals(symbol.get("returnType")))),"HOOK_ABI_UNSUPPORTED");selected.add(symbol);
        }
        symbols=List.copyOf(selected);
    }
    public Map<String,Object> symbolsById(){Map<String,Object> out=new LinkedHashMap<>();for(var s:symbols)out.put(text(s,"id"),s);return Collections.unmodifiableMap(out);}
    static String text(Map<String,Object> value,String name){Object out=value.get(name);require(out instanceof String&&!((String)out).isEmpty(),"HOOK_FIELD:"+name);return (String)out;}
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object value){require(value instanceof Map,"HOOK_OBJECT");return (Map<String,Object>)value;}
    static List<?> array(Object value){require(value instanceof List,"HOOK_ARRAY");return (List<?>)value;}
    static List<String> strings(Object value){List<String> out=new ArrayList<>();for(Object x:array(value)){require(x instanceof String,"HOOK_STRING_ARRAY");out.add((String)x);}return List.copyOf(out);}
}
