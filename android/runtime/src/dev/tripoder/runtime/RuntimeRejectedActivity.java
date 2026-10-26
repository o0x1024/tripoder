package dev.tripoder.runtime;

import android.app.Activity;
import android.os.Bundle;

/** Terminal cancellation of a revoked Android entry. No guest class is loaded. */
public final class RuntimeRejectedActivity extends Activity {
    private final String reason;
    RuntimeRejectedActivity(String reason){this.reason=reason;}
    @Override public void onCreate(Bundle state){
        // This is a terminal host shell, not the revoked guest Activity. Its
        // saved Fragment state must never instantiate guest code in the host.
        // The persisted guest state and its execution outcome remain intact.
        super.onCreate(null);
        android.util.Log.e("TripoderRuntime","ACTIVITY_RESTORE_REJECTED:"+reason+";savedStatePresent="+(state!=null));
        setResult(RESULT_CANCELED);finish();
    }
}
