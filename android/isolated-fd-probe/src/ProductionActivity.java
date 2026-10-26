package dev.tripoder.isolatedfdprobe;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.widget.TextView;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Imported unchanged into the different physical QA package for production backend acceptance. */
public final class ProductionActivity extends Activity {
    private TextView text;private final JSONArray observations=new JSONArray();private int checks;
    private final class Connection implements ServiceConnection {
        final CountDownLatch ready=new CountDownLatch(1);IBinder peer;ComponentName component;boolean bound;
        public void onServiceConnected(ComponentName name,IBinder binder){component=name;peer=binder;ready.countDown();}
        public void onServiceDisconnected(ComponentName name){}
        public void onNullBinding(ComponentName name){ready.countDown();}
    }
    private void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    @Override public void onCreate(Bundle state){super.onCreate(state);text=new TextView(this);setContentView(text);text.setText("RUNNING OWNED PRODUCTION ISOLATED JNI");new Thread(this::run,"owned-production-native-client").start();}
    private JSONObject one(boolean named)throws Exception{
        String challenge=UUID.randomUUID().toString(),instance=named?"NAMED":"PLAIN";Connection connection=new Connection();
        Intent intent=new Intent(this,ProductionService.class).setAction(named?"OWNED_NAMED":"OWNED_PLAIN");
        connection.bound=bindIsolatedService(intent,BIND_AUTO_CREATE,instance,getMainExecutor(),connection);check(connection.bound,"bind accepted");
        try{
            check(connection.ready.await(25,TimeUnit.SECONDS)&&connection.peer!=null,"real service binder connected");check(intent.getComponent().equals(connection.component),"logical component");
            CompletableFuture<JSONObject> result=new CompletableFuture<>();
            Binder callback=new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
                if(code!=1)return super.onTransact(code,data,reply,flags);data.enforceInterface(ProductionService.WIRE);String encoded=data.readString();data.enforceNoDataAvail();
                try{JSONObject value=new JSONObject(encoded);int uid=Binder.getCallingUid(),pid=Binder.getCallingPid();if(!challenge.equals(value.getString("challenge"))||uid!=value.getInt("uid")||pid!=value.getInt("pid")||!android.os.Process.isIsolatedUid(uid)||uid==android.os.Process.myUid()||value.getInt("ownerUid")!=android.os.Process.myUid())throw new SecurityException("OWNED_PRODUCTION_KERNEL_IDENTITY_CHANGED");value.put("kernelBinderIdentityVerified",true);result.complete(value);}catch(Exception failure){result.completeExceptionally(failure);}
                reply.writeNoException();return true;
            }};
            var completion=new CompletableFuture<Void>();new Thread(()->{Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{data.writeInterfaceToken(ProductionService.WIRE);data.writeString(challenge);data.writeStrongBinder(callback);if(!connection.peer.transact(1,data,reply,0))throw new RemoteException("OWNED_PRODUCTION_RUN_REJECTED");reply.readException();completion.complete(null);}catch(Throwable failure){completion.completeExceptionally(failure);}finally{data.recycle();reply.recycle();}},"owned-production-native-dispatch").start();
            JSONObject observed=result.get(20,TimeUnit.SECONDS);completion.get(2,TimeUnit.SECONDS);check(observed.getBoolean("actualGuestDefiningLoader"),"exact actual guest defining loader");
            JSONObject nativeChecks=observed.getJSONObject("checks");check(observed.getBoolean("passed")&&nativeChecks.length()==6,"six ART JNI checks");for(var keys=nativeChecks.keys();keys.hasNext();)check(nativeChecks.getBoolean(keys.next()),"ART JNI identity");
            CountDownLatch dead=new CountDownLatch(1);connection.peer.linkToDeath(dead::countDown,0);unbindService(connection);connection.bound=false;check(dead.await(8,TimeUnit.SECONDS),"real isolated death after last unbind");return observed;
        }finally{if(connection.bound)unbindService(connection);}
    }
    private void run(){JSONObject report=new JSONObject();try{
        JSONObject plain=one(false);observations.put(plain);JSONObject named=one(true);observations.put(named);check(plain.getInt("uid")!=named.getInt("uid")&&plain.getInt("pid")!=named.getInt("pid"),"fresh real isolated process per variant");report.put("passed",true);
    }catch(Throwable failure){try{report.put("passed",false).put("error",android.util.Log.getStackTraceString(failure));}catch(Exception ignored){}}
        try{report.put("checks",checks).put("observations",observations);String encoded=report.toString();android.util.Log.i("TripoderIsolatedNativeFixture",encoded);runOnUiThread(()->text.setText(encoded));}catch(Exception failure){throw new IllegalStateException(failure);}
    }
}
