package dev.tripoder.runtimeprobe;

import android.app.Activity;
import android.content.*;
import android.os.*;
import dev.tripoder.runtime.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** UI calls the same FD install, process supervisor, and ticket protocol as runtime acceptance. */
final class PreviewClient implements AutoCloseable {
    interface Listener {void changed();void launch(Intent intent);}
    final PreviewRecords records;
    JSONArray spaces=new JSONArray();JSONObject fixture,run;
    volatile boolean busy=true,ready,closed;
    String message="正在连接运行内核…",error="",selected="";
    private final Activity activity;private final Listener listener;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Link link=new Link();private boolean bound;
    PreviewClient(Activity a,Listener l)throws Exception {activity=a;listener=l;records=new PreviewRecords(a);selected=a.getPreferences(0).getString("space","");}
    void connect(){bound=activity.getApplicationContext().bindService(new Intent(activity,P1Authority.class),link,Context.BIND_AUTO_CREATE);
        if(!bound){busy=false;error="无法连接运行内核";listener.changed();return;}
        execute(()->{if(!link.connected.await(15,TimeUnit.SECONDS)||link.peer==null)throw new IOException("AUTHORITY_BIND_FAILED");call(request("status"));try(var in=activity.getAssets().open("preview-fixture.json")){fixture=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}ready=true;readSnapshot();message="内核已连接";});
    }
    void refresh(){if(!busy&&ready)execute(this::readSnapshot);}
    void select(String id){for(int i=0;i<spaces.length();i++)if(id.equals(spaces.optJSONObject(i).optString("id"))){selected=id;activity.getPreferences(0).edit().putString("space",id).apply();listener.changed();return;}throw new IllegalArgumentException("PREVIEW_SPACE_NOT_FOUND");}
    JSONObject current(){for(int i=0;i<spaces.length();i++)if(selected.equals(spaces.optJSONObject(i).optString("id")))return spaces.optJSONObject(i);return null;}
    String title(String id){for(int i=0;i<spaces.length();i++)if(id.equals(spaces.optJSONObject(i).optString("id")))return "体验空间 "+String.format(Locale.ROOT,"%02d",i+1);return "未选择空间";}
    void create(){if(!ready||busy)return;execute(()->{selected=call(request("previewCreateSpace")).getString("space");readSnapshot();message="空间已创建";});}
    private void readSnapshot()throws Exception {spaces=new JSONArray(call(request("previewSnapshot")).getString("spaces"));boolean found=false;for(int i=0;i<spaces.length();i++)found|=selected.equals(spaces.getJSONObject(i).getString("id"));if(!found)selected=spaces.length()==0?"":spaces.getJSONObject(0).getString("id");activity.getPreferences(0).edit().putString("space",selected).apply();}
    void start(){if(!ready||busy)return;execute(()->{
        if(selected.isEmpty()){selected=call(request("previewCreateSpace")).getString("space");readSnapshot();}
        if(records.unresolved(selected))throw new IllegalStateException("此空间存在待确认的运行记录，已停止新启动。请查看记录详情。");
        run=new JSONObject().put("id",UUID.randomUUID().toString()).put("space",selected).put("created",System.currentTimeMillis()).put("state","PREPARING").put("challenge",UUID.randomUUID().toString()).put("hostApkSha256",ApkStore.digest(new File(activity.getApplicationInfo().sourceDir))).put("fixtureSha256",fixture.getString("sha256"));records.save(run);
        phase("正在准备体验应用…");JSONObject installed=current().optJSONObject("install");
        if(installed==null)install();
        else if(!installed.getString("sha256").equals(fixture.getString("sha256")))throw new IllegalStateException("体验应用版本已变化，原有空间的数据保持不变。请新建体验空间。");
        phase("正在启动客体进程…");Bundle prepared=call(request("prepareActivity","space",selected,"package","dev.tripoder.previewguest","component","dev.tripoder.previewguest.CounterActivity"));
        Intent intent=prepared.getParcelable("intent",Intent.class);if(intent==null)throw new IllegalStateException("PREVIEW_INTENT_MISSING");
        run.put("ticket",intent.getStringExtra("tripoder.ticket")).put("generation",prepared.getString("generation")).put("pid",prepared.getInt("pid")).put("state","RUNNING");records.save(run);
        intent.putExtra("challenge",run.getString("challenge"));readSnapshot();
        activity.runOnUiThread(()->{if(closed)return;try{listener.launch(intent);}catch(Exception e){failLaunch(e);}});message="应用正在运行";
    });}
    private void install()throws Exception {
        File staged=File.createTempFile("preview-",".apk",activity.getCacheDir());
        try{try(var in=activity.getAssets().open(fixture.getString("asset"));var out=new FileOutputStream(staged)){in.transferTo(out);out.getFD().sync();}
            if(!ApkStore.digest(staged).equals(fixture.getString("sha256")))throw new SecurityException("PREVIEW_APK_DIGEST_MISMATCH");
            try(var fd=ParcelFileDescriptor.open(staged,ParcelFileDescriptor.MODE_READ_ONLY)){
                Bundle request=request("install","operation","preview-install-"+UUID.randomUUID(),"space",selected);request.putStringArrayList("names",new ArrayList<>(List.of("base.apk")));request.putStringArrayList("sha256",new ArrayList<>(List.of(fixture.getString("sha256"))));request.putParcelable("fd0",fd);call(request);
            }
        }finally{if(!staged.delete()&&staged.exists())throw new IOException("PREVIEW_STAGING_CLEANUP_FAILED");}
    }
    void result(int code,Intent data){if(run==null){error="运行关联已丢失；结果未被认定为成功。";listener.changed();return;}execute(()->{
        JSONObject result=null;
        if(code==Activity.RESULT_OK){if(data==null||data.getStringExtra("result")==null)throw new IllegalStateException("PREVIEW_RESULT_MISSING");result=new JSONObject(data.getStringExtra("result"));
            if(!run.getString("challenge").equals(result.getString("challenge"))||!"dev.tripoder.previewguest".equals(result.getString("package"))||result.getInt("pid")!=run.getInt("pid")||result.getInt("pid")==android.os.Process.myPid()||result.getInt("uid")!=android.os.Process.myUid()||result.getInt("count")<0)throw new SecurityException("PREVIEW_RESULT_IDENTITY_MISMATCH");
            run.put("result",result);
        }else if(code!=Activity.RESULT_CANCELED)throw new IllegalStateException("PREVIEW_RESULT_CODE_INVALID");
        run.put("state","RECEIVED").put("returnedAt",System.currentTimeMillis());records.save(run);
        call(request("completeActivity","ticket",run.getString("ticket")));
        call(request("stopProcess","generation",run.getString("generation")));
        run.put("state",result==null?"CANCELLED":"SUCCEEDED").put("finished",System.currentTimeMillis());records.save(run);
        JSONObject completed=run;run=null;
        // Export has its own outcome. A failed evidence copy must not reverse an
        // already acknowledged and durably saved execution outcome.
        message=result==null?"应用已返回，未提交计数结果":"运行完成 · 计数 "+result.getInt("count");
        try{PreviewRecords.export(activity,completed);}catch(Exception export){error="运行结果已保存，证据导出失败："+export.getMessage();}
        readSnapshot();
    });}
    private void failLaunch(Exception error){execute(()->{throw error;});}
    private interface Work {void run()throws Exception;}
    private void execute(Work work){if(closed)return;busy=true;error="";listener.changed();io.execute(()->{
        try{work.run();}catch(Exception e){error=e.getMessage()==null?e.toString():e.getMessage();message="操作未完成";if(run!=null)try{run.put("state","UNKNOWN").put("error",error);records.save(run);}catch(Exception save){error+=" · 记录保存失败："+save.getMessage();}run=null;}
        finally{busy=false;activity.runOnUiThread(()->{if(!closed)listener.changed();});}
    });}
    private void phase(String value){message=value;activity.runOnUiThread(()->{if(!closed)listener.changed();});}
    private Bundle call(Bundle input)throws Exception {if(closed||link.peer==null)throw new IllegalStateException("PREVIEW_CONNECTION_CLOSED");return RuntimeWire.call(link.peer,RuntimeWire.CONTROL,input);}
    private static Bundle request(String command,String...pairs){Bundle b=new Bundle();b.putString("command",command);for(int i=0;i<pairs.length;i+=2)b.putString(pairs[i],pairs[i+1]);return b;}
    @Override public void close(){closed=true;io.shutdown();if(bound){activity.getApplicationContext().unbindService(link);bound=false;}}
    private final class Link implements ServiceConnection {
        final CountDownLatch connected=new CountDownLatch(1);volatile IBinder peer;
        public void onServiceConnected(ComponentName n,IBinder p){peer=p;connected.countDown();}
        public void onServiceDisconnected(ComponentName n){peer=null;ready=false;error="运行内核连接中断；未自动重试。";if(!closed)listener.changed();}
        public void onNullBinding(ComponentName n){connected.countDown();}
    }
}
