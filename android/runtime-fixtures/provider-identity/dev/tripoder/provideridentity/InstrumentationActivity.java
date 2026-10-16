package dev.tripoder.provideridentity;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.widget.TextView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

public class InstrumentationActivity extends Activity {
    private PowerManager power;private PowerManager.OnThermalStatusChangedListener listener;private boolean thermal,child,finished;private int events;
    public InstrumentationActivity(){InstrumentationApplication.originals++;}
    @Override protected void attachBaseContext(Context base){InstrumentationApplication.check("replacement-attach-base-context-is-logical",base.getPackageName().equals("dev.tripoder.provideridentity")&&base.getApplicationContext() instanceof InstrumentationApplication);super.attachBaseContext(base);}
    @Override public void onCreate(Bundle state){super.onCreate(state);try{
        String mode=getIntent().getStringExtra("mode");
        InstrumentationApplication.check("actual-root-instance-matches-instrumentation-result","super".equals(mode)?getClass()==InstrumentationActivity.class:getClass()==Replacement.class);
        InstrumentationApplication.check("root-metadata-keeps-declared-component",getComponentName().getClassName().equals(InstrumentationActivity.class.getName()));
        InstrumentationApplication.check("instrumentation-intent-mutation-reaches-actual-activity","kept-from-instrumentation".equals(getIntent().getStringExtra("construction-marker")));
        InstrumentationApplication.check("only-final-instance-attached",InstrumentationApplication.originals==("super-replace".equals(mode)?2:1));
        TextView label=new TextView(this);label.setText("Tripoder · Instrumentation creation");setContentView(label);
        power=getSystemService(PowerManager.class);listener=status->{events++;thermal=true;InstrumentationApplication.check("runtime-default-thermal-callback-on-real-main",Looper.myLooper()==Looper.getMainLooper()&&status>=0&&status<=6);finishIfReady();};power.addThermalStatusListener(listener);
        startActivityForResult(new Intent(this,Child.class).putExtra("value",37),7);
        new Handler(Looper.getMainLooper()).postDelayed(()->{if(!finished){InstrumentationApplication.check("creation-event-deadline",false);}},10000);
    }catch(Throwable error){publish(error);}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);try{InstrumentationApplication.check("replacement-child-real-result",request==7&&result==RESULT_FIRST_USER+37&&data!=null&&data.getIntExtra("value",0)==38);child=true;finishIfReady();}catch(Throwable error){publish(error);}}
    private void finishIfReady(){if(thermal&&child)publish(null);}
    private void publish(Throwable error){if(finished)return;finished=true;if(power!=null&&listener!=null)power.removeThermalStatusListener(listener);try{
        JSONObject report=new JSONObject().put("completed",error==null).put("passed",error==null).put("checks",InstrumentationApplication.checks).put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid()).put("thermalEvents",events).put("mode",getIntent().getStringExtra("mode"));
        if(error!=null)report.put("error",android.util.Log.getStackTraceString(error));
        if("true".equals(getIntent().getStringExtra("baseline"))){String run=getIntent().getStringExtra("runId");if(run==null||!run.matches("[a-zA-Z0-9-]{1,80}"))throw new IllegalArgumentException("RUN_ID_INVALID");try(var out=new FileOutputStream(new File(getExternalFilesDir(null),"result-"+run+".json"))){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}}
        setResult(RESULT_OK,new Intent().putExtra("result",report.toString()));finish();
    }catch(Exception failure){throw new IllegalStateException("CREATION_REPORT_FAILED",failure);}}
    public static final class Replacement extends InstrumentationActivity {}
    public static class Child extends Activity {
        public Child(){InstrumentationApplication.children++;}
        @Override protected void attachBaseContext(Context base){InstrumentationApplication.check("child-attach-base-context-is-logical",base.getPackageName().equals("dev.tripoder.provideridentity"));super.attachBaseContext(base);}
        @Override public void onCreate(Bundle state){super.onCreate(state);InstrumentationApplication.check("super-then-replace-child-registers-final-object",getClass()==ChildReplacement.class&&InstrumentationApplication.children==2);InstrumentationApplication.check("replacement-child-retains-original-component",getComponentName().getClassName().equals(Child.class.getName()));setResult(RESULT_FIRST_USER+37,new Intent().putExtra("value",getIntent().getIntExtra("value",0)+1));finish();}
    }
    public static final class ChildReplacement extends Child {}
}
