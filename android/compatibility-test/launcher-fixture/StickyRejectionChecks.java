package dev.tripoder.launchcontract;

import android.content.*;

/** A rejected send cannot overwrite the real battery sticky view with owned fake data. */
final class StickyRejectionChecks {
    static void run(Context context,OrderedResultChecks.Checks checks)throws Exception{
        String marker="owned-rejected-sticky-marker";
        Intent invalid=new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(marker,true);invalid.setClipData(ClipData.newPlainText("owned","owned"));
        try{
            boolean refused=false;try{context.sendStickyBroadcast(invalid);}catch(IllegalArgumentException|UnsupportedOperationException expected){refused=true;}
            checks.accept("invalid-sticky-send-is-explicitly-rejected",refused);
            Intent actual=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            checks.accept("rejected-sticky-send-publishes-no-local-observation",actual==null||!actual.hasExtra(marker));
        }finally{context.removeStickyBroadcast(invalid);}
    }
    private StickyRejectionChecks(){}
}
