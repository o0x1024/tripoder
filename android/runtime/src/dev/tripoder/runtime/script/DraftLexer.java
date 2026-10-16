package dev.tripoder.runtime.script;

import java.math.BigInteger;
import java.util.*;
import static dev.tripoder.runtime.script.ScriptFault.require;

/** Bounded tokenizer for the executable subset. Source text never reaches a Java/JS compiler. */
final class DraftLexer {
    record Token(String kind,String text,Object value,int start,int end,boolean lineBreak) {}
    record Template(List<String> text,List<String> expressions) {}
    private final String source;
    private int position,nesting;
    DraftLexer(String source){this.source=source;require(source.length()<=24000,"HOOK_SOURCE_SIZE");}
    List<Token> tokens(){
        List<Token> out=new ArrayList<>();
        while(true){boolean line=space();int start=position;if(position==source.length()){out.add(new Token("eof","",null,start,start,line));return out;}
            require(out.size()<8000,"DRAFT_TOKEN_BUDGET");char c=source.charAt(position);String kind,text;Object value=null;
            if(c=='\''||c=='"'){kind="string";value=quoted(c);text=source.substring(start,position);}
            else if(c=='`'){kind="template";value=template();text=source.substring(start,position);}
            else if(identifierStart(c)){position++;while(position<source.length()&&(identifierStart(source.charAt(position))||source.charAt(position)>='0'&&source.charAt(position)<='9'))position++;kind="id";text=source.substring(start,position);}
            else if(c>='0'&&c<='9'||c=='.'&&position+1<source.length()&&Character.isDigit(source.charAt(position+1))){
                kind="number";position++;while(position<source.length()){
                    char next=source.charAt(position),prev=source.charAt(position-1);
                    boolean radix=source.startsWith("0x",start)||source.startsWith("0X",start)||source.startsWith("0b",start)||source.startsWith("0B",start)||source.startsWith("0o",start)||source.startsWith("0O",start);
                    if(Character.isLetterOrDigit(next)||next=='.'||next=='_'||!radix&&(next=='+'||next=='-')&&(prev=='e'||prev=='E'))position++;else break;
                }
                text=source.substring(start,position);value=numeric(text);if(value instanceof BigInteger)kind="bigint";
            } else {
                kind="punct";text=null;
                for(String candidate:List.of("===","!==","=>","<=",">=","&&","||","??","==","!=","++","--","**","?.","...","+=","-=","*=","/="))
                    if(source.startsWith(candidate,position)){text=candidate;break;}
                if(text==null)text=String.valueOf(c);position+=text.length();
                require("{}[](),;:.?+-*/%<>=!|&".contains(String.valueOf(c))||Set.of("&&","||","??").contains(text),"DRAFT_CHARACTER:"+start);
            }
            out.add(new Token(kind,text,value,start,position,line));
        }
    }
    private Object numeric(String raw){
        boolean big=raw.endsWith("n");String sourceNumber=big?raw.substring(0,raw.length()-1):raw;
        int separatorRadix=sourceNumber.startsWith("0x")||sourceNumber.startsWith("0X")?16:sourceNumber.startsWith("0b")||sourceNumber.startsWith("0B")?2:sourceNumber.startsWith("0o")||sourceNumber.startsWith("0O")?8:10;
        for(int i=0;i<sourceNumber.length();i++)if(sourceNumber.charAt(i)=='_')require(i>0&&i+1<sourceNumber.length()&&Character.digit(sourceNumber.charAt(i-1),separatorRadix)>=0&&Character.digit(sourceNumber.charAt(i+1),separatorRadix)>=0,"DRAFT_NUMERIC_SEPARATOR");
        String s=sourceNumber.replace("_","");
        int radix=10;String digits=s;
        if(s.matches("0[xX][0-9a-fA-F]+")){radix=16;digits=s.substring(2);}
        else if(s.matches("0[bB][01]+")){radix=2;digits=s.substring(2);}
        else if(s.matches("0[oO][0-7]+")){radix=8;digits=s.substring(2);}
        else if(big)require(s.matches("0|[1-9][0-9]*"),"DRAFT_NUMERIC_LITERAL");
        else require(s.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]*)?(?:[eE][+-]?[0-9]+)?|\\.[0-9]+(?:[eE][+-]?[0-9]+)?"),"DRAFT_NUMERIC_LITERAL");
        try{return ScriptValues.limit(big?new BigInteger(digits,radix):radix!=10?new BigInteger(digits,radix).doubleValue():Double.parseDouble(s));}
        catch(NumberFormatException e){throw new ScriptFault("DRAFT_NUMERIC_LITERAL");}
    }
    private boolean space(){
        boolean line=false;
        for(;;){while(position<source.length()&&whitespace(source.charAt(position))){line|=line(source.charAt(position));position++;}
            if(source.startsWith("//",position)){position+=2;while(position<source.length()&&!line(source.charAt(position)))position++;continue;}
            if(source.startsWith("/*",position)){position+=2;boolean closed=false;while(position<source.length()){if(source.startsWith("*/",position)){position+=2;closed=true;break;}line|=line(source.charAt(position++));}require(closed,"DRAFT_COMMENT_UNTERMINATED");continue;}
            return line;
        }
    }
    private static boolean line(char c){return c=='\n'||c=='\r'||c=='\u2028'||c=='\u2029';}
    private static boolean identifierStart(char c){return c>='a'&&c<='z'||c>='A'&&c<='Z'||c=='_'||c=='$';}
    private static boolean whitespace(char c){return c>='\u0009'&&c<='\r'||c==' '||c=='\u00a0'||c=='\u1680'||c>='\u2000'&&c<='\u200a'||c=='\u2028'||c=='\u2029'||c=='\u202f'||c=='\u205f'||c=='\u3000'||c=='\ufeff';}
    private String quoted(char quote){
        position++;StringBuilder out=new StringBuilder();boolean closed=false;
        while(position<source.length()){char c=source.charAt(position++);if(c==quote){closed=true;break;}require(!line(c),"DRAFT_STRING_NEWLINE");if(c=='\\')escape(out);else out.append(c);}
        require(closed,"DRAFT_STRING_UNTERMINATED");return (String)ScriptValues.limit(out.toString());
    }
    private void escape(StringBuilder out){
        require(position<source.length(),"DRAFT_ESCAPE");char c=source.charAt(position++);
        switch(c){
            case 'n':out.append('\n');break;case 'r':out.append('\r');break;case 't':out.append('\t');break;
            case 'b':out.append('\b');break;case 'f':out.append('\f');break;case 'v':out.append('\u000b');break;
            case '0':require(position==source.length()||!Character.isDigit(source.charAt(position)),"DRAFT_OCTAL_ESCAPE");out.append('\0');break;
            case 'x':out.append((char)hex(2));break;
            case 'u':if(position<source.length()&&source.charAt(position)=='{'){
                position++;int begin=position;while(position<source.length()&&source.charAt(position)!='}')position++;
                require(position<source.length()&&position>begin&&position-begin<=6,"DRAFT_UNICODE_ESCAPE");String text=source.substring(begin,position++);
                require(text.matches("[0-9a-fA-F]+"),"DRAFT_UNICODE_ESCAPE");int cp=Integer.parseInt(text,16);require(cp<=0x10ffff,"DRAFT_UNICODE_ESCAPE");out.appendCodePoint(cp);
            }else out.append((char)hex(4));break;
            case '\r':if(position<source.length()&&source.charAt(position)=='\n')position++;break;
            case '\n':case '\u2028':case '\u2029':break;
            default:require(c<'1'||c>'9',"DRAFT_OCTAL_ESCAPE");out.append(c);
        }
    }
    private int hex(int length){require(position+length<=source.length(),"DRAFT_ESCAPE");String text=source.substring(position,position+length);require(text.matches("[0-9a-fA-F]+"),"DRAFT_ESCAPE");position+=length;return Integer.parseInt(text,16);}
    private Template template(){
        require(++nesting<64,"DRAFT_TEMPLATE_DEPTH");position++;List<String> text=new ArrayList<>(),expressions=new ArrayList<>();StringBuilder part=new StringBuilder();boolean closed=false;
        while(position<source.length()){
            char c=source.charAt(position++);if(c=='`'){text.add(part.toString());closed=true;break;}
            if(c=='\\'){escape(part);continue;}
            if(c=='$'&&position<source.length()&&source.charAt(position)=='{'){
                position++;text.add(part.toString());part.setLength(0);int begin=position,depth=1;
                while(position<source.length()&&depth>0){
                    char x=source.charAt(position);
                    if(x=='\''||x=='"'){quoted(x);continue;}if(x=='`'){template();continue;}
                    if(source.startsWith("/*",position)||source.startsWith("//",position)){space();continue;}
                    position++;if(x=='{')depth++;else if(x=='}')depth--;
                }
                require(depth==0,"DRAFT_TEMPLATE_UNTERMINATED");expressions.add(source.substring(begin,position-1));continue;
            }
            if(c=='\r'){if(position<source.length()&&source.charAt(position)=='\n')position++;c='\n';}part.append(c);
        }
        require(closed,"DRAFT_TEMPLATE_UNTERMINATED");nesting--;return new Template(List.copyOf(text),List.copyOf(expressions));
    }
}
