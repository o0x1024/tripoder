package dev.tripoder.runtime;

import android.app.*;
import android.content.Intent;
import android.os.*;

/** Started lifetime belongs to the engine. A workbench page never owns or closes the broker. */
public final class RuntimeControllerService extends Service {
    private ControllerLease leases;
    private static volatile ControllerLease activeLeases;
    private static volatile RuntimeAgentSessions activeAgents;
    static RuntimeAgentSessions agents(){if(activeAgents==null)throw new IllegalStateException("AGENT_AUTHORITY_UNAVAILABLE");return activeAgents;}
    static void requireLease(String lease,int uid){ControllerLease current=activeLeases;if(current==null)throw new SecurityException("CONTROL_LEASE_REVOKED");current.require(lease,uid);}
    @Override public void onCreate(){
        super.onCreate();if(!Application.getProcessName().equals(getPackageName()+":authority"))throw new IllegalStateException("AUTHORITY_PROCESS_REQUIRED");
        activeAgents=new RuntimeAgentSessions(RuntimeAuthorityOwner.get(this));
        leases=new ControllerLease(this,(input,lease,uid)->RuntimeControllerCommands.execute(RuntimeAuthorityOwner.get(this),input,lease,uid));
        activeLeases=leases;
        NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("engine","Tripoder 运行引擎",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        Notification notification=new Notification.Builder(this,"engine").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Tripoder 引擎已连接")
            .setContentText("在工作台查看空间与运行状态").setOngoing(true).build();startForeground(1,notification);return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return leases.entry;}
    @Override public void onDestroy(){if(activeAgents!=null){activeAgents.close();activeAgents=null;}leases.close();if(activeLeases==leases)activeLeases=null;super.onDestroy();}
}
