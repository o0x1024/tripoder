package dev.tripoder.runtime.script;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded HTTP/1 prefix framing. Never guesses a new boundary after an error. */
final class NetworkHttp1 {
    record Frame(String state,String reason,int next,Map<String,Object> value){}
    private static Frame issue(String state,String reason,int offset){return new Frame(state,reason,offset,Map.of("state",state,"reason",reason));}
    private static final String TOKEN="[!#$%&'*+.^_`|~0-9A-Za-z-]+";
    static Frame read(byte[] input,int offset,boolean request,String method){
        if(offset<0||offset>input.length)throw new IllegalArgumentException("HTTP_OFFSET");
        String text=new String(input,StandardCharsets.ISO_8859_1);int end=text.indexOf("\r\n\r\n",offset);
        if(end<0)return issue("INCOMPLETE","HEADER_NOT_COMPLETE",offset);
        String[] lines=text.substring(offset,end).split("\r\n",-1),start=lines[0].split(" ",3);
        int status=0;
        if(request){if(start.length!=3||!start[0].matches(TOKEN)||!start[1].matches("[^\\x00-\\x20\\x7f]+")||!start[2].matches("HTTP/1\\.[01]"))return issue("UNSUPPORTED","NOT_HTTP1_REQUEST",offset);}
        else{
            if(start.length<2||!start[0].matches("HTTP/1\\.[01]")||!start[1].matches("[0-9]{3}")||start.length==3&&controls(start[2]))return issue("UNSUPPORTED","NOT_HTTP1_RESPONSE",offset);
            status=Integer.parseInt(start[1]);if(status<100||status>599)return issue("UNSUPPORTED","STATUS_RANGE",offset);
        }
        if(lines.length>65)return issue("UNSUPPORTED","HEADER_COUNT_LIMIT",offset);
        Map<String,List<String>> headers=new LinkedHashMap<>();
        for(int i=1;i<lines.length;i++){
            int colon=lines[i].indexOf(':');if(colon<1)return issue("UNSUPPORTED","HEADER_SYNTAX",offset);
            String name=lines[i].substring(0,colon),value=lines[i].substring(colon+1).replaceAll("^[ \t]+|[ \t]+$","");
            if(!name.matches(TOKEN)||controls(value))return issue("UNSUPPORTED","HEADER_SYNTAX",offset);
            headers.computeIfAbsent(name.toLowerCase(Locale.ROOT),key->new ArrayList<>()).add(value);
        }
        List<String> lengths=headers.get("content-length"),codings=headers.get("transfer-encoding");
        if(request&&headers.getOrDefault("host",List.of()).size()>1)return issue("UNSUPPORTED","MULTIPLE_HOST_HEADERS",offset);
        if(lengths!=null&&(lengths.size()!=1||!lengths.get(0).matches("[0-9]{1,10}")))return issue("UNSUPPORTED","AMBIGUOUS_CONTENT_LENGTH",offset);
        if(codings!=null&&lengths!=null)return issue("UNSUPPORTED","TRANSFER_ENCODING_WITH_CONTENT_LENGTH",offset);
        if(codings!=null&&(codings.size()!=1||!codings.get(0).equalsIgnoreCase("chunked")))return issue("UNSUPPORTED","TRANSFER_CODING_NOT_SUPPORTED",offset);
        if(!request&&(status==101||"CONNECT".equals(method)&&status>=200&&status<300))return issue("UNSUPPORTED",status==101?"PROTOCOL_UPGRADE":"CONNECT_TUNNEL",offset);
        boolean noBody=!request&&("HEAD".equals(method)||status<200||status==204||status==304);
        int body=end+4,next=body;long size=0;String framing="NONE";
        List<Map<String,Object>> ranges=new ArrayList<>();Map<String,List<String>> trailers=new LinkedHashMap<>();
        if(!noBody&&codings!=null){
            framing="CHUNKED";int cursor=body,chunks=0;
            while(true){
                if(++chunks>64)return issue("UNSUPPORTED","CHUNK_COUNT_LIMIT",offset);
                int lineEnd=text.indexOf("\r\n",cursor);if(lineEnd<0)return issue("INCOMPLETE","CHUNK_SIZE_NOT_COMPLETE",offset);
                String line=text.substring(cursor,lineEnd);if(line.indexOf(';')>=0)return issue("UNSUPPORTED","CHUNK_EXTENSIONS_NOT_SUPPORTED",offset);
                if(!line.matches("[0-9a-fA-F]{1,8}"))return issue("UNSUPPORTED","CHUNK_SIZE_INVALID",offset);
                long count=Long.parseLong(line,16);cursor=lineEnd+2;
                if(count==0){
                    if(cursor+2>text.length())return issue("INCOMPLETE","TRAILER_NOT_COMPLETE",offset);
                    if(text.startsWith("\r\n",cursor)){next=cursor+2;break;}
                    int trailerEnd=text.indexOf("\r\n\r\n",cursor);if(trailerEnd<0)return issue("INCOMPLETE","TRAILER_NOT_COMPLETE",offset);
                    for(String trailer:text.substring(cursor,trailerEnd).split("\r\n")){
                        int colon=trailer.indexOf(':');if(colon<1)return issue("UNSUPPORTED","TRAILER_SYNTAX",offset);
                        String name=trailer.substring(0,colon).toLowerCase(Locale.ROOT),value=trailer.substring(colon+1).replaceAll("^[ \t]+|[ \t]+$","");
                        if(!name.matches(TOKEN)||controls(value)||Set.of("content-length","transfer-encoding","host","connection","trailer").contains(name))return issue("UNSUPPORTED","TRAILER_FORBIDDEN_OR_INVALID",offset);
                        trailers.computeIfAbsent(name,key->new ArrayList<>()).add(value);
                    }
                    next=trailerEnd+4;break;
                }
                if(count>input.length-cursor-2)return issue("INCOMPLETE","CHUNK_DATA_NOT_COMPLETE",offset);
                int last=cursor+(int)count;if(!text.startsWith("\r\n",last))return issue("UNSUPPORTED","CHUNK_TERMINATOR_INVALID",offset);
                ranges.add(Map.of("offset",(double)cursor,"length",(double)count));size+=count;cursor=last+2;
            }
        }else if(!noBody&&lengths!=null){
            framing="CONTENT_LENGTH";size=Long.parseLong(lengths.get(0));if(size>input.length-body)return issue("INCOMPLETE","BODY_NOT_COMPLETE",offset);
            next=body+(int)size;if(size>0)ranges.add(Map.of("offset",(double)body,"length",(double)size));
        }else if(!noBody&&!request)return issue("UNSUPPORTED","CLOSE_DELIMITED_RESPONSE_NOT_SUPPORTED",offset);
        Map<String,Object> value=new LinkedHashMap<>();value.put("state","COMPLETE");value.put("offset",(double)offset);value.put("wireBytes",(double)(next-offset));value.put("startLine",lines[0]);
        value.put("headers",headers);value.put("bodyOffset",(double)body);value.put("bodyBytes",(double)size);value.put("bodyRanges",List.copyOf(ranges));value.put("framing",framing);value.put("trailers",trailers);
        if(request){value.put("method",start[0]);value.put("requestTarget",start[1]);}else{value.put("statusCode",(double)status);value.put("informational",status<200);}
        @SuppressWarnings("unchecked") Map<String,Object> immutable=(Map<String,Object>)ScriptValues.freeze(value);
        return new Frame("COMPLETE","",next,immutable);
    }
    static Map<String,Object> inspect(byte[] input,boolean request){Frame frame=read(input,0,request,"");return frame.state.equals("COMPLETE")&&frame.next!=input.length?Map.of("state","UNSUPPORTED","reason","MULTIPLE_MESSAGES_OR_EXTRA_BYTES"):frame.value;}
    private static boolean controls(String value){for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c<32&&c!='\t'||c==127)return true;}return false;}
    private NetworkHttp1(){}
}
