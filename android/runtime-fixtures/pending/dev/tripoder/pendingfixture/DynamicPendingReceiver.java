package dev.tripoder.pendingfixture;

import android.content.*;
import java.io.File;
import org.json.JSONObject;

/** A real public SDK dynamic receiver; instances are distinguishable after re-registration. */
public final class DynamicPendingReceiver extends BroadcastReceiver {
    private final String identity;
    public DynamicPendingReceiver(String identity){this.identity=identity;}
    @Override public void onReceive(Context context,Intent intent){try{
        String marker=intent.getStringExtra("marker");if(marker==null||!marker.matches("[a-z0-9-]{1,80}"))throw new IllegalArgumentException("OWNED_DYNAMIC_MARKER");
        if(isOrderedBroadcast()){setResultCode(91);setResultData(identity);}
        PendingReceiver.write(new File(context.getFilesDir(),"pending-"+marker+".json"),new JSONObject().put("identity",identity).put("pid",android.os.Process.myPid()).put("value",intent.getStringExtra("value")).put("added",intent.getStringExtra("added")).put("action",intent.getAction()).put("componentAbsent",intent.getComponent()==null).put("ordered",isOrderedBroadcast()).toString());
    }catch(Exception error){throw new IllegalStateException("OWNED_DYNAMIC_PENDING_FAILED",error);}}
}
