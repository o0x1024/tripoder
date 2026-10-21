package dev.tripoder.hooklab;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.widget.*;

/** Connection status and a stop switch. Desktop Agents select their own target. */
final class WorkbenchAgentPanel {
    private final Activity page;private final WorkbenchApplication app;private final TextView info;
    private final Button enable,stop;private final Handler main=new Handler(Looper.getMainLooper());private boolean disposed;
    private final Runnable poll=new Runnable(){public void run(){if(disposed)return;render();main.postDelayed(this,2000);}};
    WorkbenchAgentPanel(Activity page,WorkbenchApplication app,String project,LinearLayout parent){
        this.page=page;this.app=app;LinearLayout card=ProductUi.card(page);
        ProductUi.add(card,ProductUi.text(page,"外部 Agent",18,ProductUi.INK,true),0);
        ProductUi.add(card,ProductUi.text(page,"电脑上的 MCP Agent 可直接连接。无需设备身份、公钥、配对文件或脚本审批。MCP 是全局控制入口，可管理空间、应用、脚本和进程；每次操作明确指定目标。",13,ProductUi.MUTED,false),8);
        info=ProductUi.text(page,"",13,ProductUi.INK,false);info.setContentDescription("外部 Agent 连接状态");ProductUi.add(card,info,12);
        enable=ProductUi.button(page,"开启电脑 Agent 入口",true,()->{
            page.getSharedPreferences("agent-control",Context.MODE_PRIVATE).edit().putBoolean("enabled",true).apply();
            try{page.startForegroundService(new Intent(page,AgentControlService.class));}catch(Exception error){app.agents.message=UiText.error(error);}render();
        });
        stop=ProductUi.button(page,"停止电脑连接",false,()->page.startService(new Intent(page,AgentControlService.class).setAction("stop")));
        ProductUi.pair(card,enable,stop);ProductUi.add(parent,card,12);poll.run();
    }
    private void render(){info.setText(app.agents.message);enable.setEnabled(app.engine.ready()&&!app.agents.listening);stop.setEnabled(app.agents.listening);}
    void dispose(){disposed=true;main.removeCallbacks(poll);}
}
