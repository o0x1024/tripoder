package dev.tripoder.p2;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import org.json.*;

/** Both names are real signed manifest declarations; no runtime-specific API. */
public final class Aliases {
    public static final class Collision extends Activity {
        @Override public void onCreate(Bundle state){super.onCreate(state);throw new IllegalStateException("SHADOWED_ACTIVITY_MUST_NOT_RUN");}
    }
    public static final class Target extends Activity {
        @Override public void onCreate(Bundle state){super.onCreate(state);try{
            JSONObject value=new JSONObject().put("class",getClass().getName()).put("component",getComponentName().getClassName()).put("intent",getIntent().getComponent().getClassName()).put("process",android.app.Application.getProcessName()).put("pid",android.os.Process.myPid()).put("task",getTaskId());
            setResult(RESULT_OK,new Intent().putExtra("result",value.toString()));finish();
        }catch(Exception error){throw new IllegalStateException(error);}}
    }
    public static final class Parent extends Activity {
        private final JSONArray runs=new JSONArray();
        private boolean disabled;
        @Override public void onCreate(Bundle state){super.onCreate(state);
            try{startActivityForResult(new Intent().setComponent(new ComponentName(getPackageName(),"dev.tripoder.p2.DisabledAlias")),9);}catch(RuntimeException error){disabled=String.valueOf(error).contains("COMPONENT_DISABLED");}
            launch("dev.tripoder.p2.EntryAlias");
        }
        private void launch(String name){startActivityForResult(new Intent().setComponent(new ComponentName(getPackageName(),name)),7);}
        @Override protected void onActivityResult(int request,int code,Intent data){super.onActivityResult(request,code,data);try{
            if(request!=7||code!=RESULT_OK||data==null)throw new IllegalStateException("ALIAS_RESULT_INVALID");runs.put(new JSONObject(data.getStringExtra("result")));
            if(runs.length()==1)launch(Collision.class.getName());
            else if(runs.length()==2)launch("dev.tripoder.p2.EntryAlias");
            else{setResult(RESULT_OK,new Intent().putExtra("result",new JSONObject().put("disabledRejected",disabled).put("pid",android.os.Process.myPid()).put("runs",runs).toString()));finish();}
        }catch(Exception error){throw new IllegalStateException(error);}}
    }
}
