package dev.tripoder.httpcontract;

import android.content.*;
import android.net.http.*;
import android.os.Handler;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** The real SDK client and one owned loopback server. No external request or target app data. */
public final class HttpChecks {
    private static final byte[] BODY="owned-sdk-http-response".getBytes(StandardCharsets.US_ASCII);
    private static final class ObservedContext extends ContextWrapper {
        final JSONArray registrations=new JSONArray();
        ObservedContext(Context base){super(base);}
        @Override public Context getApplicationContext(){return this;}
        private synchronized void record(BroadcastReceiver receiver,IntentFilter filter,String permission,Handler handler,int flags){
            if(receiver==null)return;
            try{JSONArray actions=new JSONArray();for(int i=0;i<filter.countActions();i++)actions.put(filter.getAction(i));registrations.put(new JSONObject().put("class",receiver.getClass().getName()).put("canonicalBootLoader",receiver.getClass().getClassLoader()==Class.class.getClassLoader()).put("actions",actions).put("flags",flags).put("permissionNull",permission==null).put("handlerNull",handler==null));}
            catch(JSONException error){throw new IllegalStateException(error);}
        }
        @Override public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter){record(receiver,filter,null,null,0);return super.registerReceiver(receiver,filter);}
        @Override public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter,int flags){record(receiver,filter,null,null,flags);return super.registerReceiver(receiver,filter,flags);}
        @Override public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter,String permission,Handler handler){record(receiver,filter,permission,handler,0);return super.registerReceiver(receiver,filter,permission,handler);}
        @Override public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter,String permission,Handler handler,int flags){record(receiver,filter,permission,handler,flags);return super.registerReceiver(receiver,filter,permission,handler,flags);}
    }
    public static JSONObject inspect(Context context){
        JSONObject out=new JSONObject(),checks=new JSONObject();ObservedContext observed=new ObservedContext(context);
        ExecutorService executor=Executors.newSingleThreadExecutor();HttpEngine engine=null;UrlRequest request=null;
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            server.setSoTimeout(15000);String path="/"+UUID.randomUUID();AtomicReference<Throwable> serverFailure=new AtomicReference<>();CountDownLatch served=new CountDownLatch(1);
            Thread serving=new Thread(()->{
                try(var socket=server.accept()){
                    socket.setSoTimeout(10000);InputStream input=socket.getInputStream();ByteArrayOutputStream header=new ByteArrayOutputStream();int value;
                    while(header.size()<8192&&(value=input.read())!=-1){header.write(value);byte[] bytes=header.toByteArray();int n=bytes.length;if(n>=4&&bytes[n-4]==13&&bytes[n-3]==10&&bytes[n-2]==13&&bytes[n-1]==10)break;}
                    if(!header.toString(StandardCharsets.US_ASCII).startsWith("GET "+path+" HTTP/1.1\r\n"))throw new IOException("OWNED_HTTP_REQUEST_IDENTITY");
                    OutputStream response=socket.getOutputStream();response.write(("HTTP/1.1 200 OK\r\nContent-Length: "+BODY.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));response.write(BODY);response.flush();
                }catch(Throwable error){serverFailure.set(error);}finally{served.countDown();}
            },"owned-http-server");serving.start();
            engine=new HttpEngine.Builder(observed).setEnableQuic(false).setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISABLED,0).build();
            checks.put("real-sdk-http-engine-created",true);
            CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();AtomicInteger successes=new AtomicInteger(),status=new AtomicInteger();ByteArrayOutputStream body=new ByteArrayOutputStream();
            UrlRequest.Callback callback=new UrlRequest.Callback(){
                public void onRedirectReceived(UrlRequest request,UrlResponseInfo info,String location){failure.set(new IOException("OWNED_HTTP_UNEXPECTED_REDIRECT"));request.cancel();}
                public void onResponseStarted(UrlRequest request,UrlResponseInfo info){status.set(info.getHttpStatusCode());request.read(ByteBuffer.allocateDirect(4096));}
                public void onReadCompleted(UrlRequest request,UrlResponseInfo info,ByteBuffer buffer)throws Exception{buffer.flip();byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);if(body.size()+bytes.length>65536)throw new IOException("OWNED_HTTP_BODY_LIMIT");body.write(bytes);buffer.clear();request.read(buffer);}
                public void onSucceeded(UrlRequest request,UrlResponseInfo info){successes.incrementAndGet();done.countDown();}
                public void onFailed(UrlRequest request,UrlResponseInfo info,HttpException error){failure.set(error);done.countDown();}
                public void onCanceled(UrlRequest request,UrlResponseInfo info){failure.compareAndSet(null,new IOException("OWNED_HTTP_CANCELED"));done.countDown();}
            };
            request=engine.newUrlRequestBuilder("http://127.0.0.1:"+server.getLocalPort()+path,executor,callback).build();request.start();
            checks.put("real-sdk-loopback-request-completed",done.await(15,TimeUnit.SECONDS)&&failure.get()==null&&successes.get()==1);
            checks.put("owned-loopback-server-served-exact-request",served.await(1,TimeUnit.SECONDS)&&serverFailure.get()==null);
            checks.put("real-sdk-response-status-and-owned-body",status.get()==200&&Arrays.equals(BODY,body.toByteArray()));
            if(failure.get()!=null)out.put("requestErrorType",failure.get().getClass().getName());
            if(serverFailure.get()!=null)out.put("serverErrorType",serverFailure.get().getClass().getName());
            if(!request.isDone()){request.cancel();done.await(2,TimeUnit.SECONDS);}
            engine.shutdown();engine=null;checks.put("real-sdk-http-engine-shutdown-returned",true);
            boolean passed=true;for(Iterator<String> names=checks.keys();names.hasNext();)passed&=checks.getBoolean(names.next());out.put("passed",passed);
        }catch(Throwable error){try{out.put("passed",false).put("errorType",error.getClass().getName());}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
        finally{
            if(request!=null&&!request.isDone())request.cancel();
            if(engine!=null)try{engine.shutdown();}catch(RuntimeException error){try{out.put("passed",false).put("shutdownErrorType",error.getClass().getName());}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
            executor.shutdownNow();
        }
        try{return out.put("checks",checks).put("registeredReceivers",observed.registrations).put("accountDataRead",false).put("externalRequests",0);}catch(JSONException impossible){throw new IllegalStateException(impossible);}
    }
    private HttpChecks(){}
}
