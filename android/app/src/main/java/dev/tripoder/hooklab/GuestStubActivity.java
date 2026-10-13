package dev.tripoder.hooklab;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Manifest slot only; the factory normally returns the independent APK's real Activity. */
public final class GuestStubActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); TextView view = new TextView(this);
        view.setPadding(32, 100, 32, 32); view.setText("客体进程已被回收，请返回首页重新打开客体界面。"); setContentView(view);
    }
}
