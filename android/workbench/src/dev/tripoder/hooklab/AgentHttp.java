package dev.tripoder.hooklab;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.net.Socket;
import org.json.*;

/** Bounded HTTP/1.1 framing; one request per connection. No hidden retries. */
final class AgentHttp {
    static final int BODY_LIMIT=65536;
    private static final class BadRequest extends IOException {}
    static void serve(Socket socket,AgentNetworkIdentity identity,AgentMcp mcp)throws Exception{
        socket.setSoTimeout(5000);
        try{
            InputStream input=socket.getInputStream();String first=line(input,2048);String[] request=first.split(" ");if(request.length!=3||!request[2].equals("HTTP/1.1"))throw new BadRequest();
            Map<String,String> headers=new HashMap<>();int budget=first.length();
            for(;;){String header=line(input,8192);budget+=header.length()+2;if(budget>8192)throw new BadRequest();if(header.isEmpty())break;int colon=header.indexOf(':');if(colon<1||!header.substring(0,colon).matches("[A-Za-z0-9-]+"))throw new BadRequest();String name=header.substring(0,colon).toLowerCase(Locale.ROOT);if(headers.putIfAbsent(name,header.substring(colon+1).trim())!=null)throw new BadRequest();}
            if(!request[1].equals("/mcp")){send(socket,404,null,null);return;}
            if(!(identity.ip+":"+AgentNetworkIdentity.PORT).equals(headers.get("host"))){send(socket,403,null,null);return;}
            String origin=headers.get("origin");if(origin!=null&&!origin.equals("http://"+identity.ip+":"+AgentNetworkIdentity.PORT)){send(socket,403,null,null);return;}
            String session=headers.getOrDefault("mcp-session-id","");
            if(request[0].equals("GET")){send(socket,405,null,null);return;}
            if(request[0].equals("DELETE")){send(socket,mcp.remove(session)?204:404,null,null);return;}
            if(!request[0].equals("POST")){send(socket,405,null,null);return;}
            String accept=headers.getOrDefault("accept","");if(!accept.contains("application/json")||!accept.contains("text/event-stream")){send(socket,406,null,null);return;}
            if(!headers.getOrDefault("content-type","").split(";",2)[0].trim().equalsIgnoreCase("application/json")){send(socket,415,null,null);return;}
            socket.setSoTimeout(20000);byte[] bytes=body(input,headers);JSONObject message;
            try{message=new JSONObject(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString());}catch(Exception invalid){send(socket,400,AgentMcp.error(null,-32700,"Invalid JSON-RPC object"),null);return;}
            AgentMcp.Reply reply=mcp.handle(message,session,headers.getOrDefault("mcp-protocol-version",""));send(socket,reply.status(),reply.body(),reply.session());
        }catch(BadRequest|NumberFormatException invalid){send(socket,400,null,null);}
    }
    private static byte[] body(InputStream input,Map<String,String> headers)throws Exception{
        String transfer=headers.get("transfer-encoding"),length=headers.get("content-length");if(transfer!=null&&length!=null)throw new BadRequest();
        if(transfer!=null){
            if(!transfer.equalsIgnoreCase("chunked"))throw new BadRequest();ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            for(;;){String chunk=line(input,128);if(!chunk.matches("[0-9A-Fa-f]{1,8}"))throw new BadRequest();long size=Long.parseLong(chunk,16);if(size>BODY_LIMIT-bytes.size())throw new BadRequest();if(size==0){if(!line(input,128).isEmpty())throw new BadRequest();return bytes.toByteArray();}byte[] part=input.readNBytes((int)size);if(part.length!=size||!line(input,2).isEmpty())throw new BadRequest();bytes.write(part);}
        }
        if(length==null||!length.matches("[0-9]{1,8}"))throw new BadRequest();int count=Integer.parseInt(length);if(count>BODY_LIMIT)throw new BadRequest();byte[] bytes=input.readNBytes(count);if(bytes.length!=count)throw new BadRequest();return bytes;
    }
    private static String line(InputStream input,int max)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();for(int i=0;i<=max;i++){int value=input.read();if(value<0)throw new EOFException();if(value==13){if(input.read()!=10)throw new BadRequest();return out.toString(StandardCharsets.US_ASCII);}if(value<32||value>126)throw new BadRequest();out.write(value);}throw new BadRequest();
    }
    private static void send(Socket socket,int status,JSONObject body,String session)throws IOException{
        byte[] bytes=body==null?new byte[0]:body.toString().getBytes(StandardCharsets.UTF_8);
        String headers="HTTP/1.1 "+status+" "+switch(status){case 200->"OK";case 202->"Accepted";case 204->"No Content";case 400->"Bad Request";case 403->"Forbidden";case 404->"Not Found";case 405->"Method Not Allowed";case 406->"Not Acceptable";case 415->"Unsupported Media Type";default->"Service Unavailable";}+"\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\nCache-Control: no-store\r\n"+(session==null?"":"Mcp-Session-Id: "+session+"\r\n")+(status==405?"Allow: POST, DELETE\r\n":"")+"\r\n";
        OutputStream output=socket.getOutputStream();output.write(headers.getBytes(StandardCharsets.US_ASCII));output.write(bytes);output.flush();
    }
}
