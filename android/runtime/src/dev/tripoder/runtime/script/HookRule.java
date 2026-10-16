package dev.tripoder.runtime.script;

import java.util.*;
import static dev.tripoder.runtime.script.ScriptFault.require;

/** Immutable rule after evidence/signature binding. This class does not discover or mutate methods. */
public final class HookRule {
    public final String symbol;
    public final List<String> argumentTypes;
    public final String returnType;
    public final RestrictedAst.Handler before, replace, after;
    public HookRule(String symbol,List<String> argumentTypes,String returnType,
                    RestrictedAst.Handler before,RestrictedAst.Handler replace,RestrictedAst.Handler after) {
        require(symbol!=null&&symbol.matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}"),"HOOK_SYMBOL_ID");
        require(argumentTypes.size()<=16,"HOOK_ARGUMENT_COUNT");
        Set<String> supported=Set.of("boolean","byte","short","int","long","char","float","double","java.lang.String","int32","uint32","int64","uint64");
        require(supported.containsAll(argumentTypes)&&(returnType.equals("void")||supported.contains(returnType)),"HOOK_ABI_UNSUPPORTED");
        require(replace!=null ? before==null&&after==null : before!=null||after!=null,"HOOK_CONFLICT_OR_EMPTY");
        this.symbol=symbol;this.argumentTypes=List.copyOf(argumentTypes);this.returnType=returnType;
        this.before=before;this.replace=replace;this.after=after;
    }
    public List<Object> arguments(List<Object> input) {
        require(input.size()==argumentTypes.size(),"HOOK_ARGUMENT_COUNT");List<Object> out=new ArrayList<>();
        for(int i=0;i<input.size();i++)out.add(ScriptValues.checked(argumentTypes.get(i),input.get(i)));
        return Collections.unmodifiableList(out);
    }
    /** Backend encodes script arguments with ScriptValues.abi and decodes the actual original result. */
    @FunctionalInterface public interface Original { Object call(List<Object> arguments) throws Throwable; }
    /** Original runs outside every script catch; the same Throwable identity crosses this boundary. */
    public Object execute(Map<String,Object> parameters,List<Object> incoming,Original original)throws Throwable {
        List<Object> args=arguments(incoming);
        Map<String,Object> call=new LinkedHashMap<>();call.put("args",args);call.put("params",parameters);
        if(replace!=null)return ScriptValues.checked(returnType,replace.invoke(call));
        if(before!=null) {
            Object result=before.invoke(call);require(result instanceof List,"HOOK_BEFORE_ARGUMENT_ARRAY_REQUIRED");
            @SuppressWarnings("unchecked") List<Object> transformed=(List<Object>)result;
            args=arguments(transformed);call.put("args",args);
        }
        // No handler is given this callback. There is one syntactic call site and no retry path.
        Object result=original.call(args);
        result=ScriptValues.checked(returnType,result);
        if(after!=null) {call.put("result",result);result=ScriptValues.checked(returnType,after.invoke(call));}
        return result;
    }
}
