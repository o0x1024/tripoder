package dev.tripoder.hooklab;

import android.content.*;
import android.content.pm.*;
import android.app.*;
import android.os.*;
import dev.tripoder.control.ControlWire;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Application-owned connection; no command is replayed after an uncertain Binder result. */
final class EngineConnection implements ServiceConnection {
    interface Listener {void changed();}
    interface Result {void complete(Bundle value,Exception error);}
    static final String ENGINE="dev.tripoder.engine",SERVICE="dev.tripoder.runtime.RuntimeControllerService";
    static final int START_ENGINE=4001;
    private final Context host;private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newFixedThreadPool(2);
    private final List<Listener> listeners=new CopyOnWriteArrayList<>();
    private IBinder life,root,session;private boolean bound,connecting;
    volatile String state="尚未连接",failure="",lease="";volatile Bundle status;
    EngineConnection(Context context){host=context.getApplicationContext();}
    void observe(Listener listener){listeners.add(listener);listener.changed();}
    void remove(Listener listener){listeners.remove(listener);}
    private void changed(){main.post(()->{for(Listener listener:listeners)listener.changed();});}
    synchronized boolean ready(){return session!=null;}
    void connect(Activity page){
        synchronized(this){if(connecting||session!=null)return;connecting=true;state="正在连接引擎";failure="";}changed();
        io.execute(()->{try{
            verifyEngine();
            main.post(()->{try{
                if(page.isFinishing()||page.isDestroyed())throw new IllegalStateException("ENGINE_START_PAGE_CLOSED");
                ActivityOptions options=ActivityOptions.makeBasic().setShareIdentityEnabled(true);
                page.startActivityForResult(new Intent().setComponent(new ComponentName(ENGINE,"dev.tripoder.runtime.RuntimeControllerGateway")),START_ENGINE,options.toBundle());
            }catch(Exception error){failed(error);}});
        }catch(Exception error){failed(error);}});
    }
    private int verifyEngine()throws Exception{
        PackageInfo info=host.getPackageManager().getPackageInfo(ENGINE,PackageManager.GET_SIGNING_CERTIFICATES);
        if(info.applicationInfo==null||info.applicationInfo.uid==android.os.Process.myUid()||info.signingInfo==null||info.signingInfo.hasMultipleSigners())throw new SecurityException("ENGINE_IDENTITY_REJECTED");
        Signature[] certs=info.signingInfo.getApkContentsSigners();if(certs.length!=1||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certs[0].toByteArray())).equals(WorkbenchBuild.ENGINE_CERT_SHA256))throw new SecurityException("ENGINE_CERTIFICATE_REJECTED");
        return info.applicationInfo.uid;
    }
    void startupResult(int result,Intent data){
        if(result!=Activity.RESULT_OK){failed(new IllegalStateException(data==null?"ENGINE_START_CANCELED":data.getStringExtra("error")));return;}
        synchronized(this){connecting=true;}
        io.execute(()->{try{
            int uid=verifyEngine();synchronized(this){life=new ControlWire.Owner(uid);}
            Intent intent=new Intent().setComponent(new ComponentName(ENGINE,SERVICE));
            main.post(()->{try{synchronized(this){if(bound){host.unbindService(this);bound=false;}bound=host.bindService(intent,this,Context.BIND_AUTO_CREATE);if(!bound)throw new IllegalStateException("ENGINE_BIND_REJECTED");}}catch(Exception error){failed(error);}});
        }catch(Exception error){failed(error);}});
    }
    @Override public void onServiceConnected(ComponentName name,IBinder peer){
        synchronized(this){root=peer;}io.execute(()->{try{
            Bundle input=new Bundle();input.putBinder("owner",life);Bundle connected=ControlWire.call(peer,ControlWire.CONNECT,input);IBinder leasePeer=connected.getBinder("session");
            if(leasePeer==null||connected.getInt("protocol")!=1||connected.getInt("engineUid")==android.os.Process.myUid())throw new SecurityException("ENGINE_HANDSHAKE_REJECTED");
            Bundle current=ControlWire.call(leasePeer,ControlWire.EXECUTE,ControlWire.request("status"));
            synchronized(this){if(root!=peer)throw new DeadObjectException();session=leasePeer;lease=connected.getString("lease");status=current;connecting=false;state="新内核已连接";failure="";}changed();
        }catch(Exception error){failed(error);}});
    }
    @Override public void onServiceDisconnected(ComponentName name){failed(new DeadObjectException());}
    @Override public void onNullBinding(ComponentName name){failed(new IllegalStateException("ENGINE_NULL_BINDING"));}
    @Override public void onBindingDied(ComponentName name){failed(new DeadObjectException());}
    private void failed(Exception error){synchronized(this){session=null;root=null;connecting=false;status=null;state="引擎连接不可用";failure=error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage());}changed();}
    void execute(Bundle request,Result callback){
        IBinder current;synchronized(this){current=session;}if(current==null){callback.complete(null,new IllegalStateException("ENGINE_NOT_CONNECTED"));return;}
        Bundle snapshot=new Bundle(request);io.execute(()->{Bundle result=null;Exception failure=null;try{result=ControlWire.call(current,ControlWire.EXECUTE,snapshot);}catch(Exception error){failure=error;if(error instanceof DeadObjectException)failed(error);}
            Bundle value=result;Exception error=failure;main.post(()->callback.complete(value,error));
        });
    }
    Bundle executeBlocking(Bundle request)throws Exception{
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("BLOCKING_CONTROL_ON_UI_THREAD");
        IBinder current;synchronized(this){current=session;}if(current==null)throw new IllegalStateException("ENGINE_NOT_CONNECTED");
        return ControlWire.call(current,ControlWire.EXECUTE,new Bundle(request));
    }
}
