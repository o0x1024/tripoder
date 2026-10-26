package dev.tripoder.httpcontract;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class Main extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);TextView text=new TextView(this);setContentView(text);text.setText("RUNNING OWNED SDK HTTP CONTRACT");
        new Thread(()->{String result=HttpChecks.inspect(this).toString();android.util.Log.i("TripoderHttpContract",result);runOnUiThread(()->text.setText(result));},"owned-sdk-http").start();
    }
}
