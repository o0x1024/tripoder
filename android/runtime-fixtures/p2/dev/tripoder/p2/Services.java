package dev.tripoder.p2;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Ordinary self-owned Android components: no Tripoder classes, protocols or annotations. */
public final class Services {
    static final String DESCRIPTOR="dev.tripoder.p2.owned-service";
    public static class Owned extends Service {
        final String instance=UUID.randomUUID().toString();final JSONArray starts=new JSONArray();int binds,rebinds,unbinds;String component;
        @Override public void onCreate(){super.onCreate();log("create");}
        @Override public int onStartCommand(Intent intent,int flags,int startId){try{starts.put(new JSONObject().put("id",startId).put("nullIntent",intent==null).put("component",intent==null?JSONObject.NULL:intent.getComponent().flattenToString()).put("typedExtras",intent!=null&&intent.hasExtra("nullable")&&intent.getExtras().get("nullable")==null&&intent.getLongExtra("long",0)==9007199254740993L&&intent.getDoubleExtra("double",0)==1.25&&intent.getFloatExtra("float",0)==2.5f&&intent.getShortExtra("short",(short)0)==7&&intent.getByteExtra("byte",(byte)0)==8&&intent.getCharExtra("char",(char)0)=='中'));}catch(JSONException error){throw new IllegalStateException(error);}return this instanceof Sticky?START_STICKY:START_NOT_STICKY;}
        @Override public IBinder onBind(Intent intent){binds++;component=intent.getComponent().flattenToString();return this instanceof Null?null:api;}
        @Override public boolean onUnbind(Intent intent){unbinds++;return true;}
        @Override public void onRebind(Intent intent){rebinds++;}
        @Override public void onDestroy(){log("destroy");super.onDestroy();}
        private void log(String event){try(var out=new FileOutputStream(new File(getFilesDir(),getClass().getSimpleName()+"-events"),true)){out.write((event+":"+instance+"\n").getBytes(StandardCharsets.UTF_8));out.getFD().sync();}catch(Exception error){throw new IllegalStateException(error);}}
        final Binder api=new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            data.enforceInterface(DESCRIPTOR);
            try{
                if(code==1){FutureTask<String> snapshot=new FutureTask<>(()->new JSONObject().put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid()).put("process",Application.getProcessName()).put("instance",instance).put("component",component).put("package",getPackageName()).put("data",getFilesDir()).put("application",getApplication().getPackageName()).put("factoryServices",Factory.services).put("loader",getApplication().getClassLoader().loadClass(Owned.this.getClass().getName())==Owned.this.getClass()).put("binds",binds).put("unbinds",unbinds).put("rebinds",rebinds).put("starts",starts).toString());new Handler(Looper.getMainLooper()).post(snapshot);String result=snapshot.get(5,TimeUnit.SECONDS);reply.writeNoException();reply.writeString(result);return true;}
                if(code==2){boolean stopped=stopSelfResult(data.readInt());reply.writeNoException();reply.writeInt(stopped?1:0);return true;}
                return super.onTransact(code,data,reply,flags);
            }catch(Exception error){throw new IllegalStateException(error);}
        }};
    }
    public static final class Alpha extends Owned {}
    public static final class Beta extends Owned {}
    public static final class Remote extends Owned {}
    public static final class Null extends Owned {}
    public static final class Sticky extends Owned {}
    public static final class Absent extends Owned {}
    public static final class Link implements ServiceConnection {
        final BlockingQueue<IBinder> connected=new LinkedBlockingQueue<>();final CountDownLatch nulled=new CountDownLatch(1);volatile int disconnected;volatile ComponentName component;volatile boolean died;
        @Override public void onServiceConnected(ComponentName name,IBinder binder){component=name;connected.offer(binder);}
        @Override public void onServiceDisconnected(ComponentName name){component=name;disconnected++;}
        @Override public void onNullBinding(ComponentName name){component=name;nulled.countDown();}
        @Override public void onBindingDied(ComponentName name){died=true;}
        IBinder next()throws Exception{IBinder value=connected.poll(45,TimeUnit.SECONDS);if(value==null)throw new IllegalStateException("SERVICE_CONNECTION_TIMEOUT");return value;}
    }
    static Intent intent(Context context,Class<?> type){return new Intent().setComponent(new ComponentName(context.getPackageName(),type.getName()));}
    static JSONObject snapshot(IBinder binder)throws Exception{Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{data.writeInterfaceToken(DESCRIPTOR);if(!binder.transact(1,data,reply,0))throw new IllegalStateException("BINDER_UNSUPPORTED");reply.readException();return new JSONObject(reply.readString());}finally{data.recycle();reply.recycle();}}
    static boolean stop(IBinder binder,int id)throws Exception{Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{data.writeInterfaceToken(DESCRIPTOR);data.writeInt(id);if(!binder.transact(2,data,reply,0))throw new IllegalStateException("BINDER_UNSUPPORTED");reply.readException();return reply.readInt()!=0;}finally{data.recycle();reply.recycle();}}
    public static final class Main extends Activity {
        final JSONArray checks=new JSONArray();final List<Link> bound=new ArrayList<>();
        @Override public void onCreate(Bundle state){super.onCreate(state);android.widget.TextView text=new android.widget.TextView(this);text.setText("Owned Android Service lifecycle checks");setContentView(text);new Thread(this::run,"owned-services").start();}
        void run(){JSONObject result=new JSONObject();try{
            check("stop-absent-does-not-bootstrap-application",!main(()->stopService(intent(this,Absent.class)))&&!new File(getFilesDir(),"starts-dev.tripoder.p2_absentservice").exists(),true);
            Link startup=((App)getApplication()).startup;IBinder alpha=startup.next();JSONObject a=snapshot(alpha);check("application-onCreate-bind-without-startup-cycle",a.getInt("pid")==android.os.Process.myPid()&&a.getString("component").endsWith("Services$Alpha"),a);
            Link betaLink=bind(Beta.class);JSONObject b=snapshot(betaLink.next());check("two-real-services-one-process",a.getInt("pid")==b.getInt("pid")&&!a.getString("instance").equals(b.getString("instance"))&&b.getString("component").endsWith("Services$Beta"),b);
            check("service-guest-context-application-loader",a.getString("package").equals(getPackageName())&&a.getString("application").equals(getPackageName())&&a.getBoolean("loader")&&a.getString("data").equals(getFilesDir().toString()),a);
            main(()->{ComponentName first=startService(intent(this,Remote.class).putExtra("nullable",(String)null).putExtra("long",9007199254740993L).putExtra("double",1.25).putExtra("float",2.5f).putExtra("short",(short)7).putExtra("byte",(byte)8).putExtra("char",'中')),second=startService(intent(this,Remote.class));if(!first.equals(intent(this,Remote.class).getComponent())||!first.equals(second))throw new IllegalStateException("LOGICAL_START_COMPONENT_MISMATCH");return null;});
            Link remote=bind(Remote.class);IBinder api=remote.next();JSONObject r=snapshot(api);check("real-cross-process-service-binder",r.getInt("pid")!=android.os.Process.myPid()&&r.getString("process").equals("dev.tripoder.p2:services")&&remote.component.equals(intent(this,Remote.class).getComponent()),r);
            JSONArray starts=r.getJSONArray("starts");check("service-scalar-extras-types-survive-real-start-delivery",starts.length()==2&&starts.getJSONObject(0).getBoolean("typedExtras")&&!starts.getJSONObject(1).getBoolean("typedExtras"),starts);check("multiple-system-startIds",starts.length()==2&&starts.getJSONObject(1).getInt("id")>starts.getJSONObject(0).getInt("id"),starts);
            main(()->{unbindService(remote);return null;});bound.remove(remote);awaitCounter(api,"unbinds",1);Link rebound=bind(Remote.class);api=rebound.next();r=awaitCounter(api,"rebinds",1);check("android-onUnbind-and-onRebind",r.getInt("unbinds")==1&&r.getInt("rebinds")==1&&r.getInt("binds")==1,r);
            check("stopSelfResult-old-startId-rejected",!stop(api,starts.getJSONObject(0).getInt("id")),starts);
            check("stopSelfResult-last-startId-accepted",stop(api,starts.getJSONObject(1).getInt("id")),starts);
            check("bound-service-survives-stopSelfResult",snapshot(api).getString("instance").equals(r.getString("instance")),r);
            main(()->{unbindService(rebound);return null;});bound.remove(rebound);waitDestroyed("Remote",r.getString("instance"));check("started-and-bound-destroy-after-final-unbind",true,r.getString("instance"));
            Link nullLink=bind(Null.class);check("android-onNullBinding",nullLink.nulled.await(10,TimeUnit.SECONDS)&&nullLink.component.equals(intent(this,Null.class).getComponent())&&nullLink.connected.isEmpty(),String.valueOf(nullLink.component));main(()->{unbindService(nullLink);return null;});bound.remove(nullLink);
            main(()->{startService(intent(this,Sticky.class));return null;});Link sticky=bind(Sticky.class);JSONObject before=snapshot(sticky.next());android.os.Process.killProcess(before.getInt("pid"));IBinder restored=sticky.next();JSONObject after=snapshot(restored);
            long deadline=SystemClock.elapsedRealtime()+15000;while(after.getJSONArray("starts").length()==0&&SystemClock.elapsedRealtime()<deadline){Thread.sleep(50);after=snapshot(restored);}
            check("bound-service-system-cold-reconnect",sticky.disconnected>0&&!sticky.died&&after.getInt("pid")!=before.getInt("pid")&&!after.getString("instance").equals(before.getString("instance")),new JSONArray().put(before).put(after));
            check("sticky-restart-null-intent",after.getJSONArray("starts").length()==1&&after.getJSONArray("starts").getJSONObject(0).getBoolean("nullIntent"),after);
            main(()->{if(!stopService(intent(this,Sticky.class)))throw new IllegalStateException("STICKY_STOP_REJECTED");unbindService(sticky);return null;});bound.remove(sticky);waitDestroyed("Sticky",after.getString("instance"));
            main(()->{for(Link link:bound)unbindService(link);bound.clear();getApplication().unbindService(startup);return null;});
            result.put("passed",true);
        }catch(Throwable error){try{result.put("passed",false).put("error",android.util.Log.getStackTraceString(error));}catch(JSONException ignored){}android.util.Log.e("OwnedServices","failed",error);}
        try{result.put("checks",checks).put("pid",android.os.Process.myPid());String payload=result.toString();runOnUiThread(()->{setResult(RESULT_OK,new Intent().putExtra("result",payload));finish();});}catch(Exception error){throw new IllegalStateException(error);}}
        Link bind(Class<?> type)throws Exception{Link link=new Link();main(()->{boolean accepted=type==Beta.class?bindService(intent(this,type),link,Context.BindServiceFlags.of(BIND_AUTO_CREATE)):type==Null.class?bindService(intent(this,type),Context.BindServiceFlags.of(BIND_AUTO_CREATE),getMainExecutor(),link):bindService(intent(this,type),link,BIND_AUTO_CREATE);if(!accepted)throw new IllegalStateException("BIND_REJECTED");return null;});bound.add(link);return link;}
        <T>T main(Callable<T> call)throws Exception{FutureTask<T> task=new FutureTask<>(call);runOnUiThread(task);return task.get(15,TimeUnit.SECONDS);}
        JSONObject awaitCounter(IBinder binder,String name,int count)throws Exception{long deadline=SystemClock.elapsedRealtime()+10000;JSONObject value;do{value=snapshot(binder);if(value.getInt(name)>=count)return value;Thread.sleep(25);}while(SystemClock.elapsedRealtime()<deadline);throw new IllegalStateException("SERVICE_CALLBACK_NOT_OBSERVED:"+name+":"+value);}
        void waitDestroyed(String name,String instance)throws Exception{File file=new File(getFilesDir(),name+"-events");long deadline=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<deadline){if(file.exists()&&Files.readString(file.toPath()).contains("destroy:"+instance))return;Thread.sleep(50);}throw new IllegalStateException("DESTROY_NOT_OBSERVED:"+name);}
        void check(String name,boolean passed,Object observed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed).put("observed",observed));if(!passed)throw new IllegalStateException("SERVICE_CHECK_FAILED:"+name);}
    }
}
