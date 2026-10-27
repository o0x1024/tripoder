package dev.tripoder.ingressguest;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.webkit.*;
import android.widget.*;
import java.util.*;

public final class Main extends Activity {
    static {System.loadLibrary("ingressnative");}
    private static native String nativeCheck(String path);
    static final Set<String> passed=Collections.synchronizedSet(new HashSet<>());
    static volatile Throwable failure;
    public static final class Model implements java.io.Serializable {private static final long serialVersionUID=1;final String value="ingress";}
    public static final class ManifestReceiver extends BroadcastReceiver {public void onReceive(Context c,Intent i){passed.add("manifest");}}
    public static final class FixtureJob extends android.app.job.JobService {
        public boolean onStartJob(android.app.job.JobParameters p){return false;}
        public boolean onStopJob(android.app.job.JobParameters p){return false;}
    }
    private final Handler main=new Handler(Looper.getMainLooper());private TextView result;private WebView web;
    private BroadcastReceiver dynamic;
    private static Context raw(Context context){while(context instanceof ContextWrapper)context=((ContextWrapper)context).getBaseContext();return context;}
    @Override public void onCreate(Bundle state){super.onCreate(state);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);result=new TextView(this);result.setText("INGRESS_GUEST_RUNNING");page.addView(result);
        Button finish=new Button(this);finish.setAllCaps(false);finish.setText("Finish ingress fixture");finish.setOnClickListener(v->finish());page.addView(finish);setContentView(page);
        try{
            String nativeResult=nativeCheck(new java.io.File(getFilesDir(),"native-signal-input").toString());
            android.util.Log.i("TripoderIngressNative",nativeResult);
            if(!new org.json.JSONObject(nativeResult).getBoolean("passed"))throw new IllegalStateException(nativeResult);
            boolean javaException=false;try{Object missing=null;missing.hashCode();}catch(NullPointerException expected){javaException=true;}
            if(!javaException)throw new IllegalStateException("java-signal-semantics");
            android.app.NotificationManager notifications=getSystemService(android.app.NotificationManager.class);
            notifications.createNotificationChannel(new android.app.NotificationChannel("ingress","Ingress",android.app.NotificationManager.IMPORTANCE_LOW));
            if(notifications.getNotificationChannel("ingress")==null||notifications.getNotificationChannels().stream().noneMatch(c->c.getId().equals("ingress")))throw new IllegalStateException("notification-channel");
            notifications.deleteNotificationChannel("ingress");
            android.app.job.JobScheduler scheduler=getSystemService(android.app.job.JobScheduler.class).forNamespace("ingress");
            android.app.job.JobInfo job=new android.app.job.JobInfo.Builder(73,new ComponentName(this,FixtureJob.class)).setMinimumLatency(60000).build();
            if(scheduler.schedule(job)!=android.app.job.JobScheduler.RESULT_SUCCESS||scheduler.getPendingJob(73)==null||!"ingress".equals(scheduler.getNamespace()))throw new IllegalStateException("job-scheduler");
            scheduler.cancel(73);if(scheduler.getPendingJob(73)!=null)throw new IllegalStateException("job-cancel");
            getSystemService(android.content.pm.ShortcutManager.class).getDynamicShortcuts();
            passed.add("managers");
            Context wrapped=getApplicationContext(),physical=raw(wrapped);
            if(!wrapped.getExternalFilesDir("test").getCanonicalPath().equals(physical.getExternalFilesDir("test").getCanonicalPath()))throw new IllegalStateException("raw-storage-root");
            Context derived=physical.createConfigurationContext(getResources().getConfiguration());
            if(!wrapped.getExternalCacheDir().getCanonicalPath().equals(derived.getExternalCacheDir().getCanonicalPath()))throw new IllegalStateException("derived-storage-root");
            if(!physical.getExternalFilesDirs(null)[0].isDirectory()||!physical.getObbDir().isDirectory()||!physical.getExternalMediaDirs()[0].isDirectory())throw new IllegalStateException("raw-storage-types");passed.add("storage");
            IntentFilter empty=new IntentFilter();empty.addCategory("owned.empty");empty.addDataScheme("fixture");
            BroadcastReceiver silent=new BroadcastReceiver(){public void onReceive(Context c,Intent i){failure=new IllegalStateException("actionless-delivered");}};
            physical.registerReceiver(silent,empty,Context.RECEIVER_NOT_EXPORTED);physical.unregisterReceiver(silent);passed.add("actionless");
            dynamic=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
                PendingResult pending=goAsync();if(pending==null){failure=new IllegalStateException("pending-missing");return;}
                main.postDelayed(()->{pending.setResultCode(23);pending.setResultData("async");pending.finish();passed.add("dynamic");},100);
            }};
            physical.registerReceiver(dynamic,new IntentFilter("owned.ingress.DYNAMIC"),Context.RECEIVER_NOT_EXPORTED);
            sendOrderedBroadcast(new Intent("owned.ingress.DYNAMIC").setPackage(getPackageName()),null,new BroadcastReceiver(){public void onReceive(Context c,Intent i){
                if(getResultCode()!=23||!"async".equals(getResultData()))failure=new IllegalStateException("ordered-result");else passed.add("ordered");
                physical.unregisterReceiver(dynamic);dynamic=null;
            }},null,0,null,null);
            sendBroadcast(new Intent("owned.ingress.MANIFEST").setPackage(getPackageName()));
            Bundle child=new Bundle();child.putSerializable("model",new Model());Bundle nested=new Bundle();nested.putBundle("child",child);
            startActivityForResult(new Intent(this,Child.class).putExtra("nested",nested),91);
            // Exercise the unwrapped, derived ContextImpl path used by WebView
            // internals, including its physical bindServiceCommon boundary.
            web=new WebView(derived);page.addView(web,new LinearLayout.LayoutParams(-1,180));
            web.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView view,String url){passed.add("webview");}});
            web.loadDataWithBaseURL("https://fixture.invalid/","<html><body>Local ingress fixture</body></html>","text/html","UTF-8",null);
        }catch(Throwable error){failure=error;}
        main.postDelayed(new Runnable(){public void run(){
            if(failure!=null){result.setText("INGRESS_GUEST_FAIL "+android.util.Log.getStackTraceString(failure));return;}
            if(passed.containsAll(Set.of("managers","storage","actionless","dynamic","ordered","manifest","payload","savedState","webview","result")))result.setText("INGRESS_GUEST_PASS");
            else{result.setText("INGRESS_GUEST_RUNNING "+passed);main.postDelayed(this,200);}
        }},300);
    }
    @Override protected void onActivityResult(int request,int code,Intent intent){super.onActivityResult(request,code,intent);
        try{if(request!=91||code!=RESULT_OK||intent.getBundleExtra("result").getSerializable("model",Model.class)==null)throw new IllegalStateException("activity-result");passed.add("result");}catch(Throwable error){failure=error;}
    }
    @Override protected void onDestroy(){if(dynamic!=null){raw(getApplicationContext()).unregisterReceiver(dynamic);dynamic=null;}if(web!=null)web.destroy();super.onDestroy();}
}
