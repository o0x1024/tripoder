package dev.tripoder.pendingfixture;

import android.content.*;
import android.os.*;
import java.io.*;
import org.json.*;

public final class PendingReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){try{
        String marker=intent.getStringExtra("marker");if(marker==null||!marker.matches("[a-z0-9-]{1,80}"))throw new IllegalArgumentException("OWNED_MARKER_REQUIRED");
        JSONObject value=new JSONObject().put("marker",marker).put("value",intent.getStringExtra("value")).put("added",intent.getStringExtra("added")).put("component",intent.getComponent().flattenToString()).put("action",intent.getAction()).put("identifier",intent.getIdentifier()).put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid()).put("process",android.app.Application.getProcessName()).put("ordered",isOrderedBroadcast()).put("elapsed",SystemClock.elapsedRealtime()).put("alarmCount",intent.getIntExtra(Intent.EXTRA_ALARM_COUNT,0));
        if(isOrderedBroadcast()){setResultCode(77);setResultData("owned-result");}
        if(marker.equals("after-death")){
            var manager=context.getSystemService(android.app.ActivityManager.class);JSONArray exits=new JSONArray();
            for(var exit:manager.getHistoricalProcessExitReasons(context.getPackageName(),0,0))exits.put(new JSONObject().put("pid",exit.getPid()).put("uid",exit.getRealUid()).put("process",exit.getProcessName()).put("reason",exit.getReason()).put("status",exit.getStatus()).put("timestamp",exit.getTimestamp()).put("summaryHidden",exit.getProcessStateSummary()==null));
            value.put("history",exits).put("nullPackageHistoryCount",manager.getHistoricalProcessExitReasons(null,0,0).size());
            if(exits.length()>0){int prior=exits.getJSONObject(0).getInt("pid");var filtered=manager.getHistoricalProcessExitReasons(context.getPackageName(),prior,1);value.put("pidFiltered",filtered.size()==1&&filtered.get(0).getPid()==prior);}
            boolean other=false,negative=false,stamp=false;
            try{manager.getHistoricalProcessExitReasons("android",0,0);}catch(SecurityException expected){other=true;}
            try{manager.getHistoricalProcessExitReasons(context.getPackageName(),0,-1);}catch(IllegalArgumentException expected){negative=true;}
            // A guest may set its own summary (Chromium does); it must never replace the Runtime ownership stamp,
            // so the logical exit history keeps hiding every summary and oversized values stay rejected.
            try{manager.setProcessStateSummary(new byte[]{1});manager.setProcessStateSummary(new byte[129]);}catch(IllegalArgumentException expected){stamp=true;}
            for(var exit:manager.getHistoricalProcessExitReasons(context.getPackageName(),0,0))stamp&=exit.getProcessStateSummary()==null;
            value.put("otherPackageRejected",other).put("negativeLimitRejected",negative).put("ownershipStampProtected",stamp);
        }
        write(new File(context.getFilesDir(),"pending-"+marker+".json"),value.toString());
    }catch(Exception error){throw new IllegalStateException("OWNED_PENDING_RECEIVER_FAILED",error);}}
    static void write(File file,String value)throws Exception{File temporary=new File(file+".tmp");try(var out=new FileOutputStream(temporary)){out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}if(!temporary.renameTo(file))throw new IOException("OWNED_REPORT_RENAME");}
}
