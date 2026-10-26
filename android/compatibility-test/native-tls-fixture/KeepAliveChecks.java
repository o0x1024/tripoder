package dev.tripoder.nativetlsfixture;

import android.content.Context;
import dev.tripoder.mtlsfixture.TlsMaterial;
import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.json.*;

/** Two simultaneous mTLS connections: sequential reuse and real request pipelining. */
final class KeepAliveChecks {
    private static volatile String result="{\"state\":\"READY\"}",expected="{}";
    private static volatile CountDownLatch release=new CountDownLatch(0);
    private static final AtomicInteger ready=new AtomicInteger();
    static void begin(boolean held){ready.set(0);release=new CountDownLatch(held?1:0);result="{\"state\":\"RUNNING\"}";}
    static String summary(){return ready.get()==2&&result.equals("{\"state\":\"RUNNING\"}")?"{\"state\":\"WAITING_RELEASE\",\"liveConnections\":2}":result;}
    static String expected(){return expected;}
    static String release(){release.countDown();return "RELEASED";}
    static void clientReady()throws Exception{ready.incrementAndGet();if(!release.await(60,TimeUnit.SECONDS))throw new IOException("CAPTURE_RELEASE_TIMEOUT");}
    static void execute(Context context){try{result=run(context).toString();}catch(Exception error){try{result=new JSONObject().put("passed",false).put("error",error.toString()).toString();}catch(JSONException impossible){throw new IllegalStateException(impossible);}finally{release.countDown();}}}
    private static byte[] asset(Context context,String name)throws IOException{try(var in=context.getAssets().open(name)){return in.readAllBytes();}}
    private record Scenario(String[] requests,String[] responses,String[] finals,String interim){}
    private static Scenario scenario(String id,int connection){
        String root="/keepalive/"+id+"/"+connection+"/",body="owned-"+id+"-"+connection;
        String[] requests={"GET "+root+"1 HTTP/1.1\r\nHost: localhost\r\n\r\n","HEAD "+root+"2 HTTP/1.1\r\nHost: localhost\r\n\r\n","GET "+root+"3 HTTP/1.1\r\nHost: localhost\r\n\r\n"};
        String hints="HTTP/1.1 103 Early Hints\r\nLink: </owned>\r\n\r\n";
        String one="HTTP/1.1 200 OK\r\nContent-Length: "+body.length()+"\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2\r\n\r\n"+body;
        String two="HTTP/1.1 200 OK\r\nContent-Length: 999\r\n\r\n";
        String three="HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"+Integer.toHexString(body.length())+"\r\n"+body+"\r\n0\r\nX-Owned: yes\r\n\r\n";
        return new Scenario(requests,new String[]{hints+one,two,three},new String[]{one,two,three},hints);
    }
    private static JSONObject run(Context context)throws Exception{
        NativeClient.probe();String id=UUID.randomUUID().toString();var serverContext=new TlsMaterial(name->context.getAssets().open(name)).context("server","ca");
        byte[] ca=asset(context,"ca.crt"),cert=asset(context,"client.crt"),key=asset(context,"client.der");
        ExecutorService threads=Executors.newFixedThreadPool(4);CountDownLatch handshakes=new CountDownLatch(2);
        List<Future<String>> servers=new ArrayList<>();List<Future<String[]>> clients=new ArrayList<>();List<SSLServerSocket> listeners=new ArrayList<>();
        JSONArray exchanges=new JSONArray(),messages=new JSONArray();List<Scenario> scenarios=List.of(scenario(id,0),scenario(id,1));
        for(int c=0;c<2;c++){Scenario s=scenarios.get(c);exchanges.put(new JSONObject().put("request",String.join("",s.requests)).put("response",String.join("",s.responses)));
            for(int i=0;i<3;i++)messages.put(new JSONObject().put("connectionIndex",c).put("messageIndex",i+1).put("request",s.requests[i]).put("response",s.finals[i]).put("informational",i==0?new JSONArray().put(s.interim):new JSONArray()));}
        expected=new JSONObject().put("runId",id).put("exchanges",exchanges).put("messages",messages).toString();
        try{
            for(int c=0;c<2;c++){
                final boolean pipeline=c==1;Scenario s=scenarios.get(c);
                SSLServerSocket listener=(SSLServerSocket)serverContext.getServerSocketFactory().createServerSocket(0,1,InetAddress.getByName("127.0.0.1"));listeners.add(listener);
                listener.setNeedClientAuth(true);listener.setEnabledProtocols(new String[]{"TLSv1.3"});listener.setSoTimeout(10000);
                servers.add(threads.submit(()->{try(SSLSocket socket=(SSLSocket)listener.accept()){
                    socket.setSoTimeout(10000);socket.startHandshake();if(!socket.getSession().getPeerPrincipal().getName().equals("CN=owned-client")||!socket.getSession().getProtocol().equals("TLSv1.3"))throw new IOException("CLIENT_AUTH");
                    handshakes.countDown();if(!handshakes.await(10,TimeUnit.SECONDS))throw new IOException("CONCURRENT_CONNECTIONS_REQUIRED");
                    StringBuilder received=new StringBuilder();
                    if(pipeline){for(String request:s.requests){String actual=readRequest(socket.getInputStream());if(!actual.equals(request))throw new IOException("PIPELINED_REQUEST_MISMATCH");received.append(actual);}for(String response:s.responses)write(socket.getOutputStream(),response);}
                    else for(int i=0;i<3;i++){String actual=readRequest(socket.getInputStream());if(!actual.equals(s.requests[i]))throw new IOException("SEQUENTIAL_REQUEST_MISMATCH");received.append(actual);write(socket.getOutputStream(),s.responses[i]);}
                    if(!release.await(60,TimeUnit.SECONDS))throw new IOException("SERVER_RELEASE_TIMEOUT");return received.toString();
                }}));
                clients.add(threads.submit(()->NativeClient.exchange(listener.getLocalPort(),ca,cert,key,s.requests,Arrays.stream(s.responses).mapToInt(String::length).toArray(),pipeline)));
            }
            for(int c=0;c<2;c++){Scenario s=scenarios.get(c);if(!servers.get(c).get(70,TimeUnit.SECONDS).equals(String.join("",s.requests))||!Arrays.equals(clients.get(c).get(70,TimeUnit.SECONDS),s.responses))throw new IOException("EXACT_MESSAGES_MISMATCH");}
            return new JSONObject(expected).put("passed",true).put("concurrentConnections",2).put("messagesPerConnection",3).put("clientAuthenticated",true).put("protocol","TLSv1.3").put("modes",new JSONArray().put("sequential").put("pipelined"));
        }finally{for(var listener:listeners)listener.close();threads.shutdownNow();}
    }
    private static void write(OutputStream out,String value)throws IOException{byte[] bytes=value.getBytes(StandardCharsets.US_ASCII);for(int offset=0;offset<bytes.length;offset+=37){out.write(bytes,offset,Math.min(37,bytes.length-offset));out.flush();}}
    private static String readRequest(InputStream in)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int matched=0;byte[] ending={13,10,13,10};
        while(bytes.size()<4096){int value=in.read();if(value<0)throw new EOFException();bytes.write(value);matched=value==ending[matched]?matched+1:(value==13?1:0);if(matched==4)return bytes.toString(StandardCharsets.US_ASCII);}throw new IOException("REQUEST_HEADER_LIMIT");
    }
}
