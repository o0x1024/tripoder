package dev.tripoder.p2;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.widget.TextView;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Public SDK fixture: every onReceive/PendingResult comes from Android. */
public final class Receivers {
    public static final String ACTION="dev.tripoder.p2.RECEIVER_TEST";
    public static final class Cold extends BroadcastReceiver {
        @Override public void onReceive(Context context,Intent intent){
            if(!isOrderedBroadcast())throw new IllegalStateException("ORDERED_REQUIRED");
            Bundle extras=new Bundle();extras.putInt("pid",android.os.Process.myPid());extras.putInt("uid",android.os.Process.myUid());extras.putString("package",context.getPackageName());extras.putString("process",android.app.Application.getProcessName());extras.putString("data",context.getDataDir().toString());extras.putInt("starts",((App)context.getApplicationContext()).starts);extras.putInt("factory",Factory.receivers);extras.putString("challenge",intent.getStringExtra("challenge"));extras.putString("component",intent.getComponent().flattenToString());extras.putLong("entered",SystemClock.elapsedRealtime());
            setResult(71,"guest-ordered-result",extras);PendingResult pending=goAsync();new Handler(Looper.getMainLooper()).postDelayed(pending::finish,700);
        }
    }
    public static final class Main extends Activity {
        final JSONArray checks=new JSONArray();final JSONObject report=new JSONObject();final CountDownLatch completed=new CountDownLatch(1);volatile Bundle cold;volatile long finalAt;volatile String finalIntent;volatile int finalCode;volatile String finalData;
        @Override public void onCreate(Bundle state){super.onCreate(state);TextView view=new TextView(this);view.setText("Tripoder owned Receiver acceptance");setContentView(view);new Thread(this::run,"receiver-acceptance").start();}
        void check(String name,boolean value,Object seen)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",value).put("observed",seen));if(!value)throw new IllegalStateException("ASSERTION_FAILED:"+name);}
        void run(){boolean passed=false;HandlerThread scheduler=new HandlerThread("guest-receiver-scheduler");scheduler.start();try{
            String challenge=UUID.randomUUID().toString();Intent intent=new Intent(this,Cold.class).setAction(ACTION).putExtra("challenge",challenge);
            sendOrderedBroadcast(intent,null,new BroadcastReceiver(){@Override public void onReceive(Context context,Intent received){cold=getResultExtras(false);finalAt=SystemClock.elapsedRealtime();finalIntent=received.getStringExtra("challenge");finalCode=getResultCode();finalData=getResultData();completed.countDown();}},new Handler(scheduler.getLooper()),11,"initial",null);
            Thread.sleep(150);check("receiver-no-final-result-before-async-finish",completed.getCount()==1,completed.getCount());
            check("receiver-android-ordered-final-result",completed.await(30,TimeUnit.SECONDS),finalAt);
            check("receiver-cold-real-process",cold!=null&&cold.getInt("pid")!=android.os.Process.myPid()&&cold.getInt("uid")==android.os.Process.myUid(),String.valueOf(cold));
            check("receiver-factory-and-application-order",cold.getInt("factory")==1&&cold.getInt("starts")==1&&"dev.tripoder.p2:receivers".equals(cold.getString("process")),cold.toString());
            check("receiver-context-and-logical-intent","dev.tripoder.p2".equals(cold.getString("package"))&&challenge.equals(cold.getString("challenge"))&&challenge.equals(finalIntent)&&intent.getComponent().flattenToString().equals(cold.getString("component")),cold.toString());
            check("receiver-goAsync-system-result-waits-for-finish",finalAt-cold.getLong("entered")>=650&&finalCode==71&&"guest-ordered-result".equals(finalData),finalAt-cold.getLong("entered"));
            List<Integer> finished=Collections.synchronizedList(new ArrayList<>());List<String> threads=Collections.synchronizedList(new ArrayList<>());CountDownLatch dynamicDone=new CountDownLatch(2);
            BroadcastReceiver dynamic=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent received){
                int sequence=received.getIntExtra("sequence",-1);if(!context.getPackageName().equals(getPackageName())||!ACTION.equals(received.getAction()))throw new IllegalStateException("DYNAMIC_CONTEXT_OR_INTENT_MISMATCH");threads.add(Thread.currentThread().getName());PendingResult p=goAsync();new Handler(scheduler.getLooper()).postDelayed(()->{finished.add(sequence);p.finish();dynamicDone.countDown();},sequence==1?400:40);
            }};
            registerReceiver(dynamic,new IntentFilter(ACTION),null,new Handler(scheduler.getLooper()),Context.RECEIVER_NOT_EXPORTED);
            sendBroadcast(new Intent(ACTION).setPackage(getPackageName()).putExtra("sequence",1));sendBroadcast(new Intent(ACTION).setPackage(getPackageName()).putExtra("sequence",2));
            check("dynamic-receiver-two-deliveries",dynamicDone.await(15,TimeUnit.SECONDS),finished.toString());
            check("dynamic-receiver-scheduler-preserved",threads.size()==2&&threads.stream().allMatch("guest-receiver-scheduler"::equals),threads.toString());
            check("dynamic-receiver-pending-results-independent",finished.equals(List.of(2,1)),finished.toString());
            unregisterReceiver(dynamic);sendBroadcast(new Intent(ACTION).setPackage(getPackageName()).putExtra("sequence",3));Thread.sleep(200);check("dynamic-receiver-unregister-stops-delivery",finished.size()==2,finished.toString());
            boolean permission=false;try{sendBroadcast(new Intent(ACTION),"dev.tripoder.unsupported");}catch(UnsupportedOperationException expected){permission=true;}check("broadcast-unsupported-permission-rejected",permission,permission);
            report.put("coldPid",cold.getInt("pid")).put("data",cold.getString("data")).put("pid",android.os.Process.myPid());passed=true;
        }catch(Throwable error){try{report.put("error",android.util.Log.getStackTraceString(error));}catch(JSONException ignored){}}
        finally{scheduler.quitSafely();try{report.put("passed",passed).put("checks",checks);runOnUiThread(()->{setResult(RESULT_OK,new Intent().putExtra("result",report.toString()));finish();});}catch(JSONException error){throw new IllegalStateException(error);}}}
    }
}
