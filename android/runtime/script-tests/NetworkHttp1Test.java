package dev.tripoder.runtime.script;

import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.tripoder.runtime.script.ScriptContractTest.*;

final class NetworkHttp1Test {
    static byte[] bytes(String text){return text.getBytes(StandardCharsets.ISO_8859_1);}
    static void run(){
        String chunk="POST /chunk HTTP/1.1\r\nHost: owned\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nab\r\n1\r\nc\r\n0\r\nX-Owned: yes\r\n\r\n";
        for(int size=0;size<chunk.length();size++)check(NetworkHttp1.read(bytes(chunk.substring(0,size)),0,true,"").state().equals("INCOMPLETE"),"partial chunked frame waits for bytes "+size);
        var parsed=NetworkHttp1.read(bytes(chunk+"tail"),0,true,"");check(parsed.state().equals("COMPLETE")&&parsed.next()==chunk.length()&&parsed.value().get("bodyBytes").equals(3d),"chunked prefix exact boundary");
        String writes="GET /first HTTP/1.1\r\nHost: owned\r\n\r\nHEAD /second HTTP/1.1\r\nHost: owned\r\n\r\n"+chunk;
        String interim="HTTP/1.1 103 Early Hints\r\nLink: </owned>\r\n\r\n";
        String reads=interim+"HTTP/1.1 200 OK\r\nContent-Length: 1\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2\r\n\r\nxHTTP/1.1 200 OK\r\nContent-Length: 999\r\n\r\nHTTP/1.1 204 No Content\r\n\r\n";
        List<Map<String,Object>> messages=new ArrayList<>();var conversation=new NetworkHttpConversation("c1",row->{messages.add(row);return true;});
        // Response callbacks may be scheduled before a prior write's callback.
        conversation.drain(new byte[0],bytes(reads),false);check(messages.isEmpty(),"responses wait for their request metadata");
        for(int i=0;i<=writes.length();i++)conversation.drain(bytes(writes.substring(0,i)),bytes(reads),false);
        check(messages.size()==3&&conversation.status().get("state").equals("IN_PROGRESS"),"three messages emitted before TLS closes");
        @SuppressWarnings("unchecked") var info=(List<Map<String,Object>>)messages.get(0).get("informational");
        check(info.size()==1&&info.get(0).get("rawHex").equals(HexFormat.of().formatHex(bytes(interim))),"interim response belongs to first final response");
        @SuppressWarnings("unchecked") var head=(Map<String,Object>)messages.get(1).get("response");check(head.get("bodyBytes").equals(0d)&&head.get("wireBytes").equals((double)"HTTP/1.1 200 OK\r\nContent-Length: 999\r\n\r\n".length()),"HEAD ignores advertised representation length");
        conversation.drain(bytes(writes),bytes(reads),true);check(conversation.status().get("state").equals("COMPLETE"),"persistent stream fully associated in request order");
        for(String malformed:List.of(chunk.replace("Transfer-Encoding: chunked","Transfer-Encoding: chunked\r\nContent-Length: 3"),chunk.replace("2\r\nab","z\r\nab"),chunk.replace("2\r\nab","2;foo=bar\r\nab"),chunk.replace("X-Owned: yes","Content-Length: 1"),chunk.replace("Host: owned","Host: owned\u0000"))){check(NetworkHttp1.read(bytes(malformed),0,true,"").state().equals("UNSUPPORTED"),"ambiguous or unsupported chunk framing stops parsing");}
        check(NetworkHttp1.read(bytes("HTTP/1.1 101 Switching Protocols\r\n\r\n"),0,false,"GET").reason().equals("PROTOCOL_UPGRADE"),"upgrade never becomes next HTTP message");
        check(NetworkHttp1.read(bytes("HTTP/1.1 200 OK\r\n\r\n"),0,false,"CONNECT").reason().equals("CONNECT_TUNNEL"),"CONNECT tunnel explicit");
        check(NetworkHttp1.read(bytes("HTTP/1.1 200 OK\r\n\r\nabc"),0,false,"GET").reason().equals("CLOSE_DELIMITED_RESPONSE_NOT_SUPPORTED"),"no guessing close-delimited message on live TLS");
        var partial=new NetworkHttpConversation("c2",row->true);partial.drain(bytes("GET / HTTP/1.1\r\n\r\n"),bytes("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nx"),true);check(partial.status().get("state").equals("INCOMPLETE"),"closed partial response stays incomplete");
        var limited=new NetworkHttpConversation("c3",row->false);limited.drain(bytes(NetworkCaptureTest.REQUEST),bytes(NetworkCaptureTest.RESPONSE),false);check(limited.status().get("reason").equals("HTTP_MESSAGE_LIMIT"),"message budget explicit");
        var session=NetworkCaptureTest.session();NetworkCaptureTest.event(session,"network.open","1");NetworkCaptureTest.bytes(session,"1","write",NetworkCaptureTest.REQUEST);NetworkCaptureTest.bytes(session,"1","read",NetworkCaptureTest.RESPONSE);
        check(((List<?>)session.messages(0,1).get("exchanges")).size()==1&&NetworkCaptureTest.rows(session).isEmpty(),"MCP message cursor independent of connection close cursor");
    }
}
