package dev.tripoder.pendingfixture;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Calls public Android APIs; receives only real platform callbacks. */
public final class PendingActivity extends Activity {
    private final JSONArray checks=new JSONArray();private final JSONObject report=new JSONObject();
    private final List<PendingIntent> cleanup=new ArrayList<>();
    @Override public void onCreate(Bundle saved){super.onCreate(saved);new Thread(this::run,"owned-pending-api").start();}
    private void run(){boolean passed=false;try{
        int sdk=getApplicationInfo().targetSdkVersion;report.put("targetSdk",sdk);
        JSONObject ui=UiWindowChecks.run(this);report.put("dialogClick",ui);
        check("attached-dialog-click-reaches-listener-with-owned-application-token",ui.getBoolean("listenerReached")&&ui.getBoolean("bothAttached")&&ui.getBoolean("distinctDecor")&&ui.getBoolean("distinctWindowTokens")&&ui.getBoolean("sameNonNullApplicationToken"));
        JSONObject web=UiWebViewChecks.run(this);report.put("webView",web);
        check("platform-webview-renders-inline-page-and-executes-javascript-storage","Tripoder owned WebView".equals(web.getString("title"))&&"rendered".equals(web.getString("text"))&&"ok".equals(web.getString("storage")));
        ActivityManager activityManager=getSystemService(ActivityManager.class);int self=android.os.Process.myPid();
        android.os.Debug.MemoryInfo[] memory=activityManager.getProcessMemoryInfo(new int[]{self,self});
        check("process-memory-is-real-and-keeps-request-order",memory.length==2&&memory[0].getTotalPss()>0&&memory[1].getTotalPss()>0);
        boolean outside=false;try{activityManager.getProcessMemoryInfo(new int[]{1});}catch(SecurityException expected){outside=true;}check("process-memory-rejects-pid-outside-install",outside);
        try{
            Thread.currentThread().interrupt();SharedPreferences prefs=getSharedPreferences("interrupted",MODE_PRIVATE);
            check("preferences-initial-read-survives-interrupt",prefs.getAll().isEmpty());Thread.interrupted();
            prefs.edit().putInt("value",7).apply();Thread.currentThread().interrupt();
            check("preferences-reload-awaits-real-write-despite-interrupt",getSharedPreferences("interrupted",MODE_MULTI_PROCESS).getInt("value",0)==7);Thread.interrupted();
            Thread.currentThread().interrupt();boolean committed=prefs.edit().putInt("value",9).commit();
            check("preferences-interrupted-commit-does-not-claim-success",!committed&&!Thread.currentThread().isInterrupted());
            check("preferences-interrupted-wait-does-not-cancel-disk-write",getSharedPreferences("interrupted",MODE_MULTI_PROCESS).getInt("value",0)==9);
        }finally{Thread.interrupted();}
        Intent base=intent("initial","old");
        check("real-no-create-absent",PendingIntent.getBroadcast(this,41,base,PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE)==null);
        ExecutorService parallel=Executors.newFixedThreadPool(4);CountDownLatch start=new CountDownLatch(1);List<Future<PendingIntent>> concurrent=new ArrayList<>();
        try{for(int n=0;n<4;n++){int id=80+n;concurrent.add(parallel.submit(()->{start.await();return PendingIntent.getBroadcast(this,id,intent("concurrent-"+id,"owned"),PendingIntent.FLAG_IMMUTABLE);}));}start.countDown();Set<PendingIntent> identities=new HashSet<>();for(var future:concurrent){PendingIntent token=future.get(10,TimeUnit.SECONDS);cleanup.add(token);identities.add(token);}check("live-concurrent-mutations-serialize-without-false-unknown",identities.size()==4);}finally{parallel.shutdownNow();}
        try{PendingIntent legacy=PendingIntent.getBroadcast(this,40,intent("legacy","legacy"),0);cleanup.add(legacy);check("target30-default-is-mutable",sdk==30&&!legacy.isImmutable());}
        catch(IllegalArgumentException expected){check("target31-explicit-mutability-required",sdk>=31&&expected.getMessage().contains("MUTABILITY"));}
        boolean conflict=false;try{PendingIntent.getBroadcast(this,40,base,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_MUTABLE);}catch(IllegalArgumentException expected){conflict=true;}check("both-mutability-bits-rejected",conflict);
        PendingIntent categories=create(47,intent("categories","base").addCategory("a").addCategory("b"),PendingIntent.FLAG_IMMUTABLE);
        check("filter-equality-ignores-category-order",categories.equals(PendingIntent.getBroadcast(this,47,intent("ignored","ignored").addCategory("b").addCategory("a"),PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE)));
        PendingIntent first=create(41,base,PendingIntent.FLAG_IMMUTABLE);
        PendingIntent updated=create(41,intent("updated","new"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        check("update-retains-real-token",first.equals(updated)&&first.hashCode()==updated.hashCode());
        check("real-no-create-hit",first.equals(PendingIntent.getBroadcast(this,41,base,PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE)));
        check("creator-and-intent-sender-logical-package",getPackageName().equals(first.getCreatorPackage())&&getPackageName().equals(first.getIntentSender().getCreatorPackage()));
        check("creator-uid-is-real-ordinary-uid",first.getCreatorUid()==android.os.Process.myUid());
        Parcel parcel=Parcel.obtain();PendingIntent copy;try{first.writeToParcel(parcel,0);parcel.setDataPosition(0);copy=PendingIntent.CREATOR.createFromParcel(parcel);}finally{parcel.recycle();}
        check("parcel-preserves-token-and-logical-view",first.equals(copy)&&getPackageName().equals(copy.getCreatorPackage()));
        long coldStart=SystemClock.elapsedRealtime();copy.send();JSONObject cold=await("updated",30000);report.put("coldFirstDelivery",cold).put("coldSendToReceiverMs",SystemClock.elapsedRealtime()-coldStart);
        check("real-cold-receiver-logical-payload",cold.getString("value").equals("new")&&cold.getString("component").equals(new ComponentName(this,PendingReceiver.class).flattenToString())&&cold.getInt("pid")!=android.os.Process.myPid()&&!cold.getBoolean("ordered"));
        PendingIntent mutable=create(42,intent("mutable","base"),PendingIntent.FLAG_MUTABLE);
        CountDownLatch completed=new CountDownLatch(1);Throwable[] callbackError={null};
        mutable.send(this,0,new Intent().putExtra("value","filled").putExtra("added","new-key"),(p,intent,code,data,extras)->{try{if(!p.equals(mutable)||code!=77||!"owned-result".equals(data)||!"base".equals(intent.getStringExtra("value"))||!"new-key".equals(intent.getStringExtra("added"))||!getPackageName().equals(intent.getComponent().getPackageName()))throw new AssertionError("ON_FINISHED_LOGICAL_RESULT");}catch(Throwable e){callbackError[0]=e;}finally{completed.countDown();}},new Handler(Looper.getMainLooper()));
        JSONObject filled=await("mutable",15000);report.put("mutableDelivery",filled);check("mutable-extras-real-ordered-delivery",filled.getString("value").equals("base")&&filled.getString("added").equals("new-key")&&filled.getBoolean("ordered"));check("real-on-finished-result",completed.await(15,TimeUnit.SECONDS)&&callbackError[0]==null);
        IntentSender sender=create(43,intent("sender","base"),PendingIntent.FLAG_MUTABLE).getIntentSender();
        sender.sendIntent(this,0,new Intent().putExtra("added","sender"),null,null);check("intent-sender-enters-same-owned-route",await("sender",15000).getString("added").equals("sender"));
        boolean redirected=false;try{mutable.send(this,0,new Intent().setComponent(new ComponentName("elsewhere","elsewhere.Receiver")));}catch(UnsupportedOperationException expected){redirected=true;}check("mutable-fill-in-cannot-change-route",redirected);
        PendingIntent replacement=create(41,intent("replacement","replacement"),PendingIntent.FLAG_CANCEL_CURRENT|PendingIntent.FLAG_IMMUTABLE);check("cancel-current-creates-new-token",!first.equals(replacement));
        boolean canceled=false;try{first.send();}catch(PendingIntent.CanceledException expected){canceled=true;}check("old-system-token-is-canceled",canceled);first.cancel();check("old-cancel-does-not-cancel-replacement",replacement.equals(PendingIntent.getBroadcast(this,41,base,PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE)));
        PendingIntent once=create(44,intent("once","one"),PendingIntent.FLAG_ONE_SHOT|PendingIntent.FLAG_IMMUTABLE);once.send();await("once",15000);boolean consumed=false;try{once.send();}catch(PendingIntent.CanceledException expected){consumed=true;}check("real-one-shot-token-consumed",consumed);
        String action="dev.tripoder.pendingfixture.IMPLICIT";
        DynamicPendingReceiver dynamic=new DynamicPendingReceiver("first");registerReceiver(dynamic,new IntentFilter(action),Context.RECEIVER_NOT_EXPORTED);
        dynamic.onReceive(this,new Intent(action).putExtra("marker","local-java-call").putExtra("value","local"));JSONObject local=await("local-java-call",1000);check("registered-receiver-local-java-call-is-not-system-delivery",!local.getBoolean("ordered")&&local.getString("value").equals("local")&&dynamic.goAsync()==null);
        PendingIntent implicit=create(50,new Intent(action).putExtra("marker","implicit-first").putExtra("value","base"),PendingIntent.FLAG_MUTABLE);
        CountDownLatch implicitDone=new CountDownLatch(1);boolean[] implicitResult={false};
        implicit.send(this,0,new Intent().putExtra("added","dynamic"),(p,i,c,d,e)->{implicitResult[0]=c==91&&"first".equals(d)&&i.getComponent()==null&&action.equals(i.getAction());implicitDone.countDown();},new Handler(Looper.getMainLooper()));
        JSONObject dynamicFirst=await("implicit-first",15000);check("implicit-token-real-dynamic-receiver",dynamicFirst.getString("identity").equals("first")&&dynamicFirst.getBoolean("componentAbsent")&&dynamicFirst.getBoolean("ordered")&&dynamicFirst.getString("added").equals("dynamic"));check("implicit-real-ordered-result",implicitDone.await(10,TimeUnit.SECONDS)&&implicitResult[0]);
        unregisterReceiver(dynamic);DynamicPendingReceiver replacementReceiver=new DynamicPendingReceiver("replacement");registerReceiver(replacementReceiver,new IntentFilter(action),Context.RECEIVER_NOT_EXPORTED);
        PendingIntent implicitUpdated=create(50,new Intent(action).putExtra("marker","implicit-replacement").putExtra("value","new"),PendingIntent.FLAG_MUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);check("implicit-update-retains-system-token",implicit.equals(implicitUpdated));implicitUpdated.send();
        check("implicit-receiver-resolved-at-send-time",await("implicit-replacement",15000).getString("identity").equals("replacement"));unregisterReceiver(replacementReceiver);
        PendingIntent absent=create(51,new Intent(action).putExtra("marker","implicit-absent"),PendingIntent.FLAG_IMMUTABLE);CountDownLatch noReceiver=new CountDownLatch(1);boolean[] absentResult={false};absent.send(this,12,null,(p,i,c,d,e)->{absentResult[0]=c==12;noReceiver.countDown();},new Handler(Looper.getMainLooper()));
        check("implicit-no-receiver-real-completion",noReceiver.await(10,TimeUnit.SECONDS)&&absentResult[0]&&!new File(getFilesDir(),"pending-implicit-absent.json").exists());
        AlarmManager alarms=getSystemService(AlarmManager.class);report.put("exactAlarmsAllowed",alarms.canScheduleExactAlarms());
        PendingIntent alarm=create(45,intent("alarm","alarm"),PendingIntent.FLAG_MUTABLE);long trigger=SystemClock.elapsedRealtime()+200;alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,trigger,alarm);JSONObject fired=await("alarm",60000);check("real-alarm-delivery",fired.getLong("elapsed")>=trigger&&fired.getInt("alarmCount")>=1);alarms.cancel(alarm);
        PendingIntent exact=create(48,intent("exact-alarm","exact"),PendingIntent.FLAG_MUTABLE);
        if(alarms.canScheduleExactAlarms()){long when=SystemClock.elapsedRealtime()+200;alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,when,exact);JSONObject actual=await("exact-alarm",15000);check("real-exact-alarm-delivery",actual.getLong("elapsed")>=when&&actual.getInt("alarmCount")>=1);report.put("exactAlarmFired",true);alarms.cancel(exact);}
        else{boolean denied=false;try{alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+200,exact);}catch(SecurityException expected){denied=true;}check("exact-alarm-denial-is-real",denied);report.put("exactAlarmFired",false);}
        PendingIntent future=create(46,intent("canceled-alarm","must-not-run"),PendingIntent.FLAG_MUTABLE);alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+1500,future);alarms.cancel(future);Thread.sleep(2500);check("canceled-alarm-not-delivered",!new File(getFilesDir(),"pending-canceled-alarm.json").exists());
        PendingIntent coldToken=PendingIntent.getBroadcast(this,99,intent("after-death","durable"),PendingIntent.FLAG_ONE_SHOT|PendingIntent.FLAG_MUTABLE);check("durable-token-created-before-creator-death",coldToken!=null);
        passed=true;
    }catch(Throwable error){try{report.put("error",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}}
    finally{try{for(PendingIntent token:cleanup)try{token.cancel();}catch(Exception error){passed=false;report.put("cleanupError",android.util.Log.getStackTraceString(error));}report.put("passed",passed).put("completed",true).put("checks",checks).put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid());PendingReceiver.write(new File(getFilesDir(),"pending-contract.json"),report.toString());runOnUiThread(()->{setResult(RESULT_OK,new Intent().putExtra("result",report.toString()));finish();});}catch(Exception error){throw new IllegalStateException(error);}}}
    private Intent intent(String marker,String value){return new Intent("dev.tripoder.pendingfixture.DELIVER").setComponent(new ComponentName(this,PendingReceiver.class)).putExtra("marker",marker).putExtra("value",value);}
    private PendingIntent create(int id,Intent intent,int flags){PendingIntent token=PendingIntent.getBroadcast(this,id,intent,flags);cleanup.add(token);return token;}
    private JSONObject await(String marker,long timeout)throws Exception{long deadline=SystemClock.elapsedRealtime()+timeout;File path=new File(getFilesDir(),"pending-"+marker+".json");while(SystemClock.elapsedRealtime()<deadline){if(path.exists())return new JSONObject(Files.readString(path.toPath()));Thread.sleep(25);}throw new IllegalStateException("PENDING_REAL_CALLBACK_TIMEOUT:"+marker);}
    private void check(String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
}
