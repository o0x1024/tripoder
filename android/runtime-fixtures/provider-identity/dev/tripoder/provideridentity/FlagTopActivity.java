package dev.tripoder.provideridentity;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.widget.TextView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Same standard component exercises real Android instance selection; no synthetic callbacks. */
public final class FlagTopActivity extends Activity {
    private static FlagTopActivity root;private static Cover cover;private static JSONArray checks;
    private static int creations,deliveries,destroyed;private static boolean complete;
    private int instance,stage;private boolean resumed;private final Handler main=new Handler(Looper.getMainLooper());
    @Override public void onCreate(Bundle state){super.onCreate(state);try{
        String step=getIntent().getStringExtra("step");
        if(step==null){root=this;checks=new JSONArray();creations=0;deliveries=0;destroyed=0;complete=false;stage=0;}
        instance=++creations;TextView text=new TextView(this);text.setText("Tripoder · Activity flag contract");setContentView(text);
        if(step==null){check("standard-root-in-own-task",isTaskRoot());new Thread(()->{
            try{BroadcastPermissionChecks.inspect(this,"true".equals(getIntent().getStringExtra("baseline")),checks);StickyBroadcastChecks.inspect(this,"true".equals(getIntent().getStringExtra("baseline")),checks);main.post(()->guard(()->startActivity(new Intent(this,FlagTopActivity.class).putExtra("step","reuse").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))));}
            catch(Throwable error){main.post(()->publish(error));}
        },"broadcast-permission-check").start();main.postDelayed(()->{if(!complete)publish(new IllegalStateException("ACTIVITY_FLAGS_DEADLINE"));},20000);}
        else if(step.equals("plain")){check("standard-without-flag-creates-new-instance",this!=root&&instance==2&&deliveries==2&&getTaskId()==root.getTaskId());ActivityValueChecks.verify(getIntent(),checks);startActivity(getIntent().getBundleExtra("values").getParcelable("nestedIntent",Intent.class));}
        else if(step.equals("covered")){check("single-top-below-another-activity-creates-new-instance",this!=root&&instance==3&&deliveries==2&&cover!=null&&getTaskId()==root.getTaskId());finish();}
        else throw new AssertionError("UNEXPECTED_NEW_INSTANCE:"+step);
    }catch(Throwable error){publish(error);}}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);guard(()->{
        String step=intent.getStringExtra("step");
        if("nested-forward".equals(step)){check("received-nested-intent-can-launch-through-real-platform",this!=root&&instance==2&&"owned.nested.action".equals(intent.getAction())&&java.util.Arrays.equals(intent.getIntArrayExtra("inner"),new int[]{3,5})&&"plain".equals(getIntent().getStringExtra("step")));finish();return;}
        deliveries++;
        check("single-top-delivers-to-existing-standard-instance-"+deliveries,this==root&&instance==1&&creations==1&&getTaskId()==root.getTaskId());
        check("new-intent-does-not-replace-original-intent-"+deliveries,getIntent().getStringExtra("step")==null);
        if(step.equals("reuse")&&deliveries==1){stage=1;main.post(()->guard(()->startActivity(new Intent(this,FlagTopActivity.class).putExtra("step","reuse-task").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP))));}
        else if(step.equals("reuse-task")&&deliveries==2){stage=2;main.post(()->guard(()->startActivity(ActivityValueChecks.payload(new Intent(this,FlagTopActivity.class).putExtra("step","plain")))));}
        else throw new AssertionError("UNEXPECTED_NEW_INTENT:"+step);
    });}
    @Override protected void onResume(){super.onResume();resumed=true;advance();}
    @Override protected void onPause(){resumed=false;super.onPause();}
    private void advance(){if(this==root)main.post(()->guard(()->{
        if(complete||!resumed)return;
        if(stage==2&&creations==2&&destroyed==1){stage=3;startActivity(new Intent(this,Cover.class));}
        else if(stage==3&&creations==3&&destroyed==3){check("real-child-destruction-and-root-return",deliveries==2&&isTaskRoot());publish(null);}
    }));}
    @Override protected void onDestroy(){super.onDestroy();if(this!=root){destroyed++;root.advance();if(cover!=null)cover.advance();}}
    private static void check(String name,boolean value)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",value));if(!value)throw new AssertionError(name);}
    private void guard(Throwing action){try{action.run();}catch(Throwable error){publish(error);}}
    private interface Throwing{void run()throws Exception;}
    private void publish(Throwable error){if(complete)return;complete=true;try{
        JSONObject result=new JSONObject().put("completed",error==null).put("passed",error==null).put("checks",checks).put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid()).put("creations",creations).put("newIntents",deliveries).put("taskId",root.getTaskId());if(error!=null)result.put("error",android.util.Log.getStackTraceString(error));
        File output;if("true".equals(root.getIntent().getStringExtra("baseline"))){String run=root.getIntent().getStringExtra("runId");if(run==null||!run.matches("[a-zA-Z0-9-]{1,80}"))throw new IllegalArgumentException("RUN_ID_REQUIRED");output=new File(getExternalFilesDir(null),"result-"+run+".json");}else output=new File(getFilesDir(),"activity-flags-contract.json");
        File pending=new File(output.getPath()+".pending");try(var out=new FileOutputStream(pending)){out.write(result.toString(2).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}if(!pending.renameTo(output))throw new IOException("REPORT_PUBLISH_FAILED");root.finishAndRemoveTask();
    }catch(Exception failure){throw new IllegalStateException("ACTIVITY_FLAGS_REPORT_FAILED",failure);}}
    public static final class Cover extends Activity {
        private boolean started,resumed;
        @Override public void onCreate(Bundle state){super.onCreate(state);cover=this;new Handler(Looper.getMainLooper()).post(()->root.guard(()->{started=true;startActivity(new Intent(this,FlagTopActivity.class).putExtra("step","covered").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP));}));}
        @Override protected void onResume(){super.onResume();resumed=true;advance();}
        @Override protected void onPause(){resumed=false;super.onPause();}
        private void advance(){new Handler(Looper.getMainLooper()).post(()->{if(resumed&&started&&creations==3&&destroyed==2)finish();});}
        @Override protected void onDestroy(){super.onDestroy();destroyed++;cover=null;root.advance();}
    }
}
