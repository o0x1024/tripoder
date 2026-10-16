package dev.tripoder.p2;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.widget.TextView;
import org.json.JSONObject;

/** Remains in Android's task until a real process restart restores its saved state. */
public final class Hold extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);try{
        int starts=((App)getApplication()).starts;
        JSONObject report=new JSONObject().put("pid",android.os.Process.myPid()).put("process",Application.getProcessName()).put("starts",starts).put("saved",state!=null&&"owned-state".equals(state.getString("ownedSaved"))).put("data",getFilesDir()).put("taskId",getTaskId()).put("component",getComponentName().flattenToString());
        TextView text=new TextView(this);text.setText("Tripoder cold Activity\n"+report);setContentView(text);
        if(starts>=2)new Handler(Looper.getMainLooper()).postDelayed(()->{setResult(RESULT_OK,new Intent().putExtra("result",report.toString()));finish();},500);
    }catch(Exception error){throw new IllegalStateException("FIXTURE_COLD_FAILED",error);}}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("ownedSaved","owned-state");super.onSaveInstanceState(state);}
}
