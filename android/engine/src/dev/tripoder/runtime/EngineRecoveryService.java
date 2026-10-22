package dev.tripoder.runtime;

import android.app.*;
import android.content.*;
import android.os.*;
import dev.tripoder.control.*;
import java.util.concurrent.*;

/** Unhooked lifecycle owner. Only persisted permits may construct a product authority. */
public final class EngineRecoveryService extends Service {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final IBinder owner=new Binder();
    private EngineRecoveryStore store;private EngineRecoveryPolicy policy;
    private volatile IBinder root;private Attempt attempt;private String boot,error="";private boolean closed;
    private long lastHealth;private volatile boolean healthBusy;
    @Override public void onCreate(){
        super.onCreate();
        if(!Application.getProcessName().equals(getPackageName()+":recovery"))throw new IllegalStateException("RECOVERY_PROCESS_REQUIRED");
        try{
            boot="boot-"+android.provider.Settings.Global.getInt(getContentResolver(),android.provider.Settings.Global.BOOT_COUNT);
            store=new EngineRecoveryStore(this);policy=new EngineRecoveryPolicy(store);policy.interrupted();
        }catch(Exception failure){error="RECOVERY_STORAGE_UNAVAILABLE:"+failure.getClass().getSimpleName();}
        main.post(this::tick);
    }
    private final IBinder entry=new ControlWire.Endpoint(){
        @Override protected void authorize(int code,int pid,int uid){ControllerTrust.require(EngineRecoveryService.this,pid,uid);}
        @Override protected Bundle handle(int code,Bundle input,int pid,int uid)throws Exception{
            if(input==null||!input.isEmpty())throw new IllegalArgumentException("RECOVERY_FIELDS_UNSUPPORTED");
            if(code==RecoveryWire.STATUS)return snapshot();
            if(policy==null)throw new IllegalStateException(error);
            FutureTask<Bundle> change=new FutureTask<>(()->{
                if(code==RecoveryWire.ACTIVATE||code==RecoveryWire.RETRY_ONCE){policy.activate(SystemClock.elapsedRealtime(),boot,code==RecoveryWire.RETRY_ONCE);tick();return snapshot();}
                if(code==RecoveryWire.STOP){policy.stop();root=null;abortCurrent();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return snapshot();}
                throw new IllegalArgumentException("RECOVERY_OPERATION_UNSUPPORTED");
            });
            main.post(change);try{return change.get(5,TimeUnit.SECONDS);}catch(ExecutionException failure){throw new IllegalStateException("RECOVERY_CHANGE_FAILED",failure.getCause());}
        }
    };
    private Bundle snapshot(){Bundle out=new Bundle();out.putInt("supervisorPid",android.os.Process.myPid());
        if(policy==null){out.putString("state","BLOCKED");out.putString("reason",error);return out;}
        synchronized(policy){out.putString("state",policy.state);out.putString("reason",policy.reason);out.putString("epoch",policy.epoch);out.putInt("automaticAttempts",policy.automaticAttempts);out.putInt("totalAttempts",policy.totalAttempts);out.putLong("nextAt",policy.nextAt);
            if(policy.state.equals("READY")&&root!=null&&root.isBinderAlive())out.putBinder("entry",root);}
        return out;
    }
    @Override public IBinder onBind(Intent intent){return entry;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        var manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("engine","Tripoder 运行引擎",NotificationManager.IMPORTANCE_LOW));
        Intent open=getPackageManager().getLaunchIntentForPackage(getPackageName());PendingIntent page=open==null?null:PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE);
        startForeground(1,new Notification.Builder(this,"engine").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Tripoder 引擎恢复管理")
            .setContentText("点击查看引擎状态与待确认操作").setContentIntent(page).setOngoing(true).build());
        try{if(policy!=null&&intent!=null&&(policy.totalAttempts==0||intent.getAction()!=null))policy.activate(SystemClock.elapsedRealtime(),boot,"retry-once".equals(intent.getAction()));}catch(Exception failure){storageFailed(failure);}
        if(policy!=null&&policy.state.equals("STOPPED")){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);}
        main.post(this::tick);return START_NOT_STICKY;
    }
    private void tick(){
        if(closed)return;
        try{
            if(policy!=null){long now=SystemClock.elapsedRealtime();String state=policy.state;
                if(state.equals("BACKOFF")){policy.retryDue(now,boot);state=policy.state;}
                if(state.equals("STARTING")&&attempt==null)startAttempt();
                if(state.equals("STARTING")&&now-policy.startedAt>=EngineRecoveryPolicy.START_MS)blockAndAbort("AUTHORITY_START_TIMEOUT_DEATH_UNCONFIRMED");
                if(state.equals("READY")&&!healthBusy&&now-lastHealth>=10000)health();
            }
        }catch(Exception failure){storageFailed(failure);}
        main.removeCallbacksAndMessages(tickToken);main.postAtTime(this::tick,tickToken,SystemClock.uptimeMillis()+500);
    }
    private final Object tickToken=new Object();
    private void startAttempt(){
        Attempt next=new Attempt(policy.epoch);attempt=next;
        android.util.Log.i("TripoderRecovery","ATTEMPT total="+policy.totalAttempts+" automatic="+policy.automaticAttempts+" epoch="+policy.epoch);
        try{next.bound=bindService(new Intent(this,RuntimeControllerService.class).setAction(RecoveryWire.LIFECYCLE),next,Context.BIND_AUTO_CREATE);
            if(!next.bound){next.deathConfirmed=true;failed(next,"AUTHORITY_BIND_REJECTED",false);}
        }catch(Exception failure){next.deathConfirmed=true;failed(next,"AUTHORITY_BIND_REJECTED",false);}
    }
    private void awaitWorkers()throws Exception{
        long deadline=Math.min(policy.startedAt+EngineRecoveryPolicy.START_MS,SystemClock.elapsedRealtime()+15000);
        while(true){boolean live=false;var rows=getSystemService(ActivityManager.class).getRunningAppProcesses();if(rows==null)throw new IllegalStateException("PROCESS_OBSERVATION_UNAVAILABLE");
            for(var row:rows)if(row.uid==android.os.Process.myUid()&&row.processName.startsWith(getPackageName()+":slot"))live=true;
            if(!live)return;if(SystemClock.elapsedRealtime()>=deadline)throw new IllegalStateException("ORPHAN_PROCESS_STILL_LIVE");Thread.sleep(50);
        }
    }
    private final class Attempt implements ServiceConnection {
        final String epoch;volatile IBinder peer;boolean bound,deathConfirmed;String failure="AUTHORITY_DIED";
        Attempt(String epoch){this.epoch=epoch;}
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            if(attempt!=this||!policy.state.equals("STARTING")){unbind(this);return;}
            if(peer!=null){blockAndAbort("UNPERMITTED_SYSTEM_REBIND");return;}peer=binder;
            try{binder.linkToDeath(()->main.post(()->{deathConfirmed=true;failed(this,failure,false);}),0);}catch(RemoteException dead){deathConfirmed=true;failed(this,failure,false);return;}
            io.execute(()->{try{
                awaitWorkers();Bundle input=new Bundle();input.putString("epoch",epoch);input.putBinder("supervisor",owner);
                Bundle result=ControlWire.call(binder,RecoveryWire.INITIALIZE,input);
                main.post(()->{if(attempt!=this||!policy.state.equals("STARTING"))return;
                    try{if(!epoch.equals(result.getString("epoch"))||result.getBinder("entry")==null||!binder.isBinderAlive())throw new IllegalStateException("AUTHORITY_READY_UNCONFIRMED");
                        policy.ready(epoch,SystemClock.elapsedRealtime());root=result.getBinder("entry");lastHealth=0;
                    }catch(Exception failure){blockAndAbort("AUTHORITY_READY_UNCONFIRMED");}
                });
            }catch(Exception problem){main.post(()->{if(attempt!=this)return;failure="AUTHORITY_INITIALIZATION_FAILED:"+String.valueOf(problem.getMessage());blockAndAbort(failure);});}});
        }
        @Override public void onServiceDisconnected(ComponentName name){if(peer!=null&&!peer.isBinderAlive()){deathConfirmed=true;failed(this,failure,false);}else blockAndAbort("AUTHORITY_DISCONNECTED_DEATH_UNCONFIRMED");}
        @Override public void onBindingDied(ComponentName name){onServiceDisconnected(name);}
        @Override public void onNullBinding(ComponentName name){blockAndAbort("AUTHORITY_NULL_BINDING");}
    }
    private void failed(Attempt value,String reason,boolean permanent){
        if(attempt!=value)return;
        if(!value.deathConfirmed){blockAndAbort("AUTHORITY_DEATH_UNCONFIRMED");return;}
        root=null;unbind(value);attempt=null;healthBusy=false;
        try{policy.failed(value.epoch,SystemClock.elapsedRealtime(),reason,permanent);}catch(Exception problem){storageFailed(problem);}
    }
    private void unbind(Attempt value){if(value.bound){value.bound=false;try{unbindService(value);}catch(IllegalArgumentException ignored){}}}
    private void abortCurrent(){Attempt current=attempt;root=null;if(current==null)return;unbind(current);
        if(current.peer!=null&&current.peer.isBinderAlive())new Thread(()->{try{Bundle input=new Bundle();input.putString("epoch",current.epoch);ControlWire.call(current.peer,RecoveryWire.ABORT,input);}catch(Exception ignored){}},"authority-abort").start();
    }
    private void blockAndAbort(String reason){root=null;try{if(policy!=null)policy.block(reason);}catch(Exception problem){storageFailed(problem);}abortCurrent();}
    private void health(){
        Attempt current=attempt;if(current==null)return;healthBusy=true;lastHealth=SystemClock.elapsedRealtime();
        io.execute(()->{try{Bundle input=new Bundle();input.putString("epoch",current.epoch);Bundle result=ControlWire.call(current.peer,RecoveryWire.HEALTH,input);
            main.post(()->{healthBusy=false;if(attempt==current&&policy.state.equals("READY"))try{if(!current.epoch.equals(result.getString("epoch")))throw new IllegalStateException("HEALTH_EPOCH_MISMATCH");policy.stable(current.epoch,SystemClock.elapsedRealtime());}catch(Exception failure){blockAndAbort("AUTHORITY_HEALTH_FAILED");}});
        }catch(Exception failure){main.post(()->{healthBusy=false;if(attempt==current&&!current.peer.isBinderAlive()){current.deathConfirmed=true;failed(current,"AUTHORITY_DIED",false);}});}});
        main.postDelayed(()->{if(attempt==current&&healthBusy)blockAndAbort("AUTHORITY_HEALTH_TIMEOUT");},30000);
    }
    private void storageFailed(Exception problem){error="RECOVERY_STORAGE_UNAVAILABLE";root=null;if(policy!=null){policy.state="BLOCKED";policy.reason=error;}android.util.Log.e("TripoderRecovery",error,problem);abortCurrent();}
    @Override public void onDestroy(){closed=true;main.removeCallbacksAndMessages(null);if(attempt!=null)unbind(attempt);io.shutdownNow();if(store!=null)store.close();super.onDestroy();}
}
