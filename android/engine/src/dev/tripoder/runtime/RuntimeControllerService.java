package dev.tripoder.runtime;

import android.app.*;
import android.content.Intent;
import android.os.*;
import dev.tripoder.control.*;

/** Started lifetime belongs to the engine. A workbench page never owns or closes the broker. */
public final class RuntimeControllerService extends Service {
    private ControllerLease leases;
    private static volatile ControllerLease activeLeases;
    private static volatile RuntimeAgentSessions activeAgents;
    static RuntimeAgentSessions agents(){if(activeAgents==null)throw new IllegalStateException("AGENT_AUTHORITY_UNAVAILABLE");return activeAgents;}
    static void requireLease(String lease){ControllerLease current=activeLeases;if(current==null)throw new SecurityException("CONTROL_LEASE_REVOKED");current.require(lease);}
    @Override public void onCreate(){
        super.onCreate();if(!Application.getProcessName().equals(getPackageName()+":authority"))throw new IllegalStateException("AUTHORITY_PROCESS_REQUIRED");
        leases=new ControllerLease(this,(input,lease)->RuntimeControllerCommands.execute(RuntimeAuthorityOwner.get(this),input,lease));
        activeLeases=leases;
        NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("engine","Tripoder 运行引擎",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){stopSelf(startId);return START_NOT_STICKY;}
    private final IBinder lifecycle=new ControlWire.Endpoint(){
        @Override protected void authorize(int code,int pid,int uid){
            if(uid==android.os.Process.myUid()){
                var rows=getSystemService(ActivityManager.class).getRunningAppProcesses();
                if(rows!=null)for(var row:rows)if(row.pid==pid&&row.uid==uid&&row.processName.equals(getPackageName()+":recovery"))return;
            }
            throw new SecurityException("RECOVERY_CALLER_REJECTED");
        }
        @Override protected Bundle handle(int code,Bundle input,int pid,int uid)throws Exception{
            String epoch=ControlWire.required(input,"epoch");
            if(code==RecoveryWire.INITIALIZE){
                if(input.size()!=2||input.getBinder("supervisor")==null)throw new IllegalArgumentException("RECOVERY_PERMIT_INVALID");
                RuntimeRecoveryGate.begin(epoch,input.getBinder("supervisor"));
                try{var broker=RuntimeAuthorityOwner.get(RuntimeControllerService.this);
                    activeAgents=new RuntimeAgentSessions(broker);RuntimeRecoveryGate.complete();RuntimeControllerLaunch.startMaintenance(broker);
                }catch(Exception|Error failure){RuntimeRecoveryGate.failed();throw failure;}
                Bundle result=new Bundle();result.putString("epoch",epoch);result.putBinder("entry",leases.entry);return result;
            }
            if(!RuntimeRecoveryGate.matches(epoch))throw new IllegalStateException("RECOVERY_ATTEMPT_STALE");
            if(code==RecoveryWire.ABORT){RuntimeRecoveryGate.failed();new Thread(()->{SystemClock.sleep(20);android.os.Process.killProcess(android.os.Process.myPid());},"recovery-abort").start();return new Bundle();}
            if(code==RecoveryWire.HEALTH){RuntimeRecoveryGate.require();var broker=RuntimeAuthorityOwner.get(RuntimeControllerService.this);synchronized(broker){try(var c=broker.store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM operations",null)){if(!c.moveToFirst())throw new IllegalStateException("RECOVERY_HEALTH_FAILED");}}
                Bundle result=new Bundle();result.putString("epoch",epoch);return result;}
            throw new IllegalArgumentException("RECOVERY_OPERATION_UNSUPPORTED");
        }
    };
    @Override public IBinder onBind(Intent intent){return RecoveryWire.LIFECYCLE.equals(intent.getAction())?lifecycle:leases.entry;}
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
    @Override public void onDestroy(){if(activeAgents!=null){activeAgents.close();activeAgents=null;}if(leases!=null)leases.close();if(activeLeases==leases)activeLeases=null;super.onDestroy();}
}
