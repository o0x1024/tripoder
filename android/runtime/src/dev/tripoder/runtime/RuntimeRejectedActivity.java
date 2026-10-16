package dev.tripoder.runtime;

import android.app.Activity;
import android.os.Bundle;

/** Terminal cancellation of a revoked Android entry. No guest class is loaded. */
public final class RuntimeRejectedActivity extends Activity {
    private final String reason;
    RuntimeRejectedActivity(String reason){this.reason=reason;}
    @Override public void onCreate(Bundle state){super.onCreate(state);android.util.Log.e("TripoderRuntime","ACTIVITY_RESTORE_REJECTED:"+reason);setResult(RESULT_CANCELED);finish();}
}
