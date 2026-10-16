package dev.tripoder.hooklab;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;
import dev.tripoder.control.AgentCrypto;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Only loopback, length-prefixed signed frames; no HTTP, shell, or arbitrary Binder proxy. */
public final class AgentControlService extends Service {
    private ServerSocket socket;
    private final ExecutorService acceptor=Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor clients=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4));
    private WorkbenchAgentControl control;
    private String startupFailure;
    private final java.util.Set<Socket> connections=ConcurrentHashMap.newKeySet();
    @Override public void onCreate(){super.onCreate();control=((WorkbenchApplication)getApplication()).agents;NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("agent-control","外部 Agent 调试",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&"stop".equals(intent.getAction())){control.revoke((reply,error)->stopSelf());return START_NOT_STICKY;}
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,AgentControlService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,"agent-control").setSmallIcon(android.R.drawable.ic_menu_manage).setContentTitle("Tripoder 外部调试入口已开启").setContentText("仅接受配对客户端；点击停止可撤销会话").addAction(new Notification.Action.Builder(null,"停止外部控制",stop).build()).setOngoing(true).build();startForeground(2,notification);
        if(socket==null)try{
            if(!((WorkbenchApplication)getApplication()).engine.ready())throw new IllegalStateException("ENGINE_NOT_CONNECTED");
            // Allow rebinding after closed connections enter TIME_WAIT. SO_REUSEPORT is not enabled;
            // a second live listener must still fail instead of sharing the authorization endpoint.
            control.deviceKey();socket=new ServerSocket();socket.setReuseAddress(true);socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),AgentCrypto.DEVICE_PORT),4);control.listening=true;control.message="等待桌面客户端请求配对";
            acceptor.execute(()->{while(!socket.isClosed())try{Socket client=socket.accept();connections.add(client);try{clients.execute(()->serve(client));}catch(RejectedExecutionException busy){connections.remove(client);client.close();}}catch(IOException ended){if(!socket.isClosed())control.message="连接监听失败："+ended.getClass().getSimpleName();break;}});
        }catch(Exception error){startupFailure="外部入口启动失败："+error.getClass().getSimpleName()+":"+error.getMessage();control.message=startupFailure;android.util.Log.w("TripoderAgent",startupFailure,error);stopSelf();}
        return START_NOT_STICKY;
    }
    private void serve(Socket socket){try(socket){
        socket.setSoTimeout(20_000);DataInputStream input=new DataInputStream(socket.getInputStream());int length=input.readInt();if(length<2||length>65536)throw new IOException("AGENT_FRAME_SIZE");byte[] bytes=input.readNBytes(length);if(bytes.length!=length)throw new EOFException();
        String message=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();byte[] reply=control.wire(new JSONObject(message)).toString().getBytes(StandardCharsets.UTF_8);if(reply.length>131072)throw new IOException("AGENT_RESPONSE_SIZE");
        DataOutputStream output=new DataOutputStream(socket.getOutputStream());output.writeInt(reply.length);output.write(reply);output.flush();
    }catch(Exception error){android.util.Log.w("TripoderAgent","Rejected or interrupted frame: "+error.getClass().getSimpleName());}finally{connections.remove(socket);}}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){control.listening=false;control.revoke((reply,error)->{});try{if(socket!=null)socket.close();}catch(IOException error){android.util.Log.w("TripoderAgent","Listener close failed",error);}acceptor.shutdownNow();clients.shutdownNow();for(Socket client:connections)try{client.close();}catch(IOException error){android.util.Log.w("TripoderAgent","Client close failed",error);}connections.clear();control.closed();if(startupFailure!=null)control.message=startupFailure;super.onDestroy();}
}
