package dev.tripoder.p2;

import android.app.Activity;
import android.content.*;
import android.os.*;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.*;
import org.json.*;

/** Two real processes race distinct-key commits to the same data incarnation. */
final class PreferenceProcesses {
    // The peer is a cold guest process whose legitimate first-code install (full 2177 bindings) runs under the
    // product's authoritative 30s startup deadline; observed cold installs reach ~10-12s. The main process's wait
    // for the peer's ready marker must therefore cover that window plus observation margin, never pre-empt it.
    // Matches the established fixture-observer convention (product 30s + 5s). The go handshake is between two
    // already-live processes, so it stays a short barrier.
    private static final long COLD_PEER_READY_TIMEOUT_MS=35000;
    private static final long LIVE_HANDSHAKE_TIMEOUT_MS=10000;
    static JSONObject run(Activity activity,BlockingQueue<JSONObject> results)throws Exception{
        File ready=new File(activity.getFilesDir(),"prefs-peer-ready"),go=new File(activity.getFilesDir(),"prefs-peer-go");
        Files.deleteIfExists(ready.toPath());Files.deleteIfExists(go.toPath());
        SharedPreferences prefs=activity.getSharedPreferences("parallel",Context.MODE_PRIVATE);
        if(!prefs.edit().clear().putString("baseline","owned").commit())throw new IllegalStateException("PREFS_INITIAL_COMMIT_FAILED");
        activity.runOnUiThread(()->activity.startActivityForResult(new Intent(activity,Storage.Remote.class).putExtra("preferencesOnly",true),63));
        waitFor(ready,COLD_PEER_READY_TIMEOUT_MS);Files.writeString(go.toPath(),"go");
        long start=SystemClock.elapsedRealtimeNanos();write(prefs,"left");long end=SystemClock.elapsedRealtimeNanos();
        JSONObject peer=results.poll(20,TimeUnit.SECONDS);if(peer==null||!peer.optBoolean("passed"))throw new IllegalStateException("PREFS_PEER_FAILED:"+peer);
        boolean cached=!prefs.contains("right-49");
        SharedPreferences refreshed=activity.getSharedPreferences("parallel",Context.MODE_MULTI_PROCESS);
        boolean values="owned".equals(refreshed.getString("baseline",null))&&refreshed.getAll().size()==101;
        for(int i=0;i<50;i++)values&=refreshed.getInt("left-"+i,-1)==i&&refreshed.getInt("right-"+i,-1)==i;
        boolean overlap=start<peer.getLong("end")&&peer.getLong("start")<end;
        return new JSONObject().put("passed",cached&&refreshed==prefs&&values&&overlap&&peer.getInt("pid")!=android.os.Process.myPid())
            .put("privateCacheStable",cached).put("sameObjectReloaded",refreshed==prefs).put("bothWritersRetained",values).put("overlap",overlap)
            .put("start",start).put("end",end).put("pid",android.os.Process.myPid()).put("peer",peer);
    }
    static void remote(Activity activity){new Thread(()->{
        JSONObject result=new JSONObject();try{
            SharedPreferences prefs=activity.getSharedPreferences("parallel",Context.MODE_PRIVATE);
            if(!"owned".equals(prefs.getString("baseline",null)))throw new IllegalStateException("PREFS_PEER_INITIAL_VALUE_MISSING");
            Files.writeString(new File(activity.getFilesDir(),"prefs-peer-ready").toPath(),"ready");waitFor(new File(activity.getFilesDir(),"prefs-peer-go"),LIVE_HANDSHAKE_TIMEOUT_MS);
            long start=SystemClock.elapsedRealtimeNanos();write(prefs,"right");long end=SystemClock.elapsedRealtimeNanos();
            result.put("passed",true).put("start",start).put("end",end).put("pid",android.os.Process.myPid());
        }catch(Throwable error){try{result.put("passed",false).put("error",android.util.Log.getStackTraceString(error));}catch(JSONException ignored){}}
        activity.runOnUiThread(()->{activity.setResult(Activity.RESULT_OK,new Intent().putExtra("result",result.toString()));activity.finish();});
    },"preference-peer").start();}
    private static void write(SharedPreferences prefs,String prefix){for(int i=0;i<50;i++)if(!prefs.edit().putInt(prefix+"-"+i,i).commit())throw new IllegalStateException("PREFS_CONCURRENT_COMMIT_FAILED");}
    private static void waitFor(File marker,long timeoutMs)throws Exception{long until=SystemClock.elapsedRealtime()+timeoutMs;while(!marker.exists()){if(SystemClock.elapsedRealtime()>until)throw new IllegalStateException("PREFS_BARRIER_TIMEOUT");Thread.sleep(5);}}
    private PreferenceProcesses(){}
}
