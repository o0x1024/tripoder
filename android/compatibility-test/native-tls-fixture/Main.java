package dev.tripoder.nativetlsfixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;

public final class Main extends Activity {
    public static String summary(){return KeepAliveChecks.summary();}
    public static String expected(){return KeepAliveChecks.expected();}
    public static String releaseCapture(){return KeepAliveChecks.release();}
    public static void clientReady()throws Exception{KeepAliveChecks.clientReady();}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);NativeClient.load();LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        TextView text=new TextView(this);Button run=new Button(this),hold=new Button(this);run.setText("Run native mTLS");hold.setText("Hold keepalive mTLS");layout.addView(run);layout.addView(hold);layout.addView(text);setContentView(layout);text.setText(summary());
        java.util.function.Consumer<Boolean> start=held->{
            run.setEnabled(false);hold.setEnabled(false);KeepAliveChecks.begin(held);text.setText(summary());
            new Thread(()->{KeepAliveChecks.execute(this);runOnUiThread(()->{text.setText(summary());run.setEnabled(true);hold.setEnabled(true);});},"owned-keepalive-client").start();
        };
        run.setOnClickListener(view->start.accept(false));hold.setOnClickListener(view->start.accept(true));
    }
}
