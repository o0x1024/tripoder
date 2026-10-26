package dev.tripoder.isolatedservicecontract;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.widget.TextView;
import org.json.*;
import java.nio.file.Files;
import java.util.concurrent.*;

public final class Main extends Activity {
    private final JSONObject checks=new JSONObject();private TextView text;
    private final class Connection implements ServiceConnection {
        final CountDownLatch ready=new CountDownLatch(1);IBinder peer;boolean bound;ComponentName component;
        public void onServiceConnected(ComponentName name,IBinder binder){component=name;peer=binder;ready.countDown();}
        public void onServiceDisconnected(ComponentName name){}
        public void onNullBinding(ComponentName name){ready.countDown();}
    }
    @Override public void onCreate(Bundle state){super.onCreate(state);text=new TextView(this);setContentView(text);text.setText("RUNNING OWNED ISOLATED SERVICE CONTRACT");new Thread(this::run,"owned-isolated-client").start();}
    private void check(String name,boolean value)throws Exception{checks.put(name,value);if(!value)throw new AssertionError(name);}
    private Bundle query(Connection connection)throws Exception{
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();try(var fd=ParcelFileDescriptor.open(new java.io.File(getFilesDir(),"owned-isolated-marker"),ParcelFileDescriptor.MODE_READ_ONLY)){data.writeInterfaceToken(OwnedService.WIRE);data.writeTypedObject(fd,0);if(!connection.peer.transact(1,data,reply,0))throw new IllegalStateException("OWNED_BINDER_UNSUPPORTED");reply.readException();return reply.readBundle(getClassLoader());}finally{data.recycle();reply.recycle();}
    }
    private Connection bind(String instance)throws Exception{
        Connection connection=new Connection();Intent request=new Intent("owned.isolated.BIND").setComponent(new ComponentName(this,OwnedService.class));
        connection.bound=bindIsolatedService(request,BIND_AUTO_CREATE,instance,getMainExecutor(),connection);check(instance+"-bind-accepted",connection.bound);
        check(instance+"-binder-connected",connection.ready.await(25,TimeUnit.SECONDS)&&connection.peer!=null);check(instance+"-logical-component",request.getComponent().equals(connection.component));return connection;
    }
    private void close(Connection value){if(value!=null&&value.bound){unbindService(value);value.bound=false;}}
    private void run(){Connection a=null,b=null,again=null;JSONObject report=new JSONObject();
        try{
            java.io.File marker=new java.io.File(getFilesDir(),"owned-isolated-marker");Files.writeString(marker.toPath(),"OWNED_PRIVATE_BOUNDARY\n");android.system.Os.chmod(marker.getPath(),0600);
            check("parent-numeric-dex-order",Integer.valueOf(2).equals(Class.forName("dev.tripoder.isolatedservicecontract.OwnedDexMarker",true,getClassLoader()).getMethod("answer").invoke(null)));a=bind("A");b=bind("B");again=bind("A");Bundle first=query(a),second=query(b),same=query(again);JSONArray observations=new JSONArray();for(Bundle value:new Bundle[]{first,second,same}){JSONObject observation=new JSONObject();for(String key:value.keySet())observation.put(key,value.get(key));observations.put(observation);}report.put("observations",observations);
            check("two-instances-real-distinct-isolated-uid",first.getBoolean("isolated")&&second.getBoolean("isolated")&&android.os.Process.isIsolatedUid(first.getInt("uid"))&&first.getInt("uid")!=second.getInt("uid")&&first.getInt("pid")!=second.getInt("pid"));
            check("same-instance-keeps-exact-service",first.getString("instance").equals(same.getString("instance"))&&first.getInt("pid")==same.getInt("pid")&&first.getInt("uid")==same.getInt("uid"));
            var processes=getSystemService(android.app.ActivityManager.class).getRunningAppProcesses();check("ordinary-process-view-survives-live-isolated-instances",processes!=null&&processes.stream().anyMatch(value->value.pid==android.os.Process.myPid()&&value.uid==android.os.Process.myUid()));
            for(Bundle value:new Bundle[]{first,second}){
                check((value==first?"A":"B")+"-factory-once-and-null-creation-intent",value.getBoolean("factoryCreation"));
                String label=value==first?"A":"B";check(label+"-scoped-physical-stop-result-observed",value.get("physicalStopResult") instanceof Boolean);check(label+"-numeric-dex-order-and-main-context-loader",value.getInt("dexMarker")==2&&value.getBoolean("contextLoader"));check(label+"-exact-application-and-service-once",value.getInt("creates")==1&&value.getInt("appCreates")==1&&value.getInt("binds")==1&&value.getBoolean("applicationIdentity"));
                check(label+"-main-thread-and-real-binder-caller",value.getBoolean("main")&&value.getInt("callerUid")==android.os.Process.myUid()&&value.getInt("ownerUid")==android.os.Process.myUid());
                check(label+"-fd-resources",value.getString("resource").equals("OWNED_ISOLATED_RESOURCE_OK"));check(label+"-owner-private-path-denied",value.getBoolean("privateDenied"));check(label+"-storage-facade-denied",value.getBoolean("storageDenied"));check(label+"-pm-facade-denied",value.getBoolean("pmDenied"));
            }
            close(a);Thread.sleep(250);check("one-unbind-retains-other-binding",query(again).getString("instance").equals(first.getString("instance")));
            CountDownLatch dead=new CountDownLatch(1);again.peer.linkToDeath(dead::countDown,0);close(again);check("last-unbind-confirms-real-death",dead.await(8,TimeUnit.SECONDS));
            check("other-isolated-instance-survives",query(b).getString("instance").equals(second.getString("instance")));close(b);report.put("passed",true);
        }catch(Throwable error){try{report.put("passed",false).put("error",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}android.util.Log.e("TripoderIsolatedFixture","OWNED_CONTRACT_FAILED",error);}
        finally{close(a);close(again);close(b);}
        try{report.put("checks",checks.length()).put("results",checks);android.util.Log.i("TripoderIsolatedFixture",report.toString());runOnUiThread(()->text.setText(report.toString()));}catch(Exception error){throw new IllegalStateException(error);}
    }
}
