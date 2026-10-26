package dev.tripoder.closurefixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.util.Set;
import java.util.concurrent.*;

public final class PerTask extends Activity {
    static final Set<Integer> tasks=ConcurrentHashMap.newKeySet();
    static final java.util.List<PerTask> instances=new CopyOnWriteArrayList<>();
    static CountDownLatch first,second;
    static android.app.PendingIntent expected;static volatile boolean tokenRoundtrip;
    static void reset(){tasks.clear();instances.clear();first=new CountDownLatch(1);second=new CountDownLatch(1);}
    @Override public void onCreate(Bundle state){super.onCreate(state);tokenRoundtrip=expected!=null&&expected.equals(getIntent().getParcelableExtra("owned-token",android.app.PendingIntent.class));instances.add(this);tasks.add(getTaskId());TextView view=new TextView(this);view.setText("OWNED MODE4 TASK "+getTaskId());setContentView(view);if(instances.size()==1)first.countDown();else second.countDown();}
}
