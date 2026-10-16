package dev.tripoder.runtimeprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.util.concurrent.ConcurrentHashMap;

/** Host-owned task observation fixture. It never runs guest code or requests privileges. */
public final class TaskVisibilityActivity extends Activity {
    static final ConcurrentHashMap<Integer,TaskVisibilityActivity> instances=new ConcurrentHashMap<>();
    final int index(){return getIntent().getIntExtra("index",-1);}
    @Override public void onCreate(Bundle state){super.onCreate(state);TextView text=new TextView(this);text.setText("Tripoder task observation "+index());setContentView(text);instances.put(index(),this);}
    @Override protected void onDestroy(){instances.remove(index(),this);super.onDestroy();}
}
