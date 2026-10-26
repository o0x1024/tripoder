package dev.tripoder.runtime.script;

import java.util.*;
import java.util.function.*;

/** Request-order response association, including interim responses, over retained stream bytes. */
final class NetworkHttpConversation {
    private final String connectionId;
    private final Predicate<Map<String,Object>> emit;
    private final List<NetworkHttp1.Frame> requests=new ArrayList<>();
    private final List<Map<String,Object>> informational=new ArrayList<>();
    private int requestOffset,responseOffset,matched;
    private String state="IN_PROGRESS",reason="";
    NetworkHttpConversation(String connectionId,Predicate<Map<String,Object>> emit){this.connectionId=connectionId;this.emit=emit;}
    void invalidate(String code){if(reason.isEmpty()){reason=code;state="INCOMPLETE";}}
    void drain(byte[] writes,byte[] reads,boolean closed){
        if(!reason.isEmpty())return;
        while(requestOffset<writes.length){
            if(requests.size()>=16){state="UNSUPPORTED";reason="HTTP_REQUEST_LIMIT";return;}
            var request=NetworkHttp1.read(writes,requestOffset,true,"");
            if(!request.state().equals("COMPLETE")){if(request.state().equals("UNSUPPORTED")||closed){state=request.state();reason=request.reason();}break;}
            requests.add(request);requestOffset=request.next();
        }
        while(matched<requests.size()&&responseOffset<reads.length&&reason.isEmpty()){
            var request=requests.get(matched);var response=NetworkHttp1.read(reads,responseOffset,false,(String)request.value().get("method"));
            if(!response.state().equals("COMPLETE")){if(response.state().equals("UNSUPPORTED")||closed){state=response.state();reason=response.reason();}break;}
            responseOffset=response.next();
            if(Boolean.TRUE.equals(response.value().get("informational"))){
                if(informational.size()>=8){state="UNSUPPORTED";reason="INFORMATIONAL_RESPONSE_LIMIT";break;}
                Map<String,Object> interim=new LinkedHashMap<>(response.value());interim.put("rawHex",hex(reads,response));informational.add(interim);continue;
            }
            Map<String,Object> exchange=new LinkedHashMap<>();exchange.put("connectionId",connectionId);exchange.put("messageId",connectionId+"-m"+(matched+1));exchange.put("messageIndex",(double)(matched+1));
            exchange.put("httpState","COMPLETE");exchange.put("request",request.value());exchange.put("response",response.value());exchange.put("informational",List.copyOf(informational));
            exchange.put("requestHex",hex(writes,request));exchange.put("responseHex",hex(reads,response));exchange.put("parser","http1-v2");
            if(!emit.test(exchange)){state="INCOMPLETE";reason="HTTP_MESSAGE_LIMIT";return;}
            matched++;informational.clear();
        }
        if(closed&&reason.isEmpty()){
            if(matched!=requests.size()||!informational.isEmpty()){state="INCOMPLETE";reason="FINAL_RESPONSE_MISSING";}
            else if(responseOffset!=reads.length){state="UNSUPPORTED";reason="RESPONSE_WITHOUT_REQUEST";}
            else if(requestOffset!=writes.length){state="INCOMPLETE";reason="REQUEST_NOT_COMPLETE";}
            else if(matched==0){state="INCOMPLETE";reason="NO_HTTP_MESSAGES";}else state="COMPLETE";
        }
    }
    private static String hex(byte[] input,NetworkHttp1.Frame frame){int offset=((Double)frame.value().get("offset")).intValue();return HexFormat.of().formatHex(Arrays.copyOfRange(input,offset,frame.next()));}
    Map<String,Object> status(){return Map.of("state",state,"reason",reason,"messages",(double)matched,"pendingRequests",(double)(requests.size()-matched),"requestBytesParsed",(double)requestOffset,"responseBytesParsed",(double)responseOffset);}
}
