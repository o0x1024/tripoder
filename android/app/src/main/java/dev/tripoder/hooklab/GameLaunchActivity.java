package dev.tripoder.hooklab;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import org.json.*;

/** Visible launch progress and durable failure evidence, independent of the game process. */
public final class GameLaunchActivity extends Activity {
    private String projectId;
    private TextView status;
    private boolean bound,destroyed,launching,restarting,preparing;
    private Messenger service;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Messenger replies=new Messenger(new Handler(Looper.getMainLooper(),message->{
        if(destroyed)return true;
        preparing=false;
        try{
            JSONObject result=new JSONObject(message.getData().getString("result","{}"));
            if(result.optBoolean("restartRequired")){restart();return true;}
            if(result.has("error")){show("准备失败："+result.optString("error"));return true;}
            if(!result.optBoolean("ready"))throw new IllegalStateException("运行器没有返回准备状态");
            show("正在创建原始游戏窗口…");launching=true;
            startActivity(ProjectStore.intent(this,GameStubActivity.class,projectId));
        }catch(Exception error){show("启动未完成："+error.getMessage());}
        return true;
    }));
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){service=new Messenger(binder);prepare();}
        @Override public void onServiceDisconnected(ComponentName name){disconnected();}
        @Override public void onBindingDied(ComponentName name){disconnected();}
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        try{
            if(Build.VERSION.SDK_INT<29)throw new IllegalStateException("此游戏运行器需要 Android 10 或更高版本");
            ProjectStore store=new ProjectStore(this);store.initialize();projectId=getIntent().getStringExtra("projectId");
            if(projectId==null){for(JSONObject p:store.projects())if(GameRuntime.PACKAGE.equals(p.getJSONObject("app").optString("packageName"))){projectId=p.getString("id");break;}}
            if(projectId==null)throw new IllegalStateException("请先为王者荣耀创建项目");
            if(!GameRuntime.PACKAGE.equals(store.get(projectId).getJSONObject("app").optString("packageName")))throw new IllegalArgumentException("项目目标不是王者荣耀");
            LinearLayout page=ProductUi.screen(this,"启动王者荣耀",store.title(projectId));
            LinearLayout card=ProductUi.card(this);status=ProductUi.text(this,"正在准备独立游戏进程…",15,ProductUi.INK,false);ProductUi.add(card,status,0);
            ProductUi.add(card,ProductUi.text(this,"原始游戏代码 · 项目独立数据 · Hook 关闭\n首次运行可能需要下载游戏资源。",12,ProductUi.MUTED,false),14);
            ProductUi.add(card,ProductUi.button(this,"查看启动记录",false,()->startActivity(ProjectStore.intent(this,ProjectFilesActivity.class,projectId).putExtra("path","compatibility/game-launch.json"))),16);
            ProductUi.add(card,ProductUi.button(this,"重新启动",false,this::restart),12);ProductUi.add(page,card,18);
            showSaved();
            int seconds=Math.max(0,Math.min(60,getIntent().getIntExtra("gameDelaySeconds",0)));
            main.postDelayed(this::bind,seconds*1000L);
        }catch(Exception error){ProductUi.notice(this,"无法启动",error.getMessage());}
    }
    private void bind(){if(destroyed||bound)return;show("正在加载安装包和项目数据…");bound=bindService(new Intent(this,GameService.class),connection,BIND_AUTO_CREATE);if(!bound)show("无法连接游戏进程");}
    private void unbind(){if(bound){unbindService(connection);bound=false;}service=null;}
    private void disconnected(){boolean again=restarting;restarting=false;preparing=false;unbind();if(again)main.postDelayed(this::bind,700);else{showSaved();main.postDelayed(this::showSaved,1200);}}
    private void restart(){
        launching=false;preparing=false;
        if(service==null){unbind();bind();return;}
        restarting=true;show("正在重启游戏进程…");
        try{service.send(Message.obtain(null,GameService.STOP));}catch(RemoteException error){disconnected();}
    }
    private void prepare(){try{preparing=true;Message message=Message.obtain(null,GameService.PREPARE);Bundle b=new Bundle();b.putString("projectId",projectId);b.putBoolean("freshGameProcess",getIntent().getBooleanExtra("freshGameProcess",false));message.setData(b);message.replyTo=replies;service.send(message);
        main.postDelayed(()->{if(preparing&&!destroyed){preparing=false;unbind();show("加载超时。可重新启动，或查看启动记录。");}},45000);
    }catch(RemoteException e){preparing=false;show("游戏进程不可用");}}
    private void show(String text){if(!destroyed&&status!=null)status.setText(text);}
    private void showSaved(){try{
        ProjectStore store=new ProjectStore(this);JSONObject report=new JSONObject(store.read(projectId,"compatibility/game-launch.json","{}"));
        if(Build.VERSION.SDK_INT>=30){
            ActivityManager manager=getSystemService(ActivityManager.class);
            for(ApplicationExitInfo exit:manager.getHistoricalProcessExitReasons(getPackageName(),report.optInt("pid",-1),5)){
                if(exit.getTimestamp()<report.optLong("startedAt",Long.MAX_VALUE))continue;
                report.put("processExit",new JSONObject().put("reason",exit.getReason()).put("status",exit.getStatus()).put("timestamp",exit.getTimestamp()).put("description",exit.getDescription()));
                store.write(projectId,"compatibility/game-launch.json",report.toString(2));
                java.io.File output=getExternalFilesDir(null);if(output!=null)ProjectStore.atomic(new java.io.File(output,"game-launch-latest.json"),report.toString(2));break;
            }
        }
        String error=report.optString("error");JSONObject exit=report.optJSONObject("processExit");
        String stage=report.optString("stage","尚无记录");
        if(stage.equals("activity-resumed"))stage="游戏窗口已进入前台";
        else if(stage.equals("activity-paused"))stage="游戏窗口已进入后台";
        else if(stage.equals("failed"))stage="初始化失败";
        String exitText=exit==null?"":exit.optInt("reason")==ApplicationExitInfo.REASON_SIGNALED&&exit.optInt("status")==7?"进程收到 SIGBUS 信号，稳定性验证未通过。":"退出原因 "+exit.optInt("reason")+" · 状态码 "+exit.optInt("status");
        show((exit==null?"最近阶段：":"游戏进程已结束。最近阶段：")+stage+(error.isEmpty()?"":"\n"+error.substring(0,Math.min(error.length(),1000)))+(exit==null?"":"\n"+exitText));
    }catch(Exception e){show("无法读取启动记录");}}
    @Override protected void onResume(){super.onResume();if(launching)showSaved();}
    @Override protected void onDestroy(){destroyed=true;main.removeCallbacksAndMessages(null);unbind();super.onDestroy();}
}
