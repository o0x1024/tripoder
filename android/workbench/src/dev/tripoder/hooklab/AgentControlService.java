package dev.tripoder.hooklab;

import android.app.*;
import android.content.Intent;
import android.net.*;
import android.os.IBinder;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/** LAN HTTP MCP endpoint shared with the stdio adapter. */
public final class AgentControlService extends Service {
    private ServerSocket socket;private AgentNetworkIdentity identity;private AgentMcp mcp;
    private final ExecutorService acceptor=Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor clients=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4));
    private final java.util.Set<Socket> connections=ConcurrentHashMap.newKeySet();
    private WorkbenchAgentControl control;private String startupFailure;private ConnectivityManager.NetworkCallback networkCallback;
    @Override public void onCreate(){super.onCreate();control=((WorkbenchApplication)getApplication()).agents;getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("agent-control","外部 Agent 连接",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&"stop".equals(intent.getAction())){control.listening=false;getSharedPreferences("mcp-network",MODE_PRIVATE).edit().putBoolean("enabled",false).commit();stopSelf();return START_NOT_STICKY;}
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,AgentControlService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
        startForeground(2,new Notification.Builder(this,"agent-control").setSmallIcon(android.R.drawable.ic_menu_manage).setContentTitle("Tripoder MCP 网络入口").setContentText("支持 stdio 和 HTTP；停止后应用继续运行").addAction(new Notification.Action.Builder(null,"停止连接",stop).build()).setOngoing(true).build());
        if(socket==null)try{
            if(!AgentNetworkAccess.granted(this))throw new IllegalStateException("LOCAL_NETWORK_PERMISSION_REQUIRED：请允许访问本地网络，再开启网络 MCP");
            if(!((WorkbenchApplication)getApplication()).engine.ready())throw new IllegalStateException("ENGINE_NOT_CONNECTED");
            identity=AgentNetworkIdentity.create(this);mcp=new AgentMcp(this,control);
            socket=new ServerSocket();socket.setReuseAddress(true);socket.bind(new InetSocketAddress(identity.ip,AgentNetworkIdentity.PORT),4);
            control.networkIdentity=identity;control.listening=true;control.message="stdio / HTTP 已就绪\n"+identity.endpoint();
            networkCallback=new ConnectivityManager.NetworkCallback(){
                @Override public void onLost(Network network){verifyNetwork();}
                @Override public void onLinkPropertiesChanged(Network network,LinkProperties links){verifyNetwork();}
                private void verifyNetwork(){if(identity!=null&&!identity.addressStillPresent(AgentControlService.this)){startupFailure="网络地址已变化，请重新开启入口并导出连接配置";getSharedPreferences("mcp-network",MODE_PRIVATE).edit().putBoolean("enabled",false).commit();stopSelf();}}
            };
            getSystemService(ConnectivityManager.class).registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(),networkCallback);
            acceptor.execute(()->{while(!socket.isClosed())try{Socket client=(Socket)socket.accept();connections.add(client);try{clients.execute(()->serve(client));}catch(RejectedExecutionException busy){connections.remove(client);client.close();}}catch(IOException ended){if(!socket.isClosed()){startupFailure="MCP_LISTENER_FAILED";stopSelf();}break;}});
        }catch(Exception error){startupFailure="网络入口未开启："+UiText.error(error);getSharedPreferences("mcp-network",MODE_PRIVATE).edit().putBoolean("enabled",false).commit();stopSelf();}
        return START_NOT_STICKY;
    }
    private void serve(Socket client){try(client){AgentHttp.serve(client,identity,mcp);}catch(Exception error){android.util.Log.w("TripoderAgent","Connection interrupted: "+error.getClass().getSimpleName());}finally{connections.remove(client);}}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        if(mcp!=null)mcp.close();
        control.listening=false;control.networkIdentity=null;if(networkCallback!=null)getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
        try{if(socket!=null)socket.close();}catch(IOException ignored){}acceptor.shutdownNow();clients.shutdownNow();for(Socket client:connections)try{client.close();}catch(IOException ignored){}connections.clear();
        control.revoke((reply,error)->{});control.closed();if(startupFailure!=null)control.message=startupFailure;super.onDestroy();
    }
}
