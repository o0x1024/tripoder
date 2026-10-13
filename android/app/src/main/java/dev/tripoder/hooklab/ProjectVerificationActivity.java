package dev.tripoder.hooklab;

import android.app.Activity;
import android.os.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Explicit on-device regression for project ownership and real process transitions. */
public final class ProjectVerificationActivity extends Activity {
    private final JSONArray checks=new JSONArray();private final JSONObject report=new JSONObject();
    private final List<GuestClient> clients=new ArrayList<>();private ProjectStore store;private String a,b,defaultDraft;private GuestClient first,current;private int pidA;private TextView status;private boolean finished;
    private final Handler main=new Handler(Looper.getMainLooper());
    @Override public void onCreate(Bundle saved){super.onCreate(saved);LinearLayout page=ProductUi.screen(this,"项目隔离诊断","仅检查临时自有项目");status=ProductUi.text(this,"正在准备…",14,ProductUi.INK,true);ProductUi.add(page,status,16);
        int seconds=getIntent().getIntExtra("autoDelaySeconds",getIntent().getIntExtra("delay",0));main.postDelayed(this::start,Math.max(0,Math.min(60,seconds))*1000L);main.postDelayed(()->finishReport(new IOException("Project verification timed out")),90000);
    }
    private void start(){try{
        report.put("startedAtMs",System.currentTimeMillis()).put("hostApkSha256",GuestStore.sha256(new File(getApplicationInfo().sourceDir))).put("uid",android.os.Process.myUid()).put("pid",android.os.Process.myPid())
            .put("debuggable",(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0).put("debuggerConnected",Debug.isDebuggerConnected());
        store=new ProjectStore(this);store.initialize();defaultDraft=store.read(ProjectStore.DEFAULT,"scripts/main.ts","");a=store.create("临时验证 A",ProjectStore.fixture());b=store.create("临时验证 B",ProjectStore.fixture());
        store.write(a,"assets/value.txt","A");store.write(b,"assets/value.txt","B");check("project-files-independent",store.read(a,"assets/value.txt","").equals("A")&&store.read(b,"assets/value.txt","").equals("B"));
        check("project-files-survive-store-recreation",new ProjectStore(this).read(a,"assets/value.txt","").equals("A"));
        boolean rejected=false;try{store.write(a,"../escape.txt","bad");}catch(Exception expected){rejected=true;}check("project-path-traversal-rejected",rejected);
        connect(a,editor->{first=current;try{String source=editor.getString("template").replace("script:","project-a:");Bundle input=new Bundle();input.putString("source",source);input.putString("session",editor.getJSONObject("evidence").getString("id"));send(GuestService.DEPLOY_SCRIPT,input,ignored->send(GuestService.SAMPLE,new Bundle(),sample->{try{
            check("project-a-real-hook",sample.optString("java").equals("project-a:PHONE")&&sample.optInt("native")==706);pidA=sample.getInt("pid");file("write","A",w->switchToB());
        }catch(Exception error){finishReport(error);}}));}catch(Exception error){finishReport(error);}});
    }catch(Exception error){finishReport(error);}}
    private void switchToB(){connect(b,editor->{try{
        check("project-b-draft-independent",!editor.optString("draft").contains("project-a:"));
        first.send(GuestService.SAMPLE,new Bundle(),stale->{try{check("stale-project-client-rejected",stale.has("error"));send(GuestService.SAMPLE,new Bundle(),sample->{try{
            check("project-switch-restarts-process",sample.getInt("pid")!=pidA);check("project-b-has-no-a-hook",sample.optString("java").equals("guest-original:phone")&&sample.optInt("native")==16);
            Bundle input=new Bundle();input.putString("operation","read");input.putString("path","project-marker.txt");current.send(GuestService.FILE,input,missing->{try{check("project-b-cannot-read-a-data",missing.has("error"));file("write","B",written->returnToA());}catch(Exception error){finishReport(error);}});
        }catch(Exception error){finishReport(error);}});}catch(Exception error){finishReport(error);}});
    }catch(Exception error){finishReport(error);}});}
    private void returnToA(){connect(a,editor->{try{check("project-a-draft-retained",editor.optString("draft").contains("project-a:"));file("read","",data->{try{check("project-a-runtime-data-retained",data.optString("content").equals("A"));send(GuestService.SAMPLE,new Bundle(),sample->{try{check("restart-does-not-auto-enable-hook",sample.optString("java").equals("guest-original:phone"));connect(ProjectStore.DEFAULT,original->{try{check("default-project-draft-preserved",defaultDraft.equals(store.read(ProjectStore.DEFAULT,"scripts/main.ts","")));finishReport(null);}catch(Exception error){finishReport(error);}});}catch(Exception error){finishReport(error);}});}catch(Exception error){finishReport(error);}});}catch(Exception error){finishReport(error);}});}
    private void connect(String id,Consumer<JSONObject> callback){status.setText("正在验证项目切换…");current=new GuestClient(this,id);clients.add(current);GuestClient selected=current;selected.connect(()->selected.send(GuestService.EDITOR,new Bundle(),value->{if(value.has("error")){finishReport(new IOException(value.optString("error")));return;}callback.accept(value);}));}
    private void send(int op,Bundle data,Consumer<JSONObject> callback){current.send(op,data,value->{if(finished)return;if(value.has("error")){finishReport(new IOException(value.optString("error")));return;}callback.accept(value);});}
    private void file(String op,String content,Consumer<JSONObject> callback){Bundle input=new Bundle();input.putString("operation",op);input.putString("path","project-marker.txt");input.putString("content",content);send(GuestService.FILE,input,callback);}
    private void check(String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new IOException(name);}
    private void finishReport(Exception error){if(finished)return;finished=true;main.removeCallbacksAndMessages(null);for(GuestClient client:clients)client.close();try{report.put("passed",error==null).put("checks",checks).put("finishedAtMs",System.currentTimeMillis());if(error!=null)report.put("error",error.toString());
        if(error==null){remove(store.directory(a));remove(store.directory(b));}
        try(FileOutputStream out=new FileOutputStream(new File(getExternalFilesDir(null),"project-result.json"))){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        status.setText((error==null?"PASS":"FAIL")+" · "+checks.length()+" 项\n"+(error==null?"项目文件、运行数据、进程重启与旧会话拒绝通过。":error.getMessage()));
    }catch(Exception failure){status.setText(failure.toString());}}
    private static void remove(File root)throws Exception{if(Files.isSymbolicLink(root.toPath())){Files.delete(root.toPath());return;}File[] children=root.listFiles();if(children!=null)for(File f:children)remove(f);Files.deleteIfExists(root.toPath());}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);for(GuestClient client:clients)client.close();super.onDestroy();}
}
