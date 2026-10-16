package dev.tripoder.runtime.script;

import java.util.*;
import static dev.tripoder.runtime.script.ScriptFault.require;

/** Strict bounded JSON for the existing Worker command envelope. No object coercion or bigint truncation. */
public final class HookWire {
    private HookWire(){}
    public static Map<String,Object> request(String source){require(source!=null&&source.length()<=65536,"HOOK_REQUEST_SIZE");Parser p=new Parser(source);Object value=p.value(0);p.space();require(p.position==source.length(),"HOOK_JSON_TRAILING");return HookEvidence.object(value);}
    public static String encode(Object value){StringBuilder out=new StringBuilder();write(value,out,0,new IdentityHashMap<>());require(out.length()<=131072,"HOOK_RESPONSE_SIZE");return out.toString();}
    private static void write(Object value,StringBuilder out,int depth,IdentityHashMap<Object,Boolean> path){
        require(depth<40&&out.length()<=131072,"HOOK_JSON_BUDGET");
        if(value==null){out.append("null");return;}if(value instanceof Boolean){out.append(value);return;}
        if(value instanceof String){quote((String)value,out);return;}
        if(value instanceof Double||value instanceof Integer||value instanceof Long){double n=((Number)value).doubleValue();require(Double.isFinite(n),"HOOK_JSON_NUMBER");if(value instanceof Long)require(Math.abs(n)<=9007199254740991d,"HOOK_JSON_INTEGER_PRECISION");out.append(ScriptValues.text(n));return;}
        require(value instanceof Map||value instanceof List,"HOOK_JSON_VALUE");require(path.put(value,true)==null,"HOOK_JSON_CYCLE");
        try{boolean first=true;
            if(value instanceof Map){require(((Map<?,?>)value).size()<=128,"HOOK_JSON_OBJECT_SIZE");out.append('{');for(var e:((Map<?,?>)value).entrySet()){require(e.getKey() instanceof String,"HOOK_JSON_KEY");if(!first)out.append(',');first=false;quote((String)e.getKey(),out);out.append(':');write(e.getValue(),out,depth+1,path);}out.append('}');}
            else {require(((List<?>)value).size()<=256,"HOOK_JSON_ARRAY_SIZE");out.append('[');for(Object x:(List<?>)value){if(!first)out.append(',');first=false;write(x,out,depth+1,path);}out.append(']');}
        }finally{path.remove(value);}
    }
    private static void quote(String text,StringBuilder out){out.append('"');for(int i=0;i<text.length();i++){char c=text.charAt(i);switch(c){
        case '"':out.append("\\\"");break;case '\\':out.append("\\\\");break;case '\n':out.append("\\n");break;case '\r':out.append("\\r");break;case '\t':out.append("\\t");break;case '\b':out.append("\\b");break;case '\f':out.append("\\f");break;
        default:if(c<32||Character.isSurrogate(c))out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);
    }}out.append('"');}
    private static final class Parser {
        final String source;int position,items;
        Parser(String source){this.source=source;}
        void space(){while(position<source.length()&&" \r\n\t".indexOf(source.charAt(position))>=0)position++;}
        Object value(int depth){require(depth<40&&++items<=16000,"HOOK_JSON_BUDGET");space();require(position<source.length(),"HOOK_JSON_EOF");char c=source.charAt(position);
            if(c=='"')return string();
            if(c=='{'){position++;Map<String,Object> out=new LinkedHashMap<>();space();if(eat('}'))return out;do{space();String key=string();require(!out.containsKey(key),"HOOK_JSON_DUPLICATE_KEY");space();expect(':');out.put(key,value(depth+1));require(out.size()<=128,"HOOK_JSON_OBJECT_SIZE");space();if(eat('}'))return out;expect(',');}while(true);}
            if(c=='['){position++;List<Object> out=new ArrayList<>();space();if(eat(']'))return out;do{out.add(value(depth+1));require(out.size()<=256,"HOOK_JSON_ARRAY_SIZE");space();if(eat(']'))return out;expect(',');}while(true);}
            for(String literal:List.of("true","false","null"))if(source.startsWith(literal,position)){position+=literal.length();return literal.equals("null")?null:literal.equals("true");}
            int start=position;while(position<source.length()&&"-+0123456789.eE".indexOf(source.charAt(position))>=0)position++;String raw=source.substring(start,position);
            require(raw.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"),"HOOK_JSON_NUMBER");double n=Double.parseDouble(raw);require(Double.isFinite(n),"HOOK_JSON_NUMBER");return n;
        }
        String string(){expect('"');StringBuilder out=new StringBuilder();while(position<source.length()){char c=source.charAt(position++);if(c=='"')return out.toString();require(c>=32,"HOOK_JSON_STRING");if(c!='\\'){out.append(c);continue;}
            require(position<source.length(),"HOOK_JSON_ESCAPE");char escaped=source.charAt(position++);switch(escaped){case '"':case '\\':case '/':out.append(escaped);break;case 'b':out.append('\b');break;case 'f':out.append('\f');break;case 'n':out.append('\n');break;case 'r':out.append('\r');break;case 't':out.append('\t');break;case 'u':require(position+4<=source.length(),"HOOK_JSON_ESCAPE");String hex=source.substring(position,position+4);require(hex.matches("[0-9a-fA-F]{4}"),"HOOK_JSON_ESCAPE");position+=4;out.append((char)Integer.parseInt(hex,16));break;default:throw new ScriptFault("HOOK_JSON_ESCAPE");}
        }throw new ScriptFault("HOOK_JSON_STRING");}
        boolean eat(char c){if(position<source.length()&&source.charAt(position)==c){position++;return true;}return false;}
        void expect(char c){require(eat(c),"HOOK_JSON_EXPECTED:"+c);}
    }
}
