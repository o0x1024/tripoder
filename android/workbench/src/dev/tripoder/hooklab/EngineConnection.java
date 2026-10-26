package dev.tripoder.hooklab;

import android.content.*;
import android.app.*;
import android.os.*;
import dev.tripoder.control.ControlWire;
import dev.tripoder.control.RecoveryWire;
import java.util.*;
import java.util.concurrent.*;

/** Application-owned connection to the :authority process of this same package; no command is replayed after an uncertain Binder result. */
final class EngineConnection implements ServiceConnection {
    interface Listener {void changed();}
    interface Result {void complete(Bundle value,Exception error);}
    static final String SERVICE="dev.tripoder.runtime.RuntimeControllerService";
    /** Always this APK's own id, so an alternate id builds against itself and never reaches another install. */
    static String engine(Context context){return context.getPackageName();}
    private final Context host;private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newFixedThreadPool(2);
    private final ExecutorService lifecycleIo=Executors.newFixedThreadPool(2);
    private final List<Listener> listeners=new CopyOnWriteArrayList<>();
    private IBinder life,root,session;private boolean bound,connecting;
    private IBinder recovery;private boolean polling,handshaking;
    private String recoveryState="";private final Runnable poll=this::pollRecovery;
    volatile String state="尚未连接",failure="",lease="";volatile Bundle status;
    EngineConnection(Context context){host=context.getApplicationContext();}
    void observe(Listener listener){listeners.add(listener);listener.changed();}
    void remove(Listener listener){listeners.remove(listener);}
    private void changed(){main.post(()->{for(Listener listener:listeners)listener.changed();});}
    synchronized boolean ready(){return session!=null;}
    void connect(Activity page){
        connect(page,true);
    }
    void connectInitially(Activity page){connect(page,false);}
    private void connect(Activity page,boolean explicit){
        synchronized(this){if(connecting||session!=null)return;connecting=true;state="正在连接引擎";failure="";}changed();
        try{
            // Called from a workbench page, so the authority's foreground service start is user-initiated.
            Intent intent=new Intent().setClassName(host,RecoveryWire.SERVICE);
            synchronized(this){life=new ControlWire.Owner();if(explicit)intent.setAction("retry-once");}
            if(page.startForegroundService(intent)==null)throw new IllegalStateException("ENGINE_START_REJECTED");
            synchronized(this){if(bound){host.unbindService(this);bound=false;}bound=host.bindService(intent,this,Context.BIND_AUTO_CREATE);if(!bound)throw new IllegalStateException("ENGINE_BIND_REJECTED");}
        }catch(Exception error){failed(error);}
    }
    void stopRecovery(Result callback){
        IBinder current;synchronized(this){current=recovery;}if(current==null){callback.complete(null,new IllegalStateException("ENGINE_NOT_CONNECTED"));return;}
        lifecycleIo.execute(()->{try{Bundle result=ControlWire.call(current,RecoveryWire.STOP,new Bundle());main.post(()->{synchronized(this){session=null;root=null;lease="";status=null;}pollRecovery();callback.complete(result,null);});}
            catch(Exception error){main.post(()->callback.complete(null,error));}});
    }
    @Override public void onServiceConnected(ComponentName name,IBinder peer){
        synchronized(this){recovery=peer;polling=false;}main.removeCallbacks(poll);pollRecovery();
    }
    private void pollRecovery(){
        IBinder supervisor;synchronized(this){if(polling||recovery==null)return;polling=true;supervisor=recovery;}
        lifecycleIo.execute(()->{try{
            Bundle value=ControlWire.call(supervisor,RecoveryWire.STATUS,new Bundle());
            main.post(()->{IBinder authority=value.getBinder("entry");boolean connect=false,notify;
                synchronized(this){if(recovery!=supervisor)return;String prior=state+"\n"+failure+"\n"+recoveryState;boolean wasReady=session!=null;polling=false;recoveryState=value.getString("state","");
                    if(authority==null){session=null;root=null;status=null;lease="";connecting=false;
                        state=switch(recoveryState){case "STARTING"->"引擎正在恢复";case "BACKOFF"->"等待恢复，第 "+(value.getInt("automaticAttempts")+1)+"/3 次";case "OPEN"->"自动恢复已停止";case "BLOCKED"->"引擎恢复受阻";default->"引擎已停止";};failure=value.getString("reason","");
                    }else if(!authority.equals(root)&&!handshaking){root=authority;handshaking=true;connect=true;}
                    notify=wasReady!=(session!=null)||!prior.equals(state+"\n"+failure+"\n"+recoveryState);}
                if(connect)handshake(authority);else if(notify)changed();main.postDelayed(poll,500);
            });
        }catch(Exception failure){main.post(()->{synchronized(this){if(recovery!=supervisor)return;polling=false;}if(failure instanceof DeadObjectException)supervisorLost(failure);else failed(failure);});}});
    }
    private void handshake(IBinder peer){
        io.execute(()->{try{
            Bundle input=new Bundle();input.putBinder("owner",life);Bundle connected=ControlWire.call(peer,ControlWire.CONNECT,input);IBinder leasePeer=connected.getBinder("session");
            if(leasePeer==null||connected.getInt("protocol")!=1)throw new SecurityException("ENGINE_HANDSHAKE_REJECTED");
            Bundle current=ControlWire.call(leasePeer,ControlWire.EXECUTE,ControlWire.request("status"));
            synchronized(this){if(root!=peer)throw new DeadObjectException();session=leasePeer;lease=connected.getString("lease");status=current;connecting=false;state="新内核已连接";failure="";}changed();
        }catch(Exception error){synchronized(this){if(root!=peer)return;}failed(error);}finally{synchronized(this){handshaking=false;}}});
    }
    @Override public void onServiceDisconnected(ComponentName name){supervisorLost(new DeadObjectException());}
    @Override public void onNullBinding(ComponentName name){supervisorLost(new IllegalStateException("ENGINE_NULL_BINDING"));}
    @Override public void onBindingDied(ComponentName name){supervisorLost(new DeadObjectException());}
    private void supervisorLost(Exception error){
        // Release BIND_AUTO_CREATE too: an unhealthy supervisor must not become its own restart loop.
        synchronized(this){recovery=null;polling=false;if(bound){bound=false;try{host.unbindService(this);}catch(IllegalArgumentException ignored){}}}
        main.removeCallbacks(poll);failed(error);
    }
    private void failed(Exception error){synchronized(this){session=null;root=null;connecting=false;status=null;state="引擎连接不可用";failure=UiText.error(error);}changed();}
    void execute(Bundle request,Result callback){
        IBinder current;synchronized(this){current=session;}if(current==null){callback.complete(null,new IllegalStateException("ENGINE_NOT_CONNECTED"));return;}
        Bundle snapshot=new Bundle(request);io.execute(()->{Bundle result=null;Exception failure=null;try{result=ControlWire.call(current,ControlWire.EXECUTE,snapshot);}catch(Exception error){failure=error;synchronized(this){if(current==session&&error instanceof DeadObjectException)failed(error);}}
            Bundle value=result;Exception error=failure;main.post(()->{synchronized(this){if(current!=session){callback.complete(null,new IllegalStateException("ENGINE_RESPONSE_STALE_QUERY_REQUIRED"));return;}}callback.complete(value,error);});
        });
    }
    Bundle executeBlocking(Bundle request)throws Exception{
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("BLOCKING_CONTROL_ON_UI_THREAD");
        IBinder current;synchronized(this){current=session;}if(current==null)throw new IllegalStateException("ENGINE_NOT_CONNECTED");
        Bundle value=ControlWire.call(current,ControlWire.EXECUTE,new Bundle(request));synchronized(this){if(current!=session)throw new IllegalStateException("ENGINE_RESPONSE_STALE_QUERY_REQUIRED");}return value;
    }
}
