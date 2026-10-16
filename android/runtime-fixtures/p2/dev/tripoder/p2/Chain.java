package dev.tripoder.p2;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.TextView;
import org.json.*;

public abstract class Chain extends Activity {
    protected abstract String next();
    private JSONObject observation;
    @Override public void onCreate(Bundle state){super.onCreate(state);try{
        observation=new JSONObject().put("component",getComponentName().flattenToString())
            .put("package",getPackageName()).put("process",Application.getProcessName())
            .put("callingPackage",getCallingPackage()).put("callingActivity",getCallingActivity()==null?JSONObject.NULL:getCallingActivity().flattenToString())
            .put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid())
            .put("data",getFilesDir()).put("applicationData",getApplication().getFilesDir())
            .put("starts",((App)getApplication()).starts).put("factoryApplication",Factory.applications).put("factoryLoader",Factory.loaders).put("factoryActivities",Factory.activities).put("implementation",getClass().getName())
            .put("loader",getClassLoader()==getApplication().getClassLoader())
            .put("challenge",getIntent().getStringExtra("challenge"));
        TextView view=new TextView(this);view.setText(observation.toString());setContentView(view);
        if(next()==null){finishResult();return;}
        if(state==null)startActivityForResult(new Intent().setComponent(new ComponentName(getPackageName(),next())).putExtra("challenge",getIntent().getStringExtra("challenge")),7);
    }catch(Exception error){throw new IllegalStateException("FIXTURE_CHAIN_FAILED",error);}}
    @Override protected void onActivityResult(int request,int code,Intent data){super.onActivityResult(request,code,data);try{
        if(request!=7||code!=RESULT_OK||data==null)throw new IllegalStateException("FIXTURE_RESULT_INVALID");
        observation.put("resultCaller",getCurrentCaller().getPackage()).put("child",new JSONObject(data.getStringExtra("result")));finishResult();
    }catch(Exception error){throw new IllegalStateException("FIXTURE_RESULT_FAILED",error);}}
    private void finishResult(){setResult(RESULT_OK,new Intent().putExtra("result",observation.toString()));finish();}
    public static final class Main extends Chain {protected String next(){return Remote.class.getName();}}
    public static final class Remote extends Chain {protected String next(){return Middle.class.getName();}}
    public static final class Middle extends Chain {protected String next(){return Leaf.class.getName();}}
    public static final class Leaf extends Chain {protected String next(){return null;}}
}
