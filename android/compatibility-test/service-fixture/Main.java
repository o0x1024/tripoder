package dev.tripoder.servicecontract;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.TextView;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Only owned Service instances and payloads; no target-app state or account data. */
public final class Main extends Activity {
    private final JSONArray checks=new JSONArray();
    private final AtomicInteger missingCallbacks=new AtomicInteger();
    private TextView text;
    private void check(String name,boolean passed)throws Exception{
        checks.put(new JSONObject().put("name",name).put("passed",passed));
        android.util.Log.i("TripoderServiceFixture","check="+name+";passed="+passed);
        if(!passed)throw new AssertionError(name);
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);text=new TextView(this);setContentView(text);text.setText("RUNNING OWNED SERVICE CONTRACT");
        new Thread(this::run,"owned-service-checks").start();
    }
    private void run(){
        Throwable failure=null;
        try{missing();inspect(Inherited.class,Inherited.state);inspect(Overridden.class,Overridden.state);check("absent-service-has-no-connection-callback",missingCallbacks.get()==0);}
        catch(Throwable error){failure=error;android.util.Log.e("TripoderServiceFixture","OWNED_SERVICE_CONTRACT_FAILED",error);}
        try{
            JSONObject result=new JSONObject().put("passed",failure==null).put("checks",checks.length()).put("stopObservations",StopObserver.snapshot());
            if(failure!=null)result.put("error",failure.getClass().getName()+":"+failure.getMessage());
            android.util.Log.i("TripoderServiceFixture",result.toString());
            String display=new JSONObject().put("summary",result).put("checks",checks).toString();runOnUiThread(()->text.setText(display));
        }catch(JSONException error){throw new IllegalStateException(error);}
    }
    private void missing()throws Exception{
        ComponentName absent=new ComponentName(this,getPackageName()+".OwnedUndeclaredServiceProbe");
        ServiceConnection connection=new ServiceConnection(){
            public void onServiceConnected(ComponentName name,IBinder binder){missingCallbacks.incrementAndGet();}
            public void onServiceDisconnected(ComponentName name){missingCallbacks.incrementAndGet();}
            public void onNullBinding(ComponentName name){missingCallbacks.incrementAndGet();}
            public void onBindingDied(ComponentName name){missingCallbacks.incrementAndGet();}
        };
        boolean bound=bindService(new Intent().setComponent(absent),connection,BIND_AUTO_CREATE);
        try{check("absent-service-bind-matches-native-false",!bound);}finally{if(bound)unbindService(connection);}
        unbindService(connection);check("absent-bind-keeps-one-local-unbind-registration",true);
        boolean rejected=false;try{unbindService(connection);}catch(IllegalArgumentException expected){rejected=true;}
        check("absent-bind-second-unbind-is-rejected",rejected);
        check("absent-service-start-matches-native-null",startService(new Intent().setComponent(absent))==null);
    }
    private void inspect(Class<? extends Inherited> type,State state)throws Exception{
        String label=type.getSimpleName();ComponentName component=new ComponentName(this,type);
        CountDownLatch bound=new CountDownLatch(1);AtomicBoolean logicalBind=new AtomicBoolean();
        ServiceConnection connection=new ServiceConnection(){
            public void onServiceConnected(ComponentName name,IBinder binder){throw new AssertionError("INTENT_SERVICE_BINDER_MUST_BE_NULL");}
            public void onServiceDisconnected(ComponentName name){}
            public void onNullBinding(ComponentName name){logicalBind.set(component.equals(name));bound.countDown();}
        };
        check(label+"-bind-accepted",bindService(new Intent().setComponent(component),connection,BIND_AUTO_CREATE));
        try{
            check(label+"-inherited-onBind-null-and-logical-component",bound.await(6,TimeUnit.SECONDS)&&logicalBind.get());
            StopObserver.install(state.instance);
            for(int i=1;i<=2;i++){
                Intent request=new Intent("owned.service.WORK").setComponent(component).putExtra("sequence",i).putExtra("payload",new Payload(i));
                check(label+"-start-accepted-"+i,component.equals(startService(request)));
            }
            check(label+"-framework-dispatch-delivers-two-work-items",state.handled.await(8,TimeUnit.SECONDS));
            check(label+"-logical-intent-and-owned-parcelable-preserved",state.valid.get());
            check(label+"-serial-background-worker-and-exactly-once",state.sequences.equals(List.of(1,2))&&state.background.get()&&state.instances.get()==1);
            if(type==Overridden.class)check(label+"-guest-overrides-not-double-delivered",Overridden.creates.get()==1&&Overridden.starts.get()==2);
        }finally{unbindService(connection);}
        if(type==Overridden.class)check(label+"-guest-destroy-exactly-once",Overridden.destroyed.await(6,TimeUnit.SECONDS)&&Overridden.destroys.get()==1);
    }
    static final class State {
        final CountDownLatch handled=new CountDownLatch(2);
        final AtomicInteger instances=new AtomicInteger();
        final AtomicBoolean valid=new AtomicBoolean(true),background=new AtomicBoolean(true);
        final List<Integer> sequences=Collections.synchronizedList(new ArrayList<>());
        volatile Service instance;
    }
    public static class Inherited extends IntentService {
        static final State state=new State();
        final State current;
        public Inherited(){this(state,"OwnedInheritedService");}
        Inherited(State state,String name){super(name);current=state;state.instance=this;state.instances.incrementAndGet();}
        @Override protected void onHandleIntent(Intent intent){
            try{
                int sequence=intent.getIntExtra("sequence",-1);Payload payload=intent.getParcelableExtra("payload",Payload.class);
                boolean valid=new ComponentName(this,getClass()).equals(intent.getComponent())&&"owned.service.WORK".equals(intent.getAction())
                    &&payload!=null&&payload.sequence==sequence&&!intent.hasExtra("tripoder.serviceLease")
                    &&!intent.hasExtra("tripoder.serviceComponent")&&!intent.hasExtra("tripoder.thinService");
                if(!valid)current.valid.set(false);
                if(Looper.myLooper()==Looper.getMainLooper())current.background.set(false);
                current.sequences.add(sequence);
            }catch(Throwable error){current.valid.set(false);android.util.Log.e("TripoderServiceFixture","OWNED_WORK_FAILED",error);}
            finally{current.handled.countDown();}
        }
    }
    public static final class Overridden extends Inherited {
        static final State state=new State();
        static final AtomicInteger creates=new AtomicInteger(),starts=new AtomicInteger(),destroys=new AtomicInteger();
        static final CountDownLatch destroyed=new CountDownLatch(1);
        public Overridden(){super(state,"OwnedOverrideService");}
        @Override public void onCreate(){creates.incrementAndGet();super.onCreate();}
        @Override public int onStartCommand(Intent intent,int flags,int startId){starts.incrementAndGet();return super.onStartCommand(intent,flags,startId);}
        @Override public void onDestroy(){super.onDestroy();destroys.incrementAndGet();destroyed.countDown();}
    }
    public static final class Payload implements Parcelable {
        final int sequence;
        Payload(int sequence){this.sequence=sequence;}
        public int describeContents(){return 0;}
        public void writeToParcel(Parcel out,int flags){out.writeInt(sequence);}
        public static final Parcelable.Creator<Payload> CREATOR=new Parcelable.Creator<>(){
            public Payload createFromParcel(Parcel in){return new Payload(in.readInt());}
            public Payload[] newArray(int size){return new Payload[size];}
        };
    }
}
