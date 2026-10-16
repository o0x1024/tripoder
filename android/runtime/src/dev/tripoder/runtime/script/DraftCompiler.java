package dev.tripoder.runtime.script;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static dev.tripoder.runtime.script.ScriptFault.require;
import static dev.tripoder.runtime.script.HookEvidence.*;

/** The only compilation entry accepts the original source snapshot, never a caller-supplied AST. */
public final class DraftCompiler {
    public static final String VERSION="tripoder-restricted-source-v1";
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
    private DraftCompiler(HookEvidence evidence){this.evidence=evidence;}
    public static Plan compile(String source,HookEvidence evidence){
        require(source!=null&&source.length()<=24000,"HOOK_SOURCE_SIZE");
        for(int i=0;i<source.length();i++){char c=source.charAt(i);if(Character.isHighSurrogate(c)){require(i+1<source.length()&&Character.isLowSurrogate(source.charAt(++i)),"DRAFT_SOURCE_SURROGATE");}else require(!Character.isLowSurrogate(c),"DRAFT_SOURCE_SURROGATE");}
        String snapshot=source;String sha=digest(snapshot);
        return new DraftCompiler(Objects.requireNonNull(evidence)).prepare(snapshot,sha);
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
        require(!rules.isEmpty()&&rules.size()<=32,"EMPTY_PLAN_OR_RULE_BUDGET");List<HookRule> compiled=new ArrayList<>();List<Map<String,Object>> bindings=new ArrayList<>();
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
    private Map<String,Object> context(){return Map.of("java",Map.of("method",RestrictedAst.capability(this::javaMethod)),"native",Map.of("function",RestrictedAst.capability(this::nativeFunction)));}
    private Object javaMethod(List<Object> args){
        require(collecting,"SETUP_CLOSED");arity(args,3,4);require(evidence.capabilities.contains("java-hook"),"CAPABILITY_MISSING");
        String className=string(args.get(0)),method=string(args.get(1));List<String> parameters=strings(args.get(2));Map<String,Object> options=args.size()==4?object(args.get(3)):Map.of();
        require(Set.of("classLoaderId","returnType").containsAll(options.keySet()),"HOOK_SELECTOR_OPTIONS");List<Map<String,Object>> selected=new ArrayList<>();
        for(var symbol:evidence.symbols)if("java".equals(symbol.get("kind"))&&className.equals(symbol.get("className"))&&method.equals(symbol.get("methodName"))&&parameters.equals(symbol.get("argumentTypes"))
            &&(!options.containsKey("classLoaderId")||Objects.equals(options.get("classLoaderId"),symbol.get("classLoaderId")))&&(!options.containsKey("returnType")||Objects.equals(options.get("returnType"),symbol.get("returnType"))))selected.add(symbol);
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
