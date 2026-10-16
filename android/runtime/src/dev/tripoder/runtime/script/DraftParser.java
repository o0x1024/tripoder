package dev.tripoder.runtime.script;

import java.util.*;
import static dev.tripoder.runtime.script.ScriptFault.require;

/** Recursive-descent statements plus Pratt expressions. Unsupported syntax is rejected, never evaluated elsewhere. */
final class DraftParser {
    private final List<DraftLexer.Token> tokens;
    private final int[] nodes;
    private int position,depth,functionDepth;
    private final Set<String> typeDeclarations=new HashSet<>();
    private static final Set<String> RESERVED=Set.of("break","case","catch","class","const","continue","debugger","default","delete","do","else","enum","export","extends","false","finally","for","function","if","import","in","instanceof","new","null","return","super","switch","this","throw","true","try","typeof","var","void","while","with","yield","await","let","static","implements","interface","package","private","protected","public");
    DraftParser(String source){this(source,new int[]{0});}
    private DraftParser(String source,int[] nodes){tokens=new DraftLexer(source).tokens();this.nodes=nodes;}
    List<Map<String,Object>> program(){
        List<Map<String,Object>> result=new ArrayList<>();
        while(!at("eof")){
            if(eat("import")){
                expect("{");List<Object> names=new ArrayList<>();if(!eat("}")){do{String imported=name(),local=eat("as")?bindingName():imported;require(!RESERVED.contains(local),"DRAFT_RESERVED_BINDING");names.add(ast("ImportSpecifier","imported",id(imported),"local",id(local)));}while(eat(",")&&!is("}"));expect("}");}
                expect("from");DraftLexer.Token module=take();require(module.kind().equals("string"),"DRAFT_IMPORT_SOURCE");semicolon();result.add(ast("ImportDeclaration","source",ast("StringLiteral","value",module.value()),"specifiers",names));
            }else if(eat("export")){expect("default");result.add(ast("ExportDefaultDeclaration","declaration",expression(0)));semicolon();}
            else if(eat("type")){require(typeDeclarations.add(bindingName()),"DRAFT_DUPLICATE_TYPE");expect("=");type();semicolon();}
            else if(eat("interface")){require(typeDeclarations.add(bindingName()),"DRAFT_DUPLICATE_TYPE");typeObject();eat(";");}
            else result.add(statement());
        }
        return result;
    }
    Map<String,Object> expressionOnly(){Map<String,Object> n=expression(0);require(at("eof"),"DRAFT_TRAILING_SOURCE");return n;}
    private Map<String,Object> statement(){
        enter();try{
            if(eat(";"))return ast("EmptyStatement");
            if(is("{"))return block();
            if(eat("const")){
                List<Object> declarations=new ArrayList<>();do{Map<String,Object> p=pattern(false);if(eat(":"))type();expect("=");declarations.add(ast("VariableDeclarator","id",p,"init",expression(0)));}while(eat(","));semicolon();
                return ast("VariableDeclaration","kind","const","declarations",declarations);
            }
            if(eat("return")){require(functionDepth>0,"DRAFT_TOP_LEVEL_RETURN");Map<String,Object> value=is(";")||is("}")||at("eof")||peek().lineBreak()?null:expression(0);semicolon();return ast("ReturnStatement","argument",value);}
            if(eat("if")){expect("(");Map<String,Object> test=expression(0);expect(")");Map<String,Object> yes=statement(),no=eat("else")?statement():null;require(!"VariableDeclaration".equals(yes.get("type"))&&(no==null||!"VariableDeclaration".equals(no.get("type"))),"DRAFT_LEXICAL_DECLARATION_NEEDS_BLOCK");return ast("IfStatement","test",test,"consequent",yes,"alternate",no);}
            require(!Set.of("let","var","for","while","do","switch","try","throw","class","function","await","yield","with","debugger","break","continue","import","export").contains(peek().text()),"DRAFT_UNSUPPORTED_STATEMENT:"+peek().text());
            Map<String,Object> expr=expression(0);semicolon();return ast("ExpressionStatement","expression",expr);
        }finally{depth--;}
    }
    private Map<String,Object> block(){expect("{");List<Object> body=new ArrayList<>();while(!eat("}")){require(!at("eof"),"DRAFT_BLOCK_UNTERMINATED");body.add(statement());}return ast("BlockStatement","body",body);}
    private Map<String,Object> expression(int minimum){
        enter();try{
            Map<String,Object> left=prefix();
            while(true){
                if(is(".")||is("[")||is("(")||is("!")||is("as")||is("satisfies")){
                    if(18<minimum)break;
                    if(eat(".")){left=ast("MemberExpression","object",left,"property",id(name()),"computed",false);continue;}
                    if(eat("[")){Map<String,Object> key=expression(0);expect("]");left=ast("MemberExpression","object",left,"property",key,"computed",true);continue;}
                    if(eat("(")){List<Object> args=new ArrayList<>();if(!eat(")")){do{args.add(expression(0));}while(eat(",")&&!is(")"));expect(")");}left=ast("CallExpression","callee",left,"arguments",args);continue;}
                    if(eat("!")){left=ast("TSNonNullExpression","expression",left);continue;}
                    String annotation=take().text();type();left=ast(annotation.equals("as")?"TSAsExpression":"TSSatisfiesExpression","expression",left);continue;
                }
                if(is("?")&&minimum<=1){take();Map<String,Object> yes=expression(0);expect(":");left=ast("ConditionalExpression","test",left,"consequent",yes,"alternate",expression(1));continue;}
                String op=peek().text();int power=power(op);if(power<minimum||power<0)break;take();Map<String,Object> right=expression(power+1);
                if(Set.of("??","||","&&").contains(op))for(Map<String,Object> child:List.of(left,right))if("LogicalExpression".equals(child.get("type")))
                    require(op.equals("??")=="??".equals(child.get("operator")),"DRAFT_NULLISH_MIX_REQUIRES_PARENTHESES");
                left=ast(Set.of("&&","||","??").contains(op)?"LogicalExpression":"BinaryExpression","operator",op,"left",left,"right",right);
            }
            return left;
        }finally{depth--;}
    }
    private Map<String,Object> prefix(){
        if(at("id")&&position+1<tokens.size()&&tokens.get(position+1).text().equals("=>")){
            List<Object> params=List.of(id(bindingName()));require(!peek().lineBreak(),"DRAFT_ARROW_NEWLINE");expect("=>");return function(params,functionBody());
        }
        if(is("(")&&arrowAhead()){
            List<Object> params=parameters();if(eat(":"))type();require(!peek().lineBreak(),"DRAFT_ARROW_NEWLINE");expect("=>");return function(params,functionBody());
        }
        if(eat("(")){Map<String,Object> value=expression(0);expect(")");return ast("TSAsExpression","expression",value);}
        if(is("!")||is("+")||is("-")||is("typeof")){String op=take().text();return ast("UnaryExpression","operator",op,"argument",expression(15),"prefix",true);}
        if(eat("<")){type();expect(">");return ast("TSTypeAssertion","expression",expression(15));}
        if(eat("function")){require(is("("),"DRAFT_ANONYMOUS_FUNCTION");List<Object> params=parameters();if(eat(":"))type();require(is("{"),"DRAFT_FUNCTION_BODY");return ast("FunctionExpression","params",params,"body",functionBody(),"async",false,"generator",false);}
        if(eat("[")){List<Object> elements=new ArrayList<>();if(!eat("]")){do{require(!is(","),"DRAFT_ARRAY_HOLE");elements.add(expression(0));}while(eat(",")&&!is("]"));expect("]");}return ast("ArrayExpression","elements",elements);}
        if(eat("{")){
            List<Object> properties=new ArrayList<>();if(!eat("}")){do{
                boolean computed=eat("[");Map<String,Object> key=computed?expression(0):propertyKey();if(computed)expect("]");
                if(is("(")){List<Object> params=parameters();if(eat(":"))type();require(is("{"),"DRAFT_FUNCTION_BODY");properties.add(ast("ObjectMethod","kind","method","key",key,"computed",computed,"params",params,"body",functionBody()));}
                else {Map<String,Object> value=eat(":")?expression(0):key;require(!computed||value!=key,"DRAFT_COMPUTED_SHORTHAND");require(value!=key||"Identifier".equals(key.get("type"))&&!RESERVED.contains(key.get("name")),"DRAFT_SHORTHAND");properties.add(ast("ObjectProperty","key",key,"computed",computed,"value",value));}
            }while(eat(",")&&!is("}"));expect("}");}return ast("ObjectExpression","properties",properties);
        }
        DraftLexer.Token token=take();
        if(token.kind().equals("string"))return ast("StringLiteral","value",token.value());
        if(token.kind().equals("number"))return ast("NumericLiteral","value",token.value());
        if(token.kind().equals("bigint"))return ast("BigIntLiteral","value",token.value());
        if(token.kind().equals("template")){
            DraftLexer.Template value=(DraftLexer.Template)token.value();List<Object> quasis=new ArrayList<>(),expressions=new ArrayList<>();
            for(String q:value.text())quasis.add(ast("TemplateElement","value",Map.of("cooked",q)));
            for(String e:value.expressions())expressions.add(new DraftParser(e,nodes).expressionOnly());
            return ast("TemplateLiteral","quasis",quasis,"expressions",expressions);
        }
        if(token.text().equals("true")||token.text().equals("false"))return ast("BooleanLiteral","value",token.text().equals("true"));
        if(token.text().equals("null"))return ast("NullLiteral");
        require(token.kind().equals("id")&&!RESERVED.contains(token.text()),"DRAFT_UNSUPPORTED_EXPRESSION:"+token.text());
        return id(token.text());
    }
    private boolean arrowAhead(){
        int level=0,end=-1;for(int i=position;i<tokens.size();i++){String t=tokens.get(i).text();if(t.equals("("))level++;else if(t.equals(")")&&--level==0){end=i+1;break;}}
        if(end<0)return false;if(tokens.get(end).text().equals("=>"))return true;
        if(tokens.get(end).text().equals(":")){int saved=position;position=end+1;try{type();return is("=>");}finally{position=saved;}}
        return false;
    }
    private List<Object> parameters(){expect("(");List<Object> result=new ArrayList<>();if(!eat(")")){do{Map<String,Object> value=pattern(false);if(eat(":"))type();if(eat("="))value=ast("AssignmentPattern","left",value,"right",expression(0));result.add(value);}while(eat(",")&&!is(")"));expect(")");}return result;}
    private Map<String,Object> pattern(boolean defaultAllowed){
        enter();try{
            Map<String,Object> result;
            if(eat("{")){List<Object> props=new ArrayList<>();if(!eat("}")){do{
                boolean computed=eat("[");Map<String,Object> key=computed?expression(0):propertyKey();if(computed)expect("]");
                Map<String,Object> value=eat(":")?pattern(true):key;require(value!=key||"Identifier".equals(key.get("type")),"DRAFT_BINDING");
                if(value==key)require(!RESERVED.contains(key.get("name")),"DRAFT_RESERVED_BINDING");
                if(eat("="))value=ast("AssignmentPattern","left",value,"right",expression(0));props.add(ast("ObjectProperty","key",key,"computed",computed,"value",value));
            }while(eat(",")&&!is("}"));expect("}");}result=ast("ObjectPattern","properties",props);}
            else if(eat("[")){List<Object> items=new ArrayList<>();while(!eat("]")){if(eat(",")){items.add(null);continue;}items.add(pattern(true));if(!eat(",")){expect("]");break;}}result=ast("ArrayPattern","elements",items);}
            else result=id(bindingName());
            if(defaultAllowed&&eat("="))result=ast("AssignmentPattern","left",result,"right",expression(0));return result;
        }finally{depth--;}
    }
    private Map<String,Object> propertyKey(){DraftLexer.Token token=take();if(token.kind().equals("id"))return id(token.text());if(token.kind().equals("string"))return ast("StringLiteral","value",token.value());if(token.kind().equals("number"))return ast("NumericLiteral","value",token.value());throw new ScriptFault("DRAFT_PROPERTY_KEY");}
    private Map<String,Object> functionBody(){functionDepth++;try{return is("{")?block():expression(0);}finally{functionDepth--;}}
    /** Type syntax has no runtime value. Accept only a parsed type grammar, not arbitrary skipped text. */
    private void type(){
        enter();try{
            if(is("{"))typeObject();
            else if(eat("(")){type();expect(")");}
            else if(at("string")||at("number")){take();}
            else {name();while(eat("."))name();if(eat("<")){do{type();}while(eat(","));expect(">");}}
            while(eat("["))expect("]");
            if(eat("|")||eat("&"))type();
        }finally{depth--;}
    }
    private void typeObject(){expect("{");while(!eat("}")){propertyKey();eat("?");expect(":");type();if(!eat(";")&&!eat(","))require(is("}")||peek().lineBreak(),"DRAFT_TYPE_SEPARATOR");}}
    private static int power(String op){switch(op){case "??":case "||":return 2;case "&&":return 3;case "===":case "!==":return 7;case "<":case "<=":case ">":case ">=":return 8;case "+":case "-":return 10;case "*":case "/":case "%":return 11;default:return -1;}}
    private void semicolon(){if(!eat(";"))require(at("eof")||is("}")||peek().lineBreak(),"DRAFT_SEMICOLON");}
    private String name(){DraftLexer.Token token=take();require(token.kind().equals("id"),"DRAFT_IDENTIFIER");ScriptValues.key(token.text());return token.text();}
    private String bindingName(){String value=name();require(!RESERVED.contains(value),"DRAFT_RESERVED_BINDING");return value;}
    private Map<String,Object> id(String name){return ast("Identifier","name",name);}
    private Map<String,Object> function(List<Object> params,Map<String,Object> body){return ast("ArrowFunctionExpression","params",params,"body",body,"async",false,"generator",false);}
    private Map<String,Object> ast(String type,Object... pairs){require(++nodes[0]<=8000,"DRAFT_AST_BUDGET");Map<String,Object> out=new LinkedHashMap<>();out.put("type",type);for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    private void enter(){require(++depth<80,"DRAFT_PARSE_DEPTH");}
    private DraftLexer.Token peek(){return tokens.get(position);}
    private DraftLexer.Token take(){DraftLexer.Token token=peek();require(!token.kind().equals("eof"),"DRAFT_UNEXPECTED_EOF");position++;return token;}
    private boolean is(String text){return peek().text().equals(text);}
    private boolean at(String kind){return peek().kind().equals(kind);}
    private boolean eat(String text){if(!is(text))return false;position++;return true;}
    private void expect(String text){require(eat(text),"DRAFT_EXPECTED:"+text+":"+peek().start());}
}
