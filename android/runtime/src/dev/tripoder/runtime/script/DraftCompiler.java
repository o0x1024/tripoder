package dev.tripoder.runtime.script;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static dev.tripoder.runtime.script.ScriptFault.require;
import static dev.tripoder.runtime.script.HookEvidence.*;

/** The only compilation entry accepts the original source snapshot, never a caller-supplied AST. */
public final class DraftCompiler {
    public static final String VERSION="tripoder-restricted-source-v2";
    public record Plan(String id,String sourceSha256,String compilerVersion,HookEvidence evidence,
                       Map<String,ParameterSpec> specs,Map<String,Object> parameters,List<HookRule> rules,
                       List<Map<String,Object>> bindings) {}
    private static final class RuleBuilder {
        final Map<String,Object> symbol;
        RestrictedAst.Handler before,replace,after;
        RuleBuilder(Map<String,Object> symbol){this.symbol=symbol;}
    }
    private final HookEvidence evidence;
    private final LinkedHashMap<String,RuleBuilder> rules=new LinkedHashMap<>();
    private final IdentityHashMap<Object,ParameterSpec> schemas=new IdentityHashMap<>();
    private boolean collecting;
    private final java.util.function.Function<String,Map<String,Object>> codeSnapshot;
    private final java.util.function.BiFunction<String,Object,Map<String,Object>> evidenceSnapshot;
    private final java.util.function.Function<String,Map<String,Object>> privacyAttribution;
    private final java.util.function.Supplier<Map<String,Object>> privacyConsentStage;
    private DraftCompiler(HookEvidence evidence,java.util.function.Function<String,Map<String,Object>> codeSnapshot,java.util.function.BiFunction<String,Object,Map<String,Object>> evidenceSnapshot,java.util.function.Function<String,Map<String,Object>> privacyAttribution,java.util.function.Supplier<Map<String,Object>> privacyConsentStage){this.evidence=evidence;this.codeSnapshot=codeSnapshot;this.evidenceSnapshot=evidenceSnapshot;this.privacyAttribution=privacyAttribution;this.privacyConsentStage=privacyConsentStage;}
    public static Plan compile(String source,HookEvidence evidence){
        return compile(source,evidence,id->{throw new UnsupportedOperationException("CODE_SNAPSHOT_UNSUPPORTED");});
    }
    public static Plan compile(String source,HookEvidence evidence,java.util.function.Function<String,Map<String,Object>> codeSnapshot){
        return compile(source,evidence,codeSnapshot,(tag,value)->{throw new UnsupportedOperationException("EVIDENCE_CAPTURE_UNSUPPORTED");});
    }
    public static Plan compile(String source,HookEvidence evidence,java.util.function.Function<String,Map<String,Object>> codeSnapshot,java.util.function.BiFunction<String,Object,Map<String,Object>> evidenceSnapshot){
        return compile(source,evidence,codeSnapshot,evidenceSnapshot,className->{throw new UnsupportedOperationException("PRIVACY_ATTRIBUTION_UNSUPPORTED");},()->{throw new UnsupportedOperationException("PRIVACY_CONSENT_STAGE_UNSUPPORTED");});
    }
    public static Plan compile(String source,HookEvidence evidence,java.util.function.Function<String,Map<String,Object>> codeSnapshot,java.util.function.BiFunction<String,Object,Map<String,Object>> evidenceSnapshot,java.util.function.Function<String,Map<String,Object>> privacyAttribution,java.util.function.Supplier<Map<String,Object>> privacyConsentStage){
        require(source!=null&&source.length()<=24000,"HOOK_SOURCE_SIZE");
        for(int i=0;i<source.length();i++){char c=source.charAt(i);if(Character.isHighSurrogate(c)){require(i+1<source.length()&&Character.isLowSurrogate(source.charAt(++i)),"DRAFT_SOURCE_SURROGATE");}else require(!Character.isLowSurrogate(c),"DRAFT_SOURCE_SURROGATE");}
        String snapshot=source;String sha=digest(snapshot);
        return new DraftCompiler(Objects.requireNonNull(evidence),Objects.requireNonNull(codeSnapshot),Objects.requireNonNull(evidenceSnapshot),Objects.requireNonNull(privacyAttribution),Objects.requireNonNull(privacyConsentStage)).prepare(snapshot,sha);
    }
    private Plan prepare(String source,String sha){
        List<Map<String,Object>> program=new DraftParser(source).program();DraftSyntax.check(program);
        RestrictedAst.Preparation preparation=new RestrictedAst.Preparation(Map.of("target",evidence.target,"symbols",evidence.symbolsById()));
        Map<String,Object> imports=Map.of("definePlan",RestrictedAst.capability(args->{arity(args,1,1);return args.get(0);}),"param",parameters());
        Object result=null;boolean exported=false;
        for(Map<String,Object> statement:program){String type=text(statement,"type");
            if(type.equals("ImportDeclaration")){
                require("@abox/hook".equals(object(statement.get("source")).get("value")),"DRAFT_IMPORT_SOURCE");
                for(Object item:array(statement.get("specifiers"))){Map<String,Object> spec=object(item);String imported=text(object(spec.get("imported")),"name"),local=text(object(spec.get("local")),"name");require(imports.containsKey(imported),"DRAFT_IMPORT_NAME");preparation.imported(local,imports.get(imported));}
            }else if(type.equals("ExportDefaultDeclaration")){require(!exported,"DRAFT_DUPLICATE_DEFAULT_EXPORT");exported=true;result=preparation.expression(object(statement.get("declaration")));}
            else preparation.statement(statement);
        }
        require(exported&&result instanceof Map,"DRAFT_DEFAULT_PLAN_REQUIRED");Map<String,Object> plan=object(result);String id=text(plan,"id");require(id.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,79}"),"PLAN_ID");
        Map<String,Object> target=object(plan.get("target"));for(String k:List.of("packageName","versionCode","apkSha256"))require(Objects.equals(evidence.target.get(k),target.get(k)),"TARGET_MISMATCH");
        Map<String,ParameterSpec> specs=new LinkedHashMap<>();for(var entry:object(plan.get("params")).entrySet()){
            ScriptValues.key(entry.getKey());ParameterSpec spec=schemas.get(entry.getValue());
            if(spec==null){Map<String,Object> holder=object(entry.getValue());require(holder.keySet().equals(Set.of("schema")),"PARAM_SCHEMA_FIELDS");Map<String,Object> schema=object(holder.get("schema"));require(Set.of("type","default","title","minimum","maximum","maxLength").containsAll(schema.keySet())&&schema.containsKey("default"),"PARAM_SCHEMA_FIELDS");Double length=optionalNumber(schema,"maxLength");require(length==null||length==Math.rint(length)&&length>=0&&length<=Integer.MAX_VALUE,"PARAM_SCHEMA");spec=new ParameterSpec(text(schema,"type"),schema.get("default"),optionalNumber(schema,"minimum"),optionalNumber(schema,"maximum"),length==null?null:length.intValue());}
            specs.put(entry.getKey(),spec);
        }
        Map<String,Object> values=ParameterSpec.resolve(specs,Map.of());
        collecting=true;
        try{RestrictedAst.invokeSetup(plan.get("setup"),context(),new RestrictedAst.Budget());}finally{collecting=false;}
        require(!rules.isEmpty()&&rules.size()<=128,"EMPTY_PLAN_OR_RULE_BUDGET");List<HookRule> compiled=new ArrayList<>();List<Map<String,Object>> bindings=new ArrayList<>();
        for(RuleBuilder rule:rules.values()){
            compiled.add(new HookRule(text(rule.symbol,"id"),strings(rule.symbol.get("argumentTypes")),text(rule.symbol,"returnType"),rule.before,rule.replace,rule.after));bindings.add(rule.symbol);
        }
        return new Plan(id,sha,VERSION,evidence,Collections.unmodifiableMap(specs),values,List.copyOf(compiled),List.copyOf(bindings));
    }
    private Map<String,Object> parameters(){
        Map<String,Object> out=new LinkedHashMap<>();for(String kind:List.of("string","boolean","number","integer"))out.put(kind,RestrictedAst.capability(args->{
            arity(args,1,2);Map<String,Object> options=args.size()==2?object(args.get(1)):Map.of();
            Set<String> allowed=Set.of("title","maxLength","minimum","maximum");
            require(allowed.containsAll(options.keySet()),"PARAM_SCHEMA_FIELDS");if(options.containsKey("title"))require(options.get("title") instanceof String,"PARAM_TITLE");
            Double minimum=optionalNumber(options,"minimum"),maximum=optionalNumber(options,"maximum");Double length=optionalNumber(options,"maxLength");
            require(length==null||length==Math.rint(length)&&length>=0&&length<=Integer.MAX_VALUE,"PARAM_SCHEMA");
            ParameterSpec spec=new ParameterSpec(kind,args.get(0),minimum,maximum,length==null?null:length.intValue());Map<String,Object> schema=new LinkedHashMap<>(options);schema.put("type",kind);schema.put("default",spec.defaultValue);
            Map<String,Object> holder=Collections.singletonMap("schema",Collections.unmodifiableMap(schema));schemas.put(holder,spec);return holder;
        }));return Collections.unmodifiableMap(out);
    }
    private Map<String,Object> context(){return Map.of("java",Map.of("method",RestrictedAst.capability(this::javaMethod),"objectInfo",RestrictedAst.capability(args->{objectInvocation(args);arity(args,1,1);return ScriptValues.freeze(JavaObjectScope.info(args.get(0)));}),"snapshotThread",RestrictedAst.capability(args->{objectInvocation(args);arity(args,1,1);return ScriptValues.freeze(JavaObjectSnapshots.thread(args.get(0)));}),"snapshotThrowable",RestrictedAst.capability(this::snapshotThrowable),"readField",RestrictedAst.capability(this::readField),"snapshotContainer",RestrictedAst.capability(this::snapshotContainer)),"native",Map.of("function",RestrictedAst.capability(this::nativeFunction),"captureBuffer",RestrictedAst.capability(this::captureNativeBuffer),"readCString",RestrictedAst.capability(this::readNativeCString)),"code",Map.of("snapshotDex",RestrictedAst.capability(args->{require(!collecting,"CODE_CAPTURE_OUTSIDE_INVOCATION");arity(args,1,1);String id=string(args.get(0));require(id.matches("[A-Za-z0-9_-]{1,80}"),"DEX_REFERENCE");return ScriptValues.freeze(codeSnapshot.apply(id));})),"debug",Map.of("emitEvent",RestrictedAst.capability(this::emitEvent),"captureEvidence",RestrictedAst.capability(this::captureEvidence),"captureJavaStack",RestrictedAst.capability(this::captureJavaStack),"callerModuleOffset",RestrictedAst.capability(this::callerModuleOffset)),"privacy",Map.of("attribution",RestrictedAst.capability(this::privacyAttributionOf),"consentStage",RestrictedAst.capability(args->{arity(args,0,0);return ScriptValues.freeze(privacyConsentStage.get());})),"state",Map.of("get",RestrictedAst.capability(this::stateGet),"set",RestrictedAst.capability(this::stateSet),"delete",RestrictedAst.capability(this::stateDelete),"clear",RestrictedAst.capability(this::stateClear),"keys",RestrictedAst.capability(this::stateKeys)));}
    private Object privacyAttributionOf(List<Object> args){arity(args,1,1);return ScriptValues.freeze(privacyAttribution.apply(string(args.get(0))));}
    private Object readField(List<Object> args){
        objectInvocation(args);arity(args,2,2);String name=string(args.get(1));
        return JavaObjectSnapshots.readField(args.get(0),name);
    }
    private Object snapshotContainer(List<Object> args){
        objectInvocation(args);arity(args,1,2);
        int maxEntries=16;
        if(args.size()==2&&args.get(1)!=ScriptValues.UNDEFINED){
            Object raw=args.get(1);
            require(raw instanceof Double&&(Double)raw==Math.rint((Double)raw)&&(Double)raw>=1&&(Double)raw<=64,"HOOK_SNAPSHOT_LIMIT:maxEntries");
            maxEntries=((Double)raw).intValue();
        }
        return ScriptValues.freeze(JavaObjectSnapshots.snapshotContainer(args.get(0),maxEntries));
    }
    private Object stateGet(List<Object> args){
        require(!collecting,"HOOK_STATE_OUTSIDE_INVOCATION");arity(args,1,1);
        String key=ScriptValues.key(string(args.get(0)));require(key.length()<=128,"HOOK_STATE_KEY");
        return HookInvocationSession.stateGet(key);
    }
    private Object stateSet(List<Object> args){
        require(!collecting,"HOOK_STATE_OUTSIDE_INVOCATION");arity(args,2,2);
        String key=ScriptValues.key(string(args.get(0)));require(key.length()<=128,"HOOK_STATE_KEY");
        Object raw=args.get(1)==ScriptValues.UNDEFINED?null:args.get(1);
        Object frozen=ScriptValues.freeze(raw);
        return HookInvocationSession.stateSet(key,frozen);
    }
    private Object stateDelete(List<Object> args){
        require(!collecting,"HOOK_STATE_OUTSIDE_INVOCATION");arity(args,1,1);
        String key=ScriptValues.key(string(args.get(0)));require(key.length()<=128,"HOOK_STATE_KEY");
        return HookInvocationSession.stateDelete(key);
    }
    private Object stateClear(List<Object> args){
        require(!collecting,"HOOK_STATE_OUTSIDE_INVOCATION");arity(args,0,0);
        HookInvocationSession.stateClear();
        return ScriptValues.UNDEFINED;
    }
    private Object stateKeys(List<Object> args){
        require(!collecting,"HOOK_STATE_OUTSIDE_INVOCATION");arity(args,0,0);
        return ScriptValues.freeze(HookInvocationSession.stateKeys());
    }
    private Object captureNativeBuffer(List<Object> args){
        require(!collecting,"NATIVE_BUFFER_OUTSIDE_INVOCATION");arity(args,3,3);
        int[] values=new int[3];for(int i=0;i<3;i++){Object value=args.get(i);require(value instanceof Double&&(Double)value==Math.rint((Double)value)&&(Double)value>=0&&(Double)value<=1024,"NATIVE_BUFFER_ARGUMENT");values[i]=((Double)value).intValue();}
        return ScriptValues.freeze(NativeBufferScope.capture(values[0],values[1],values[2]));
    }
    private Object readNativeCString(List<Object> args){
        require(!collecting,"NATIVE_BUFFER_OUTSIDE_INVOCATION");arity(args,1,2);
        Object ptr=args.get(0);require(ptr instanceof Double&&(Double)ptr==Math.rint((Double)ptr)&&(Double)ptr>=0,"NATIVE_BUFFER_ARGUMENT");
        int maxBytes=256;
        if(args.size()==2){Object limit=args.get(1);require(limit instanceof Double&&(Double)limit==Math.rint((Double)limit)&&(Double)limit>=1&&(Double)limit<=1024,"NATIVE_BUFFER_ARGUMENT");maxBytes=((Double)limit).intValue();}
        String result=NativeBufferScope.readCString(((Double)ptr).intValue(),maxBytes);
        return result==null?null:ScriptValues.freeze(result);
    }
    private void objectInvocation(List<Object> args){require(!collecting,"HOOK_OBJECT_OUTSIDE_INVOCATION");}
    private Object snapshotThrowable(List<Object> args){
        objectInvocation(args);arity(args,1,2);Map<String,Object> options=args.size()==2?object(args.get(1)):Map.of();require(Set.of("maxFrames","maxCauses").containsAll(options.keySet()),"HOOK_SNAPSHOT_FIELDS");
        int frames=limitOption(options,"maxFrames",16,32),causes=limitOption(options,"maxCauses",4,6);
        return ScriptValues.freeze(JavaObjectSnapshots.throwable(args.get(0),frames,causes));
    }
    private int limitOption(Map<String,Object> options,String key,int initial,int max){Object value=options.getOrDefault(key,(double)initial);require(value instanceof Double&&(Double)value==Math.rint((Double)value)&&(Double)value>=1&&(Double)value<=max,"HOOK_SNAPSHOT_LIMIT:"+key);return ((Double)value).intValue();}
    private Object captureEvidence(List<Object> args){
        require(!collecting,"HOOK_EMIT_OUTSIDE_INVOCATION");arity(args,2,2);String tag=string(args.get(0));require(tag.matches("[a-zA-Z0-9_.:-]{1,64}"),"HOOK_EVENT_TAG");
        Object snapshot=ScriptValues.freeze(args.get(1));return ScriptValues.freeze(evidenceSnapshot.apply(tag,snapshot));
    }
    private Object emitEvent(List<Object> args){
        require(!collecting,"HOOK_EMIT_OUTSIDE_INVOCATION");arity(args,1,2);
        String tag=string(args.get(0));require(tag.matches("[a-zA-Z0-9_.:-]{1,64}"),"HOOK_EVENT_TAG");
        Object payload=args.size()==2?args.get(1):null;
        if(payload==ScriptValues.UNDEFINED)payload=null;
        payload=ScriptValues.freeze(payload);
        HookInvocationSession.recordEvent(tag,payload);
        return ScriptValues.UNDEFINED;
    }
    private Object captureJavaStack(List<Object> args){
        require(!collecting,"HOOK_EMIT_OUTSIDE_INVOCATION");arity(args,0,1);
        int maxDepth=8;
        if(args.size()==1&&args.get(0)!=ScriptValues.UNDEFINED){
            Object raw=args.get(0);
            require(raw instanceof Double&&(Double)raw==Math.rint((Double)raw)&&(Double)raw>=1&&(Double)raw<=32,"HOOK_STACK_DEPTH");
            maxDepth=((Double)raw).intValue();
        }
        return ScriptValues.freeze(HookInvocationSession.captureStack(maxDepth));
    }
    private Object callerModuleOffset(List<Object> args){
        require(!collecting,"HOOK_EMIT_OUTSIDE_INVOCATION");arity(args,0,0);
        return HookInvocationSession.callerModuleOffset();
    }
    private static String normJavaType(Object t){return "byte[]".equals(t)?"[B":String.valueOf(t);}
    private static List<String> normJavaTypes(List<String> list){List<String> out=new ArrayList<>(list.size());for(String s:list)out.add(normJavaType(s));return out;}
    private Object javaMethod(List<Object> args){
        require(collecting,"SETUP_CLOSED");arity(args,3,4);require(evidence.capabilities.contains("java-hook"),"CAPABILITY_MISSING");
        String className=string(args.get(0)),method=string(args.get(1));List<String> parameters=normJavaTypes(strings(args.get(2)));Map<String,Object> options=args.size()==4?object(args.get(3)):Map.of();
        require(Set.of("classLoaderId","returnType").containsAll(options.keySet()),"HOOK_SELECTOR_OPTIONS");List<Map<String,Object>> selected=new ArrayList<>();
        for(var symbol:evidence.symbols)if("java".equals(symbol.get("kind"))&&className.equals(symbol.get("className"))&&method.equals(symbol.get("methodName"))&&parameters.equals(normJavaTypes(strings(symbol.get("argumentTypes"))))
            &&(!options.containsKey("classLoaderId")||Objects.equals(options.get("classLoaderId"),symbol.get("classLoaderId")))&&(!options.containsKey("returnType")||Objects.equals(normJavaType(options.get("returnType")),normJavaType(symbol.get("returnType")))))selected.add(symbol);
        require(!selected.isEmpty(),"TARGET_NOT_FOUND");require(selected.size()==1,"TARGET_AMBIGUOUS");return builder(selected.get(0));
    }
    private Object nativeFunction(List<Object> args){
        require(collecting,"SETUP_CLOSED");arity(args,3,3);require(evidence.capabilities.contains("native-hook"),"CAPABILITY_MISSING");
        String module=string(args.get(0)),name=string(args.get(1));Map<String,Object> signature=object(args.get(2));require(signature.keySet().equals(Set.of("argumentTypes","returnType","buildId")),"HOOK_NATIVE_SIGNATURE_FIELDS");
        List<Map<String,Object>> selected=new ArrayList<>();for(var symbol:evidence.symbols)if("native".equals(symbol.get("kind"))&&module.equals(symbol.get("moduleName"))&&name.equals(symbol.get("symbol")))selected.add(symbol);
        require(!selected.isEmpty(),"TARGET_NOT_FOUND");require(selected.size()==1,"TARGET_AMBIGUOUS");Map<String,Object> symbol=selected.get(0);
        require(!"unknown".equals(symbol.get("signatureSource")),"SIGNATURE_UNKNOWN");require(text(signature,"buildId").equals(symbol.get("buildId")),"BUILD_ID_MISMATCH");
        require(strings(signature.get("argumentTypes")).equals(symbol.get("argumentTypes"))&&Objects.equals(signature.get("returnType"),symbol.get("returnType")),"SIGNATURE_MISMATCH");return builder(symbol);
    }
    private Object builder(Map<String,Object> symbol){
        require(Boolean.TRUE.equals(symbol.get("loaded")),"HOOK_TARGET_NOT_LOADED");RuleBuilder selected=rules.computeIfAbsent(text(symbol,"id"),ignored->new RuleBuilder(symbol));Map<String,Object> builder=new LinkedHashMap<>();
        for(String stage:List.of("before","replace","after"))builder.put(stage,RestrictedAst.capability(args->{
            require(collecting,"SETUP_CLOSED");arity(args,1,1);RestrictedAst.Handler handler=RestrictedAst.capturedHandler(args.get(0));
            if(stage.equals("replace")){require(selected.replace==null&&selected.before==null&&selected.after==null,"HOOK_CONFLICT");selected.replace=handler;}
            else {require(selected.replace==null,"HOOK_CONFLICT");if(stage.equals("before")){require(selected.before==null,"HOOK_CONFLICT");selected.before=handler;}else{require(selected.after==null,"HOOK_CONFLICT");selected.after=handler;}}
            return builder;
        }));return Collections.unmodifiableMap(builder);
    }
    private static String string(Object value){require(value instanceof String,"HOOK_STRING");return (String)value;}
    private static Double optionalNumber(Map<String,Object> options,String key){if(!options.containsKey(key))return null;Object value=options.get(key);require(value instanceof Double&&Double.isFinite((Double)value),"PARAM_SCHEMA");return (Double)value;}
    private static void arity(List<?> values,int minimum,int maximum){require(values.size()>=minimum&&values.size()<=maximum,"DRAFT_CAPABILITY_ARITY");}
    public static String digest(String source){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
