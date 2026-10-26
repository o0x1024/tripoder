package dev.tripoder.runtime.script;

import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.tripoder.runtime.script.ScriptContractTest.*;

final class NetworkCaptureTest {
    static final String REQUEST="POST /owned HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n\r\nabc";
    static final String RESPONSE="HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nxyz";
    static NetworkCaptureSession session(){return new NetworkCaptureSession("capture",(tag,payload)->m("state","COMPLETE","artifactId","owned"));}
    static void event(NetworkCaptureSession s,String tag,Object payload){s.event(m("tag",tag,"payload",payload));}
    static void begin(NetworkCaptureSession s,String pointer,String direction){event(s,"network.begin",m("connection",pointer,"direction",direction));}
    static void bytes(NetworkCaptureSession s,String pointer,String direction,String text){begin(s,pointer,direction);chunk(s,pointer,direction,text,"COMPLETE",false);}
    static void chunk(NetworkCaptureSession s,String pointer,String direction,String text,String state,boolean truncated){byte[] bytes=text.getBytes(StandardCharsets.ISO_8859_1);event(s,"network.bytes",m("connection",pointer,"direction",direction,"buffer",m("state",state,"result",Integer.toString(bytes.length),"capturedBytes",(double)bytes.length,"hex",HexFormat.of().formatHex(bytes),"truncated",truncated)));}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(NetworkCaptureSession s){return (List<Map<String,Object>>)s.query(0,16).get("connections");}
    static void run()throws Throwable{
        NetworkCaptureSession s=session();event(s,"network.open","11");bytes(s,"11","write",REQUEST.substring(0,20));bytes(s,"11","write",REQUEST.substring(20));bytes(s,"11","read",RESPONSE);
        check(rows(s).isEmpty(),"live connection not frozen under a consumed cursor");event(s,"network.close","11");Map<String,Object> row=rows(s).get(0);
        check(row.get("httpState").equals("COMPLETE")&&row.get("byteState").equals("COMPLETE"),"framed exact passive exchange");
        check(row.get("requestHex").equals(HexFormat.of().formatHex(REQUEST.getBytes(StandardCharsets.ISO_8859_1))),"split data retains exact bytes");
        event(s,"network.open","11");bytes(s,"11","write",REQUEST);event(s,"network.close","11");
        check(!rows(s).get(0).get("connectionId").equals(rows(s).get(1).get("connectionId")),"reused pointer has a new lifetime id");
        check(((List<?>)s.query(1,1).get("connections")).size()==1&&s.query(2,1).get("hasMore").equals(false),"capture cursor progresses");
        try{s.query(3,1);throw new AssertionError("future cursor accepted");}catch(HookPreflightRejected expected){check(true,"future cursor rejected");}
        NetworkCaptureSession partial=session();event(partial,"network.open","1");bytes(partial,"1","write",REQUEST);begin(partial,"1","read");chunk(partial,"1","read",RESPONSE,"COMPLETE",true);event(partial,"network.close","1");
        check(rows(partial).get(0).get("httpState").equals("INCOMPLETE"),"truncation cannot be called complete even when retained bytes parse");
        NetworkCaptureSession unavailable=session();event(unavailable,"network.open","1");begin(unavailable,"1","read");chunk(unavailable,"1","read","xx","UNAVAILABLE",false);event(unavailable,"network.close","1");
        check(((List<?>)rows(unavailable).get(0).get("reasons")).contains("UNAVAILABLE"),"memory failure retained");
        NetworkCaptureSession concurrent=session();event(concurrent,"network.open","1");begin(concurrent,"1","read");begin(concurrent,"1","read");chunk(concurrent,"1","read","a","COMPLETE",false);chunk(concurrent,"1","read","b","COMPLETE",false);event(concurrent,"network.close","1");
        check(((List<?>)rows(concurrent).get(0).get("reasons")).contains("CONCURRENT_SAME_DIRECTION"),"same direction overlap does not imply stream order");
        NetworkCaptureSession orphan=session();bytes(orphan,"1","read","x");check(orphan.status().get("coverage").equals("INCOMPLETE")&&rows(orphan).isEmpty(),"midstream bytes never invent an observed lifetime");
        NetworkCaptureSession stopped=session();event(stopped,"network.open","1");stopped.stop();check(rows(stopped).get(0).get("byteState").equals("INCOMPLETE"),"stop finalizes open connection as incomplete");
        NetworkCaptureSession quota=session();for(int i=0;i<17;i++)event(quota,"network.open",Integer.toString(i+1));check(quota.status().get("droppedConnections").equals(1d),"connection budget observable");
        NetworkCaptureSession size=session();event(size,"network.open","1");for(int i=0;i<5;i++)bytes(size,"1","read","x".repeat(1024));event(size,"network.close","1");check(((List<?>)rows(size).get(0).get("reasons")).contains("CAPTURE_BYTE_LIMIT"),"stream byte cap observable");
        NetworkCaptureSession failed=new NetworkCaptureSession("capture",(tag,payload)->{throw new IllegalStateException();});event(failed,"network.open","1");event(failed,"network.close","1");check(failed.status().get("captureError").equals("NETWORK_ARTIFACT_WRITE_FAILED"),"persistence failure visible without altering original I/O");
        for(String unsupported:List.of(REQUEST+REQUEST,REQUEST.replace("Content-Length: 3","Transfer-Encoding: chunked"),REQUEST.replace("Host: localhost","Host: localhost\r\nHost: other"),"PRI * HTTP/2.0\r\n\r\n"))check(!NetworkHttp1.inspect(unsupported.getBytes(StandardCharsets.ISO_8859_1),true).get("state").equals("COMPLETE"),"unsupported framing is explicit");
        // Configurable limits (next capture only) and HAR 1.2 export.
        NetworkCaptureSession wide=new NetworkCaptureSession("capture",(tag,payload)->m("state","COMPLETE","artifactId","owned"),40,100,131072);
        for(int i=0;i<40;i++)event(wide,"network.open",Integer.toString(i+1));
        check(wide.status().get("droppedConnections").equals(0d)&&((Map<?,?>)wide.status().get("limits")).get("connections").equals(40d)&&((Map<?,?>)wide.status().get("limits")).get("messagesPerCapture").equals(100d),"raised connection/message limits are applied and reported");
        NetworkCaptureSession narrow=new NetworkCaptureSession("capture",(tag,payload)->m("state","COMPLETE","artifactId","owned"),2,32,65536);
        for(int i=0;i<3;i++)event(narrow,"network.open",Integer.toString(i+1));check(narrow.status().get("droppedConnections").equals(1d),"lowered connection limit is enforced");
        String JSON_REQUEST="POST /v1/items?id=7&mode=x HTTP/1.1\r\nHost: api.example.test\r\nContent-Type: application/json\r\nContent-Length: 7\r\n\r\n{\"a\":1}";
        String JSON_RESPONSE="HTTP/1.1 201 Created\r\nContent-Type: application/json\r\nContent-Length: 7\r\nLocation: /v1/items/7\r\n\r\n{\"b\":2}";
        NetworkCaptureSession har=session();event(har,"network.open","5");bytes(har,"5","write",JSON_REQUEST);bytes(har,"5","read",JSON_RESPONSE);
        Map<String,Object> page=har.har(0,16);@SuppressWarnings("unchecked") Map<String,Object> log=(Map<String,Object>)((Map<String,Object>)page.get("har")).get("log");
        @SuppressWarnings("unchecked") List<Map<String,Object>> entries=(List<Map<String,Object>>)log.get("entries");
        check(log.get("version").equals("1.2")&&entries.size()==1,"HAR page contains the retained exchange");
        @SuppressWarnings("unchecked") Map<String,Object> harRequest=(Map<String,Object>)entries.get(0).get("request"),harResponse=(Map<String,Object>)entries.get(0).get("response");
        check(harRequest.get("method").equals("POST")&&harRequest.get("url").equals("https://api.example.test/v1/items?id=7&mode=x")&&((List<?>)harRequest.get("queryString")).size()==2,"HAR request method/url/query");
        check(((Map<?,?>)harRequest.get("postData")).get("text").equals("{\"a\":1}")&&harRequest.get("bodySize").equals(7d),"HAR request body is the exact payload");
        check(harResponse.get("status").equals(201d)&&harResponse.get("statusText").equals("Created")&&harResponse.get("redirectURL").equals("/v1/items/7")&&((Map<?,?>)harResponse.get("content")).get("text").equals("{\"b\":2}"),"HAR response status/location/body");
        check(entries.get(0).get("_tripoderTimeSource").equals("CAPTURE_SESSION_START_APPROXIMATION")&&entries.get(0).get("time").equals(-1d),"HAR does not invent per-message timing");
        try{har.har(2,1);throw new AssertionError("future HAR cursor accepted");}catch(HookPreflightRejected expected){check(true,"future HAR cursor rejected");}
        check(NetworkHttp1.inspect(REQUEST.substring(0,REQUEST.length()-1).getBytes(StandardCharsets.ISO_8859_1),true).get("state").equals("INCOMPLETE"),"short body incomplete");
    }
}
