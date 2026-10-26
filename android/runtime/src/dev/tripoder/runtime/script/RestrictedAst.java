package dev.tripoder.runtime.script;

import java.math.BigInteger;
import java.util.*;
import static dev.tripoder.runtime.script.ScriptFault.require;
import static dev.tripoder.runtime.script.ScriptValues.*;

/** Interpreter for inert Babel-shaped handler ASTs. Parsing TypeScript is a separate, required boundary. */
public final class RestrictedAst {
    private static final Set<String> ALLOWED = Set.of("Identifier", "StringLiteral", "NumericLiteral", "BigIntLiteral",
        "BooleanLiteral", "NullLiteral", "ArrayExpression", "ObjectExpression", "ObjectProperty", "ObjectMethod",
        "ArrowFunctionExpression", "FunctionExpression", "MemberExpression", "CallExpression", "ConditionalExpression",
        "LogicalExpression", "UnaryExpression", "BinaryExpression", "TemplateLiteral", "TemplateElement",
        "TSAsExpression", "TSTypeAssertion", "TSNonNullExpression", "TSSatisfiesExpression", "BlockStatement",
        "ExpressionStatement", "ReturnStatement", "IfStatement", "EmptyStatement", "VariableDeclaration",
        "VariableDeclarator", "ObjectPattern", "ArrayPattern", "AssignmentPattern");
    private interface Callable { Object call(List<Object> args, Budget budget); }
    @FunctionalInterface interface Capability {Object call(List<Object> args);}
    static Object capability(Capability operation){return (Callable)(args,budget)->operation.call(args);}
    static Handler capturedHandler(Object function){require(function instanceof Callable,"DRAFT_HANDLER_FUNCTION");return new Handler((Callable)function);}
    static Object invokeSetup(Object function,Object context,Budget budget){require(function instanceof Callable,"DRAFT_SETUP_FUNCTION");return ((Callable)function).call(Collections.singletonList(context),budget);}
    /** Package-private capabilities are supplied only by the in-process plan compiler. */
    static final class Preparation {
        final Map<String,Object> scope=builtins();
        final Budget budget=new Budget();
        Preparation(Map<String,Object> globals){
            @SuppressWarnings("unchecked") Map<String,Object> safe=(Map<String,Object>)freeze(globals);
            for(var e:safe.entrySet()){require(!scope.containsKey(e.getKey()),"DRAFT_GLOBAL_CONFLICT");scope.put(e.getKey(),e.getValue());}
        }
        void imported(String name,Object value){key(name);require(!scope.containsKey(name),"DRAFT_DUPLICATE_BINDING");scope.put(name,value);}
        Object expression(Map<String,Object> expression){return eval(checked(expression),scope,budget);}
        void statement(Map<String,Object> statement){require(statements(Collections.singletonList(checked(statement)),scope,budget)==null,"DRAFT_TOP_LEVEL_RETURN");}
        @SuppressWarnings("unchecked") private Map<String,Object> checked(Map<String,Object> value){return (Map<String,Object>)copyAst(value,new int[]{0},0,new IdentityHashMap<>());}
    }
    public static final class Budget {
        private int remaining = 3000, depth;
        private void tick() { require(--remaining >= 0, "DRAFT_EXECUTION_BUDGET"); }
        private void enter() { require(++depth < 64, "DRAFT_CALL_DEPTH"); }
        private void leave() { depth--; }
    }
    public static final class Handler {
        private final Callable function;
        private Handler(Callable function) { this.function = function; }
        public Object invoke(Map<String,Object> call) { return invoke(call, new Budget()); }
        Object invoke(Map<String,Object> call, Budget budget) {
            Object input = ScriptValues.freezeInvocation(call);
            return ScriptValues.freezeInvocation(function.call(Collections.singletonList(input), budget));
        }
    }
    private static final class Returned {
        final Object value;
        Returned(Object value) { this.value = value; }
    }
    /** Only handler functions are accepted; globals are inert values, copied before compilation. */
    public static Handler compile(Map<String,Object> input, Map<String,Object> globals) {
        @SuppressWarnings("unchecked") Map<String,Object> ast = (Map<String,Object>)copyAst(input, new int[]{0}, 0, new IdentityHashMap<>());
        require(Set.of("ArrowFunctionExpression", "FunctionExpression").contains(type(ast)), "DRAFT_HANDLER_FUNCTION");
        @SuppressWarnings("unchecked") Map<String,Object> safe = (Map<String,Object>)freeze(globals);
        Map<String,Object> scope = builtins();
        for (var e : safe.entrySet()) {
            require(!scope.containsKey(e.getKey()), "DRAFT_GLOBAL_CONFLICT"); scope.put(e.getKey(), e.getValue());
        }
        return new Handler(function(ast, scope));
    }
    private static Object copyAst(Object value, int[] count, int depth, IdentityHashMap<Object,Boolean> path) {
        require(++count[0] <= 32000 && depth < 80, "DRAFT_AST_BUDGET");
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof BigInteger) return limit(value);
        if (value instanceof Number) return limit(((Number)value).doubleValue());
        require(value instanceof Map || value instanceof List, "DRAFT_AST_VALUE");
        require(path.put(value, Boolean.TRUE) == null, "DRAFT_AST_CYCLE");
        try {
            if (value instanceof List) {
                require(((List<?>)value).size() <= 8000, "DRAFT_AST_BUDGET");
                List<Object> out = new ArrayList<>();
                for (Object child : (List<?>)value) out.add(copyAst(child, count, depth + 1, path));
                return Collections.unmodifiableList(out);
            }
            Map<String,Object> out = new LinkedHashMap<>();
            require(((Map<?,?>)value).size() <= 64, "DRAFT_AST_BUDGET");
            for (var e : ((Map<?,?>)value).entrySet()) {
                require(e.getKey() instanceof String, "DRAFT_AST_KEY");
                String key = (String)e.getKey();
                require(!Set.of("loc", "extra", "comments", "typeAnnotation", "returnType", "typeParameters", "typeArguments").contains(key), "DRAFT_AST_METADATA_NOT_STRIPPED");
                out.put(key, copyAst(e.getValue(), count, depth + 1, path));
            }
            if (out.containsKey("type")) {
                String kind = type(out); require(ALLOWED.contains(kind), "DRAFT_UNSUPPORTED_SYNTAX:" + kind);
                require(!Boolean.TRUE.equals(out.get("async")) && !Boolean.TRUE.equals(out.get("generator")), "DRAFT_ASYNC_GENERATOR");
                if (kind.equals("Identifier")) key(out.get("name"));
                if (kind.equals("VariableDeclaration")) require("const".equals(out.get("kind")), "DRAFT_CONST_REQUIRED");
                if (kind.equals("ObjectMethod")) require("method".equals(out.get("kind")), "DRAFT_ACCESSOR_FORBIDDEN");
            }
            return Collections.unmodifiableMap(out);
        } finally { path.remove(value); }
    }
    private static String type(Map<String,Object> node) { return string(node.get("type")); }
    private static String string(Object value) { require(value instanceof String, "DRAFT_STRING_REQUIRED"); return (String)value; }
    @SuppressWarnings("unchecked") private static Map<String,Object> node(Object value) {
        require(value instanceof Map && ((Map<?,?>)value).containsKey("type"), "DRAFT_NODE_REQUIRED"); return (Map<String,Object>)value;
    }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) {
        require(value instanceof List, "DRAFT_LIST_REQUIRED"); return (List<Object>)value;
    }
    private static Callable function(Map<String,Object> n, Map<String,Object> closure) {
        require(n.get("id") == null, "DRAFT_ANONYMOUS_FUNCTION");
        // Scope is interpreter-owned; retain lexical bindings added later in the same const-only block.
        // Every invocation still gets its own local map and no mutable Java object enters this scope.
        Map<String,Object> captured = closure;
        return (args, budget) -> {
            budget.enter();
            try {
                Map<String,Object> local = new LinkedHashMap<>(captured), parameters = new LinkedHashMap<>();
                List<Object> patterns = list(n.get("params")); require(patterns.size() <= 64, "DRAFT_ARGUMENT_COUNT");
                for (int i=0;i<patterns.size();i++) bind(node(patterns.get(i)), i<args.size()?args.get(i):UNDEFINED, parameters, budget);
                local.putAll(parameters);
                Map<String,Object> body = node(n.get("body"));
                if (!type(body).equals("BlockStatement")) return limit(eval(body, local, budget));
                Returned r = statements(list(body.get("body")), local, budget);
                return r == null ? UNDEFINED : r.value;
            } finally { budget.leave(); }
        };
    }
    private static void bind(Map<String,Object> n, Object value, Map<String,Object> scope, Budget b) {
        b.tick();
        switch(type(n)) {
            case "Identifier": {
                String name = key(n.get("name")); require(!scope.containsKey(name), "DRAFT_DUPLICATE_BINDING"); scope.put(name,value); return;
            }
            case "AssignmentPattern": bind(node(n.get("left")),value == UNDEFINED ? eval(node(n.get("right")),scope,b):value,scope,b); return;
            case "ArrayPattern": {
                List<Object> values = list(value), patterns = list(n.get("elements"));
                for(int i=0;i<patterns.size();i++) if(patterns.get(i)!=null) bind(node(patterns.get(i)),i<values.size()?values.get(i):UNDEFINED,scope,b);
                return;
            }
            case "ObjectPattern":
                for(Object p:list(n.get("properties"))) { Map<String,Object> prop=node(p); bind(node(prop.get("value")),get(value,property(prop,scope,b)),scope,b); } return;
            default: throw new ScriptFault("DRAFT_BINDING");
        }
    }
    private static String property(Map<String,Object> n, Map<String,Object> scope, Budget b) {
        Map<String,Object> k=node(n.get("key"));
        return key(Boolean.TRUE.equals(n.get("computed")) ? eval(k,scope,b) : k.containsKey("name")?k.get("name"):k.get("value"));
    }
    private static Object eval(Map<String,Object> n, Map<String,Object> s, Budget b) {
        b.tick(); String t=type(n);
        switch(t) {
            case "Identifier": { String name=key(n.get("name")); require(s.containsKey(name),"DRAFT_UNKNOWN_IDENTIFIER:"+name); return s.get(name); }
            case "StringLiteral": require(n.get("value") instanceof String,"DRAFT_LITERAL"); return limit(n.get("value"));
            case "BooleanLiteral": require(n.get("value") instanceof Boolean,"DRAFT_LITERAL"); return n.get("value");
            case "NumericLiteral": require(n.get("value") instanceof Double,"DRAFT_LITERAL"); return limit(n.get("value"));
            case "BigIntLiteral": return bigint(n.get("value"));
            case "NullLiteral": return null;
            case "TSAsExpression": case "TSTypeAssertion": case "TSNonNullExpression": case "TSSatisfiesExpression": return eval(node(n.get("expression")),s,b);
            case "ArrowFunctionExpression": case "FunctionExpression": return function(n,s);
            case "ArrayExpression": {
                List<Object> out=new ArrayList<>(); require(list(n.get("elements")).size()<=64,"DRAFT_ARRAY_SIZE");
                for(Object x:list(n.get("elements"))) { require(x!=null,"DRAFT_ARRAY_HOLE"); out.add(eval(node(x),s,b)); } return Collections.unmodifiableList(out);
            }
            case "ObjectExpression": {
                Map<String,Object> out=new LinkedHashMap<>(); require(list(n.get("properties")).size()<=64,"DRAFT_OBJECT_SIZE");
                for(Object x:list(n.get("properties"))) { Map<String,Object> p=node(x); String k=property(p,s,b);
                    out.put(k,type(p).equals("ObjectMethod")?function(p,s):eval(node(p.get("value")),s,b)); } return Collections.unmodifiableMap(out);
            }
            case "MemberExpression": return get(eval(node(n.get("object")),s,b),Boolean.TRUE.equals(n.get("computed"))?eval(node(n.get("property")),s,b):node(n.get("property")).get("name"));
            case "CallExpression": {
                Object callee=eval(node(n.get("callee")),s,b); require(callee instanceof Callable,"DRAFT_UNAVAILABLE_FUNCTION");
                List<Object> args=new ArrayList<>(); require(list(n.get("arguments")).size()<=64,"DRAFT_ARGUMENT_COUNT");
                for(Object a:list(n.get("arguments")))args.add(eval(node(a),s,b));
                return limit(((Callable)callee).call(Collections.unmodifiableList(args),b));
            }
            case "ConditionalExpression": return eval(node(n.get(truth(eval(node(n.get("test")),s,b))?"consequent":"alternate")),s,b);
            case "LogicalExpression": {
                Object left=eval(node(n.get("left")),s,b); String op=string(n.get("operator"));
                if(op.equals("&&"))return truth(left)?eval(node(n.get("right")),s,b):left;
                if(op.equals("||"))return truth(left)?left:eval(node(n.get("right")),s,b);
                if(op.equals("??"))return left==null||left==UNDEFINED?eval(node(n.get("right")),s,b):left;
                throw new ScriptFault("DRAFT_LOGICAL_OPERATOR");
            }
            case "UnaryExpression": {
                Object v=eval(node(n.get("argument")),s,b); if(v instanceof JavaObjectScope.Ref){JavaObjectScope.invocationValue(v);String op=string(n.get("operator"));require(op.equals("!")||op.equals("typeof"),"HOOK_OBJECT_OPERATION_UNSUPPORTED");return op.equals("typeof")?"object":false;} require(scalar(v),"DRAFT_SCALAR_REQUIRED");
                switch(string(n.get("operator"))) {
                    case "!": return !truth(v);
                    case "-": return limit(v instanceof BigInteger?((BigInteger)v).negate():-number(v));
                    case "+": require(!(v instanceof BigInteger),"DRAFT_BIGINT_UNARY_PLUS"); return limit(number(v));
                    case "typeof": return v==UNDEFINED?"undefined":v==null?"object":v instanceof BigInteger?"bigint":v instanceof Double?"number":v instanceof String?"string":"boolean";
                    default: throw new ScriptFault("DRAFT_UNARY_OPERATOR");
                }
            }
            case "BinaryExpression": return binary(string(n.get("operator")),eval(node(n.get("left")),s,b),eval(node(n.get("right")),s,b));
            case "TemplateLiteral": {
                List<Object> q=list(n.get("quasis")), e=list(n.get("expressions")); require(q.size()==e.size()+1,"DRAFT_TEMPLATE");
                String out="";
                for(int i=0;i<q.size();i++) { Object v=node(q.get(i)).get("value"); require(v instanceof Map,"DRAFT_TEMPLATE");
                    out+=string(((Map<?,?>)v).get("cooked")); if(i<e.size())out+=text(eval(node(e.get(i)),s,b)); limit(out); } return out;
            }
            default: throw new ScriptFault("DRAFT_EXPRESSION:"+t);
        }
    }
    private static Returned statements(List<Object> body,Map<String,Object> s,Budget b) {
        for(Object item:body) {
            b.tick(); Map<String,Object> n=node(item);
            switch(type(n)) {
                case "VariableDeclaration": for(Object d:list(n.get("declarations"))) { Map<String,Object> dec=node(d); bind(node(dec.get("id")),eval(node(dec.get("init")),s,b),s,b); } break;
                case "ExpressionStatement": eval(node(n.get("expression")),s,b); break;
                case "ReturnStatement": return new Returned(n.get("argument")==null?UNDEFINED:eval(node(n.get("argument")),s,b));
                case "BlockStatement": { Returned r=statements(list(n.get("body")),new LinkedHashMap<>(s),b); if(r!=null)return r; break; }
                case "IfStatement": { Object branch=n.get(truth(eval(node(n.get("test")),s,b))?"consequent":"alternate");
                    if(branch!=null) { Returned r=statements(Collections.singletonList(branch),s,b);if(r!=null)return r; } break; }
                case "EmptyStatement": break;
                default: throw new ScriptFault("DRAFT_STATEMENT");
            }
        } return null;
    }
    private static Object get(Object value,Object rawKey) {
        String k=key(rawKey); require(value!=null&&value!=UNDEFINED,"DRAFT_NULL_PROPERTY");
        if(value instanceof Map) { require(((Map<?,?>)value).containsKey(k),"DRAFT_UNAVAILABLE_PROPERTY:"+k);return ((Map<?,?>)value).get(k); }
        if(value instanceof List) {
            if(k.equals("length"))return (double)((List<?>)value).size();
            require(k.matches("0|[1-9][0-9]{0,8}"),"DRAFT_UNAVAILABLE_PROPERTY");int i=Integer.parseInt(k);
            require(i<((List<?>)value).size(),"DRAFT_UNAVAILABLE_PROPERTY");return ((List<?>)value).get(i);
        }
        if(value instanceof String) {
            String v=(String)value;
            if(k.equals("length"))return (double)v.length();
            if(k.matches("0|[1-9][0-9]{0,8}")) {int i=Integer.parseInt(k);return i<v.length()?String.valueOf(v.charAt(i)):UNDEFINED;}
            require(Set.of("toUpperCase","toLowerCase","trim","slice","substring","includes","startsWith","endsWith").contains(k),"DRAFT_UNAVAILABLE_PROPERTY");
            return (Callable)(args,b) -> stringCall(v,k,args);
        }
        throw new ScriptFault("DRAFT_UNAVAILABLE_PROPERTY");
    }
    private static Object stringCall(String v,String name,List<Object> a) {
        require(a.size()<=2&&a.stream().allMatch(ScriptValues::scalar),"DRAFT_STRING_ARGUMENTS");
        if(name.equals("toUpperCase"))return limit(v.toUpperCase(Locale.ROOT));
        if(name.equals("toLowerCase"))return limit(v.toLowerCase(Locale.ROOT));
        if(name.equals("trim"))return ScriptValues.trim(v);
        Object first=a.isEmpty()?UNDEFINED:a.get(0);
        if(name.equals("slice")||name.equals("substring")) {
            int from=a.isEmpty()?0:index(first,v.length(),name.equals("slice"));
            int to=a.size()<2||a.get(1)==UNDEFINED?v.length():index(a.get(1),v.length(),name.equals("slice"));
            return name.equals("substring")?v.substring(Math.min(from,to),Math.max(from,to)):v.substring(from,Math.max(from,to));
        }
        String needle=text(first);int position=a.size()<2?name.equals("endsWith")?v.length():0:index(a.get(1),v.length(),false);
        if(name.equals("includes"))return v.indexOf(needle,position)>=0;
        if(name.equals("startsWith"))return v.startsWith(needle,position);
        return v.substring(0,position).endsWith(needle);
    }
    private static int index(Object v,int length,boolean negative) {
        double d=v==UNDEFINED?0:number(v);d=d<0?Math.ceil(d):Math.floor(d);
        if(negative&&d<0)d+=length;return (int)Math.max(0,Math.min(length,d));
    }
    private static Object binary(String op,Object a,Object b) {
        if(a instanceof JavaObjectScope.Ref||b instanceof JavaObjectScope.Ref){require(op.equals("===")||op.equals("!=="),"HOOK_OBJECT_OPERATION_UNSUPPORTED");JavaObjectScope.invocationValue(a);JavaObjectScope.invocationValue(b);return op.equals("===")?a==b:a!=b;}
        require(scalar(a)&&scalar(b),"DRAFT_SCALAR_REQUIRED");
        if(op.equals("===")||op.equals("!==")) {
            boolean eq=a==b||(a!=null&&b!=null&&a.getClass()==b.getClass()&&(a instanceof Double?(double)(Double)a==(double)(Double)b:a.equals(b)));
            return op.equals("===")?eq:!eq;
        }
        if(Set.of("<","<=",">",">=").contains(op)) {
            int c;
            if(a instanceof String&&b instanceof String)c=((String)a).compareTo((String)b);
            else if(a instanceof BigInteger||b instanceof BigInteger) {
                if(a instanceof String) {a=relationalBigInt((String)a);if(a==null)return false;}
                if(b instanceof String) {b=relationalBigInt((String)b);if(b==null)return false;}
                // Compare through exact decimal representation; no loss at 2^53 or signed 64-bit edges.
                double xNumber=a instanceof BigInteger?0:number(a),yNumber=b instanceof BigInteger?0:number(b);
                if(Double.isNaN(xNumber)||Double.isNaN(yNumber))return false;
                if(Double.isInfinite(xNumber)||Double.isInfinite(yNumber))c=Double.compare(xNumber,yNumber);
                else {
                    java.math.BigDecimal x=a instanceof BigInteger?new java.math.BigDecimal((BigInteger)a):new java.math.BigDecimal(xNumber);
                    java.math.BigDecimal y=b instanceof BigInteger?new java.math.BigDecimal((BigInteger)b):new java.math.BigDecimal(yNumber);c=x.compareTo(y);
                }
            } else {
                double x=number(a),y=number(b);if(Double.isNaN(x)||Double.isNaN(y))return false;
                c=Double.compare(x==0?0:x,y==0?0:y);
            }
            return op.equals("<")?c<0:op.equals("<=")?c<=0:op.equals(">")?c>0:c>=0;
        }
        if(op.equals("+")&&(a instanceof String||b instanceof String))return limit(text(a)+text(b));
        if(a instanceof BigInteger||b instanceof BigInteger) {
            require(a instanceof BigInteger&&b instanceof BigInteger,"DRAFT_BIGINT_MIXED_ARITHMETIC");BigInteger x=(BigInteger)a,y=(BigInteger)b;
            require(!(op.equals("/")||op.equals("%"))||y.signum()!=0,"DRAFT_DIVISION_ZERO");
            switch(op) {case "+":return limit(x.add(y));case "-":return limit(x.subtract(y));case "*":return limit(x.multiply(y));case "/":return x.divide(y);case "%":return x.remainder(y);default:throw new ScriptFault("DRAFT_BINARY_OPERATOR");}
        }
        double x=number(a),y=number(b);
        switch(op) {case "+":return limit(x+y);case "-":return limit(x-y);case "*":return limit(x*y);case "/":return limit(x/y);case "%":return limit(x%y);default:throw new ScriptFault("DRAFT_BINARY_OPERATOR");}
    }
    private static BigInteger relationalBigInt(String input) {
        String s=ScriptValues.trim(input);if(s.isEmpty())return BigInteger.ZERO;
        if(s.matches("[+-]?[0-9]+"))return new BigInteger(s);
        if(s.matches("0[xX][0-9a-fA-F]+"))return new BigInteger(s.substring(2),16);
        if(s.matches("0[bB][01]+"))return new BigInteger(s.substring(2),2);
        if(s.matches("0[oO][0-7]+"))return new BigInteger(s.substring(2),8);
        return null;
    }
    private static Map<String,Object> builtins() {
        Map<String,Object> out=new LinkedHashMap<>();out.put("undefined",UNDEFINED);
        out.put("String",(Callable)(a,b)->{require(a.size()==1,"DRAFT_BUILTIN_ARITY");return limit(text(a.get(0)));});
        out.put("Number",(Callable)(a,b)->{require(a.size()==1,"DRAFT_BUILTIN_ARITY");return limit(number(a.get(0)));});
        out.put("BigInt",(Callable)(a,b)->{require(a.size()==1,"DRAFT_BUILTIN_ARITY");return bigint(a.get(0));});
        out.put("Boolean",(Callable)(a,b)->{require(a.size()==1&&scalar(a.get(0)),"DRAFT_BUILTIN_ARITY");return truth(a.get(0));});
        Map<String,Object> math=new LinkedHashMap<>();
        for(String name:List.of("min","max","floor","ceil","round","abs")) math.put(name,(Callable)(a,b)->{
            require(!a.isEmpty()&&a.stream().allMatch(x->x instanceof Double),"DRAFT_MATH_ARGUMENTS");double v=(Double)a.get(0);
            switch(name) {case "min":for(Object x:a)v=Math.min(v,(Double)x);break;case "max":for(Object x:a)v=Math.max(v,(Double)x);break;
                case "floor":v=Math.floor(v);break;case "ceil":v=Math.ceil(v);break;
                case "round":if(v!=Math.rint(v)){double floor=Math.floor(v);v=v>=-0.5&&v<0?-0.0:v-floor<0.5?floor:floor+1;}break;
                case "abs":v=Math.abs(v);break;}
            return limit(v);
        });out.put("Math",Collections.unmodifiableMap(math));return out;
    }
}
