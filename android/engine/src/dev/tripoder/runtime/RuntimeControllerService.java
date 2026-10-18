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
    static void requireLease(String lease){ControllerLease current=activeLeases;if(current==null)throw new SecurityException("CONTROL_LEASE_REVOKED");current.require(lease);}
    @Override public void onCreate(){
        super.onCreate();if(!Application.getProcessName().equals(getPackageName()+":authority"))throw new IllegalStateException("AUTHORITY_PROCESS_REQUIRED");
        activeAgents=new RuntimeAgentSessions(RuntimeAuthorityOwner.get(this));
        leases=new ControllerLease(this,(input,lease)->RuntimeControllerCommands.execute(RuntimeAuthorityOwner.get(this),input,lease));
        activeLeases=leases;
        NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("engine","Tripoder 运行引擎",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        // The only ongoing notification of the package; tapping returns to the workbench task.
        Intent open=getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent page=open==null?null:PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,"engine").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Tripoder 运行中")
            .setContentText("点击返回工作台查看空间与运行状态").setContentIntent(page).setOngoing(true).build();startForeground(1,notification);return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return leases.entry;}
    @Override protected void dump(java.io.FileDescriptor fd,java.io.PrintWriter out,String[] args){
        if(args==null||args.length!=1||!"threads".equals(args[0])){super.dump(fd,out,args);return;}
        // Invoked through Android's DUMP-protected service path. No broker lock,
        // database contents, capabilities, or guest values are included.
        var stacks=Thread.getAllStackTraces();var threads=new java.util.ArrayList<>(stacks.keySet());
        threads.sort(java.util.Comparator.comparingLong(Thread::getId));
        for(int i=0;i<Math.min(threads.size(),128);i++){
            Thread thread=threads.get(i);out.println("thread="+thread.getId()+" name="+thread.getName()+" state="+thread.getState());
            StackTraceElement[] stack=stacks.get(thread);for(int j=0;j<Math.min(stack.length,64);j++)out.println("  "+stack[j]);
        }
        out.println("threads="+threads.size()+" truncated="+(threads.size()>128));
    }
    @Override public void onDestroy(){if(activeAgents!=null){activeAgents.close();activeAgents=null;}leases.close();if(activeLeases==leases)activeLeases=null;super.onDestroy();}
}
