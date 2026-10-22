package dev.tripoder.hooklab;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.widget.*;

/** One phone endpoint, two selectable client entry points. */
final class WorkbenchAgentPanel {
    private final Activity page;private final WorkbenchApplication app;private final TextView info;
    private final Button enable,stop,export,reset;private final Handler main=new Handler(Looper.getMainLooper());private boolean disposed;
    private final Runnable poll=new Runnable(){public void run(){if(disposed)return;render();main.postDelayed(this,2000);}};
    WorkbenchAgentPanel(Activity page,WorkbenchApplication app,String project,LinearLayout parent){
        this.page=page;this.app=app;LinearLayout card=ProductUi.card(page);
        ProductUi.add(card,ProductUi.text(page,"外部 Agent",18,ProductUi.INK,true),0);
        ProductUi.add(card,ProductUi.text(page,"选择适合客户端的方式：stdio 使用电脑 Node 适配器；HTTP 直接连接下方 MCP 地址。两种方式共用手机控制管线，无需 ADB。请先连接 Wi-Fi 或以太网。",13,ProductUi.MUTED,false),8);
        info=ProductUi.text(page,"",13,ProductUi.INK,false);info.setContentDescription("外部 Agent 连接状态");ProductUi.add(card,info,12);
        enable=ProductUi.button(page,"开启网络 MCP",true,()->{
            page.getSharedPreferences("mcp-network",Context.MODE_PRIVATE).edit().putBoolean("enabled",true).commit();
            try{page.startForegroundService(new Intent(page,AgentControlService.class));}catch(Exception error){app.agents.message=UiText.error(error);}render();
        });
        stop=ProductUi.button(page,"停止连接",false,()->page.startService(new Intent(page,AgentControlService.class).setAction("stop")));ProductUi.pair(card,enable,stop);
        export=ProductUi.button(page,"导出连接配置",false,()->page.startActivity(new Intent(page,AgentConnectionActivity.class)));ProductUi.add(card,export,8);
        ProductUi.add(card,ProductUi.text(page,"配置包含访问凭证，请仅交给可信客户端。HTTP 客户端需信任导出的证书并配置 Bearer 令牌。重置后须重新导出；不需要逐条批准工具调用。",12,ProductUi.MUTED,false),8);
        reset=ProductUi.button(page,"重置访问凭证",false,()->ProductUi.confirm(page,"重置访问凭证？","现有连接会关闭，旧配置将失效。客体应用继续运行。","重置",false,()->{try{AgentNetworkIdentity.rotate(page);page.startService(new Intent(page,AgentControlService.class).setAction("stop"));app.agents.message="访问凭证已重置，请重新开启并导出配置";}catch(Exception error){app.agents.message=UiText.error(error);}render();}));ProductUi.add(card,reset,8);
        ProductUi.add(parent,card,12);poll.run();
    }
    private void render(){info.setText(app.agents.message);enable.setEnabled(app.engine.ready()&&!app.agents.listening);stop.setEnabled(app.agents.listening);export.setEnabled(app.agents.listening);reset.setEnabled(app.agents.listening);}
    void dispose(){disposed=true;main.removeCallbacks(poll);}
}
