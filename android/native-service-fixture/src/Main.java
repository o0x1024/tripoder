package dev.tripoder.nativeservicefixture;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.*;
import android.util.Log;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Owned oracle for a native-only service hosted by the runtime: every assertion is independently observable from the client. */
public final class Main extends Activity {
    private static final String TAG="TripoderNativeServiceFixture",DESCRIPTOR="dev.tripoder.nativeservicefixture.Echo";
    private final List<String> failed=new ArrayList<>();private int checks;
    private final String pkg="dev.tripoder.nativeservicefixture";
    private void check(String name,boolean ok){checks++;if(!ok){failed.add(name);}Log.i(TAG,"{\"check\":\""+name+"\",\"ok\":"+ok+"}");}
    private final class Link implements ServiceConnection {
        final CountDownLatch connected=new CountDownLatch(1);volatile IBinder binder;volatile boolean nullBinding,died;
        @Override public void onServiceConnected(ComponentName name,IBinder service){binder=service;connected.countDown();}
        @Override public void onServiceDisconnected(ComponentName name){died=true;}
        @Override public void onNullBinding(ComponentName name){nullBinding=true;connected.countDown();}
    }
    private Link bind(String component,String action,String instance){
        Link link=new Link();Intent intent=new Intent().setComponent(new ComponentName(pkg,pkg+"."+component));if(action!=null)intent.setAction(action);
        boolean bound=bindIsolatedService(intent,Context.BIND_AUTO_CREATE,instance,getMainExecutor(),link);
        if(!bound)throw new IllegalStateException("BIND_REFUSED:"+component+"/"+instance);
        return link;
    }
    private static boolean await(Link link){try{return link.connected.await(20,TimeUnit.SECONDS)&&link.binder!=null;}catch(InterruptedException e){return false;}}
    private static String ask(IBinder binder,int code)throws RemoteException{Parcel in=Parcel.obtain(),out=Parcel.obtain();try{in.writeInterfaceToken(DESCRIPTOR);binder.transact(IBinder.FIRST_CALL_TRANSACTION+code,in,out,0);return out.readString();}finally{in.recycle();out.recycle();}}
    private static int echo(IBinder binder,int value)throws RemoteException{Parcel in=Parcel.obtain(),out=Parcel.obtain();try{in.writeInterfaceToken(DESCRIPTOR);in.writeInt(value);binder.transact(IBinder.FIRST_CALL_TRANSACTION+1,in,out,0);return out.readInt();}finally{in.recycle();out.recycle();}}
    private static Map<String,String> parse(String report){Map<String,String> map=new HashMap<>();for(String part:report.split(" ")){int at=part.indexOf('=');if(at>0)map.put(part.substring(0,at),part.substring(at+1));}return map;}
    private static boolean isolatedUid(int uid){int app=uid%100000;return app>=90000&&app<=99999;}
    private static boolean dead(IBinder binder,long ms){long end=SystemClock.elapsedRealtime()+ms;while(SystemClock.elapsedRealtime()<end){if(!binder.pingBinder())return true;SystemClock.sleep(100);}return !binder.pingBinder();}
    private void run(){
        List<Link> open=new ArrayList<>();
        try{
            Link a=bind("NativeEcho","dev.tripoder.nativeservicefixture.A","a");open.add(a);
            check("native-service-binds-and-connects",await(a));
            IBinder ba=a.binder;String descriptor=ba==null?null:ba.getInterfaceDescriptor();
            check("native-service-publishes-its-own-binder-descriptor",DESCRIPTOR.equals(descriptor));
            Map<String,String> ra=parse(ask(ba,0));int uidA=Integer.parseInt(ra.get("uid")),pidA=Integer.parseInt(ra.get("pid"));
            check("native-service-runs-in-a-real-isolated-uid",isolatedUid(uidA));
            check("native-service-runs-in-another-process",pidA!=android.os.Process.myPid()&&uidA!=android.os.Process.myUid());
            check("native-service-receives-the-bind-action",("dev.tripoder.nativeservicefixture.A").equals(ra.get("action")));
            check("native-service-binder-transacts-with-data",echo(ba,41)==42);
            Map<String,String> ca=parse(ask(ba,2));
            check("native-service-entry-ran-once-and-bound-once",("1").equals(ca.get("created"))&&("1").equals(ca.get("binds"))&&("0").equals(ca.get("destroys")));

            Link b=bind("NativeEcho","dev.tripoder.nativeservicefixture.A","b");open.add(b);
            check("second-instance-connects",await(b));
            Map<String,String> rb=parse(ask(b.binder,0));int uidB=Integer.parseInt(rb.get("uid")),pidB=Integer.parseInt(rb.get("pid"));
            check("second-instance-is-another-real-isolated-process",isolatedUid(uidB)&&pidB!=pidA&&uidB!=uidA);
            check("second-instance-has-its-own-native-state",("1").equals(parse(ask(b.binder,2)).get("created")));

            Link a2=bind("NativeEcho","dev.tripoder.nativeservicefixture.B","a");open.add(a2);
            check("same-instance-new-intent-connects",await(a2));
            Map<String,String> ra2=parse(ask(a2.binder,0));
            check("same-instance-new-intent-reaches-the-same-process",Integer.parseInt(ra2.get("pid"))==pidA);
            check("same-instance-new-intent-delivers-its-own-action",("dev.tripoder.nativeservicefixture.B").equals(ra2.get("action")));
            check("same-instance-new-intent-calls-onBind-again-without-recreating",parse(ask(a2.binder,2)).get("binds").equals("2")&&parse(ask(a2.binder,2)).get("created").equals("1"));

            Link d=bind("DefaultNames",null,"d");open.add(d);
            check("default-named-entry-point-connects",await(d));
            check("default-named-entry-point-transacts",d.binder!=null&&echo(d.binder,6)==7&&isolatedUid(Integer.parseInt(parse(ask(d.binder,0)).get("uid"))));

            DeathWatch watch=new DeathWatch();ba.linkToDeath(watch,0);
            unbindService(a);unbindService(a2);open.remove(a);open.remove(a2);
            check("unbinding-every-client-ends-the-real-process",watch.await(20000)||dead(ba,5000));
            check("another-instance-survives-that-death",b.binder.pingBinder()&&echo(b.binder,1)==2);

            Link again=bind("NativeEcho","dev.tripoder.nativeservicefixture.A","a");open.add(again);
            check("same-instance-name-after-death-connects",await(again));
            Map<String,String> rg=parse(ask(again.binder,0));
            check("same-instance-name-after-death-is-a-fresh-process",Integer.parseInt(rg.get("pid"))!=pidA&&("1").equals(parse(ask(again.binder,2)).get("created")));
        }catch(Throwable error){Log.e(TAG,"fixture failure",error);check("fixture-completes-without-exception:"+error.getClass().getSimpleName()+":"+error.getMessage(),false);}
        finally{for(Link link:open)try{unbindService(link);}catch(RuntimeException ignored){}}
        try{
            JSONObject result=new JSONObject().put("passed",failed.isEmpty()).put("checks",checks).put("failedSections",new JSONArray(failed));
            Log.i(TAG,result.toString());
        }catch(JSONException error){Log.e(TAG,"json",error);}
        runOnUiThread(()->{setTitle(failed.isEmpty()?"native service: PASS":"native service: FAIL");});
    }
    private static final class DeathWatch implements IBinder.DeathRecipient {
        private final CountDownLatch latch=new CountDownLatch(1);
        @Override public void binderDied(){latch.countDown();}
        boolean await(long ms){try{return latch.await(ms,TimeUnit.MILLISECONDS);}catch(InterruptedException e){return false;}}
    }
    @Override protected void onCreate(Bundle state){super.onCreate(state);new Thread(this::run,"native-service-fixture").start();}
}
