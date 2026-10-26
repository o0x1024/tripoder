package dev.tripoder.hooklab;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.widget.*;

/** One phone endpoint, two selectable client entry points. */
final class WorkbenchAgentPanel {
    private final Activity page;private final WorkbenchApplication app;private final TextView info;
    private final Button enable,stop,export;private final Handler main=new Handler(Looper.getMainLooper());private boolean disposed;
    private final Runnable poll=new Runnable(){public void run(){if(disposed)return;render();main.postDelayed(this,2000);}};
    WorkbenchAgentPanel(Activity page,WorkbenchApplication app,String project,LinearLayout parent){
        this.page=page;this.app=app;LinearLayout card=ProductUi.card(page);
        ProductUi.add(card,ProductUi.text(page,"外部 Agent",18,ProductUi.INK,true),0);
        ProductUi.add(card,ProductUi.text(page,"选择适合客户端的方式：stdio 使用电脑 Node 适配器；HTTP 直接连接下方 MCP 地址。两种方式共用手机控制管线，无需 ADB。请先连接 Wi-Fi 或以太网。",13,ProductUi.MUTED,false),8);
        info=ProductUi.text(page,"",13,ProductUi.INK,false);info.setContentDescription("外部 Agent 连接状态");ProductUi.add(card,info,12);
        enable=ProductUi.button(page,"开启网络 MCP",true,()->{
            AgentNetworkAccess.enable(page);render();
        });
        stop=ProductUi.button(page,"停止连接",false,()->page.startService(new Intent(page,AgentControlService.class).setAction("stop")));ProductUi.pair(card,enable,stop);
        export=ProductUi.button(page,"导出连接配置",false,()->page.startActivity(new Intent(page,AgentConnectionActivity.class)));ProductUi.add(card,export,8);
        ProductUi.add(card,ProductUi.text(page,"连接使用 HTTP，配置仅包含 MCP 地址，无需证书或访问令牌。",12,ProductUi.MUTED,false),8);
        ProductUi.add(parent,card,12);poll.run();
    }
    private void render(){info.setText(app.agents.message);enable.setEnabled(app.engine.ready()&&!app.agents.listening);stop.setEnabled(app.agents.listening);export.setEnabled(app.agents.listening);}
    void dispose(){disposed=true;main.removeCallbacks(poll);}
}
