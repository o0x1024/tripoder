package dev.tripoder.runtime.script;

import java.util.*;
import static dev.tripoder.runtime.script.ScriptFault.require;
import static dev.tripoder.runtime.script.HookEvidence.*;

/** Early binding errors are checked in every callback before a plan can be prepared. */
final class DraftSyntax {
    static void check(List<Map<String,Object>> program){Set<String> scope=new HashSet<>();for(var n:program)visit(n,scope);}
    private static void visit(Map<String,Object> n,Set<String> scope){
        String kind=text(n,"type");
        if(kind.equals("ImportDeclaration")){for(Object item:array(n.get("specifiers")))declare(object(object(item).get("local")),scope);return;}
        if(Set.of("ArrowFunctionExpression","FunctionExpression","ObjectMethod").contains(kind)){
            if(kind.equals("ObjectMethod")&&Boolean.TRUE.equals(n.get("computed")))visit(object(n.get("key")),scope);
            Set<String> local=new HashSet<>();for(Object p:array(n.get("params")))declare(object(p),local);
            Map<String,Object> body=object(n.get("body"));if("BlockStatement".equals(body.get("type")))for(Object statement:array(body.get("body")))visit(object(statement),local);else visit(body,local);return;
        }
        if(kind.equals("BlockStatement")){Set<String> local=new HashSet<>();for(Object statement:array(n.get("body")))visit(object(statement),local);return;}
        if(kind.equals("VariableDeclaration")){for(Object declaration:array(n.get("declarations"))){Map<String,Object> d=object(declaration);declare(object(d.get("id")),scope);visit(object(d.get("init")),scope);}return;}
        for(Object value:n.values())children(value,scope);
    }
    private static void children(Object value,Set<String> scope){if(value instanceof Map&&((Map<?,?>)value).containsKey("type"))visit(object(value),scope);else if(value instanceof List)for(Object child:(List<?>)value)children(child,scope);}
    private static void declare(Map<String,Object> pattern,Set<String> scope){
        switch(text(pattern,"type")){
            case "Identifier":String name=text(pattern,"name");require(!name.equals("eval")&&!name.equals("arguments")&&scope.add(name),"DRAFT_DUPLICATE_OR_STRICT_BINDING");return;
            case "AssignmentPattern":declare(object(pattern.get("left")),scope);visit(object(pattern.get("right")),scope);return;
            case "ArrayPattern":for(Object item:array(pattern.get("elements")))if(item!=null)declare(object(item),scope);return;
            case "ObjectPattern":for(Object item:array(pattern.get("properties"))){Map<String,Object> p=object(item);declare(object(p.get("value")),scope);if(Boolean.TRUE.equals(p.get("computed")))visit(object(p.get("key")),scope);}return;
            default:throw new ScriptFault("DRAFT_BINDING");
        }
    }
}
