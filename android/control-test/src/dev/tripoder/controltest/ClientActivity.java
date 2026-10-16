package dev.tripoder.controltest;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** External test stimulus brings only the negative-test package into a normal foreground lifetime. */
public final class ClientActivity extends Activity {
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);android.widget.TextView text=new android.widget.TextView(this);text.setText("Tripoder 控制权限测试");setContentView(text);
    }
    @Override protected void onPostResume(){super.onPostResume();startService(new Intent(this,RejectedClient.class));finish();}
}
