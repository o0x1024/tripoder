package dev.tripoder.runtime.script;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** HAR 1.2 entries from retained HTTP/1 exchanges. No wall-clock was recorded per message, so time fields are explicit about that. */
final class NetworkHar {
    private NetworkHar(){}
    @SuppressWarnings("unchecked")
    static Map<String,Object> entry(Map<String,Object> exchange,String startedIso){
        Map<String,Object> request=(Map<String,Object>)exchange.get("request"),response=(Map<String,Object>)exchange.get("response");
        byte[] requestBytes=HexFormat.of().parseHex((String)exchange.get("requestHex")),responseBytes=HexFormat.of().parseHex((String)exchange.get("responseHex"));
        Map<String,List<String>> requestHeaders=(Map<String,List<String>>)request.get("headers"),responseHeaders=(Map<String,List<String>>)response.get("headers");
        String host=first(requestHeaders,"host"),target=(String)request.get("requestTarget");
        String url=target.startsWith("http://")||target.startsWith("https://")?target:"https://"+(host.isEmpty()?"unknown-host.invalid":host)+(target.startsWith("/")?target:"/"+target);
        String[] requestLine=((String)request.get("startLine")).split(" ",3),statusLine=((String)response.get("startLine")).split(" ",3);
        byte[] requestBody=body(requestBytes,request),responseBody=body(responseBytes,response);
        Map<String,Object> har=new LinkedHashMap<>();
        har.put("startedDateTime",startedIso);har.put("time",-1d);
        Map<String,Object> req=new LinkedHashMap<>();
        req.put("method",request.get("method"));req.put("url",url);req.put("httpVersion",requestLine.length>2?requestLine[2]:"HTTP/1.1");
        req.put("cookies",List.of());req.put("headers",headers(requestHeaders));req.put("queryString",query(url));
        req.put("headersSize",((Double)request.get("bodyOffset")-(Double)request.get("offset")));req.put("bodySize",(double)requestBody.length);
        if(requestBody.length>0){Map<String,Object> post=new LinkedHashMap<>();post.put("mimeType",first(requestHeaders,"content-type"));putText(post,requestBody,first(requestHeaders,"content-encoding"));req.put("postData",post);}
        Map<String,Object> res=new LinkedHashMap<>();
        res.put("status",response.get("statusCode"));res.put("statusText",statusLine.length>2?statusLine[2]:"");res.put("httpVersion",statusLine[0]);
        res.put("cookies",List.of());res.put("headers",headers(responseHeaders));res.put("redirectURL",first(responseHeaders,"location"));
        res.put("headersSize",((Double)response.get("bodyOffset")-(Double)response.get("offset")));res.put("bodySize",(double)responseBody.length);
        Map<String,Object> content=new LinkedHashMap<>();content.put("size",(double)responseBody.length);content.put("mimeType",first(responseHeaders,"content-type"));
        putText(content,responseBody,first(responseHeaders,"content-encoding"));res.put("content",content);
        har.put("request",req);har.put("response",res);har.put("cache",Map.of());har.put("timings",Map.of("send",-1d,"wait",-1d,"receive",-1d));
        har.put("_tripoderConnectionId",exchange.get("connectionId"));har.put("_tripoderMessageId",exchange.get("messageId"));
        har.put("_tripoderTimeSource","CAPTURE_SESSION_START_APPROXIMATION");
        return Collections.unmodifiableMap(har);
    }
    private static String first(Map<String,List<String>> headers,String name){
        for(Map.Entry<String,List<String>> entry:headers.entrySet())if(entry.getKey().equalsIgnoreCase(name)&&!entry.getValue().isEmpty())return entry.getValue().get(0);
        return "";
    }
    private static List<Map<String,Object>> headers(Map<String,List<String>> headers){
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map.Entry<String,List<String>> entry:headers.entrySet())for(String value:entry.getValue())out.add(Map.of("name",entry.getKey(),"value",value));
        return out;
    }
    private static List<Map<String,Object>> query(String url){
        int mark=url.indexOf('?');List<Map<String,Object>> out=new ArrayList<>();if(mark<0)return out;
        for(String pair:url.substring(mark+1).split("&")){if(pair.isEmpty())continue;int eq=pair.indexOf('=');out.add(Map.of("name",eq<0?pair:pair.substring(0,eq),"value",eq<0?"":pair.substring(eq+1)));}
        return out;
    }
    @SuppressWarnings("unchecked")
    private static byte[] body(byte[] wire,Map<String,Object> frame){
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        int base=((Double)frame.get("offset")).intValue();
        for(Map<String,Object> range:(List<Map<String,Object>>)frame.get("bodyRanges")){int start=((Double)range.get("offset")).intValue()-base,length=((Double)range.get("length")).intValue();out.write(wire,start,length);}
        return out.toByteArray();
    }
    private static void putText(Map<String,Object> target,byte[] body,String contentEncoding){
        boolean encoded=!contentEncoding.isEmpty()&&!contentEncoding.equalsIgnoreCase("identity");
        if(!encoded){
            try{String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();target.put("text",text);return;}
            catch(CharacterCodingException binary){/* fall through to base64 */}
        }
        target.put("text",Base64.getEncoder().encodeToString(body));target.put("encoding","base64");
        if(encoded)target.put("comment","Body is still Content-Encoding: "+contentEncoding+" (not decompressed)");
    }
}
