package dev.tripoder.hooklab;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.widget.*;
import dev.tripoder.control.ControlWire;
import java.util.*;
import org.json.*;

/** Manual pairing and exact-source review share the same project as local editing. */
final class WorkbenchAgentPanel {
    private final Activity page;private final WorkbenchApplication app;private final String project;
    private final TextView info;private final Button enable,pair,review,stop,identity;
    private final Handler main=new Handler(Looper.getMainLooper());private boolean disposed,loading;
    private JSONObject current;
    private final Runnable poll=new Runnable(){@Override public void run(){if(disposed)return;render();refresh();main.postDelayed(this,2000);}};
    WorkbenchAgentPanel(Activity page,WorkbenchApplication app,String project,LinearLayout parent){
        this.page=page;this.app=app;this.project=project;LinearLayout card=ProductUi.card(page);
        ProductUi.add(card,ProductUi.text(page,"外部 Agent",18,ProductUi.INK,true),0);
        ProductUi.add(card,ProductUi.text(page,"由 Sentinel 等桌面助手观察与部署。Tripoder 不连接模型；外部草稿不会覆盖项目编辑器。",13,ProductUi.MUTED,false),8);
        info=ProductUi.text(page,"",13,ProductUi.INK,false);info.setTextIsSelectable(true);info.setContentDescription("外部 Agent 会话状态");ProductUi.add(card,info,12);
        enable=ProductUi.button(page,"开启外部控制入口",false,()->{try{page.startForegroundService(new Intent(page,AgentControlService.class));}catch(Exception error){show(error);}render();});
        identity=ProductUi.button(page,"查看设备配对身份",false,()->{try{AgentDeviceKey key=app.agents.deviceKey();ProductUi.notice(page,"设备配对身份",key.fingerprint+"\n\n公钥（可复制到桌面配对配置）\n"+key.publicKey);}catch(Exception error){show(error);}});ProductUi.pair(card,enable,identity);
        pair=ProductUi.button(page,"批准配对请求",true,this::choose);review=ProductUi.button(page,"审阅外部脚本",false,this::review);ProductUi.pair(card,pair,review);
        stop=ProductUi.button(page,"撤销会话并停止入口",false,()->new AlertDialog.Builder(page).setTitle("撤销外部控制").setMessage("拒绝后续外部操作。仍有 Hook 或未确认操作时，引擎会终止该受控进程；不会把停用解释为业务数据回滚。").setNegativeButton("返回",null).setPositiveButton("撤销并停止",(d,w)->page.startService(new Intent(page,AgentControlService.class).setAction("stop"))).show());ProductUi.add(card,stop,10);
        ProductUi.add(parent,card,22);poll.run();
    }
    private void render(){if(disposed)return;var request=app.agents.pending();String text=app.agents.message;
        if(request!=null&&request.session.isEmpty())text+="\n待配对："+request.name+"\n客户端指纹："+request.fingerprint;
        if(current!=null)text+="\n会话："+current.optString("state")+" · "+(current.optBoolean("clientConnected")?"客户端已连接":"等待首次连接")+" · Hook："+current.optString("hookState")+"\n进程代次："+current.optString("processGeneration")+"\n剩余 "+current.optLong("remainingMs")/1000+" 秒 · "+current.optInt("remainingRequests")+" 次请求";
        info.setText(text);enable.setEnabled(app.engine.ready()&&!app.agents.listening);identity.setEnabled(true);pair.setEnabled(app.agents.listening&&request!=null&&request.session.isEmpty()&&!request.approving&&!loading);review.setEnabled(current!=null&&project.equals(current.optString("project"))&&current.has("preparedSourceSha256")&&current.optString("state").equals("OPEN"));stop.setEnabled(app.agents.listening);
    }
    private void refresh(){if(loading||!app.engine.ready()||app.agents.session.isEmpty())return;loading=true;String session=app.agents.session;
        app.engine.execute(ControlWire.request("agentInspect","session",session),(reply,error)->{loading=false;if(disposed)return;if(error!=null){show(error);return;}try{current=new JSONObject(reply.getString("result"));render();}catch(Exception invalid){show(invalid);}});
    }
    private void choose(){var request=app.agents.pending();if(request==null)return;loading=true;
        app.engine.execute(ControlWire.request("hookProcesses","project",project),(target,error)->{loading=false;if(disposed)return;if(error!=null){show(error);return;}try{
            JSONArray processes=new JSONArray(target.getString("processes"));if(processes.length()==0)throw new IllegalStateException("请先从项目启动应用，再授权实际运行进程。");
            String[] labels=new String[processes.length()];for(int i=0;i<labels.length;i++){JSONObject p=processes.getJSONObject(i);labels[i]=p.getString("processName")+" · PID "+p.getInt("pid");}
            new AlertDialog.Builder(page).setTitle("选择外部 Agent 可控制的进程").setItems(labels,(d,index)->new AlertDialog.Builder(page).setTitle("授权此客户端").setMessage(request.name+"\n"+request.fingerprint+"\n\n仅此项目及选中的进程，最长 10 分钟、256 次请求。激活前需在手机审阅该版本脚本。首次连接限 8 分钟；连接后断开联系 45 秒或授权到期时，仍有 Hook/未知操作的进程会被终止。").setNegativeButton("返回",null).setPositiveButton("授权观察与准备",(confirm,which)->{try{app.agents.approve(request,project,target,processes.getJSONObject(index),(reply,failure)->{if(failure!=null)show(failure);else refresh();render();});}catch(Exception invalid){show(invalid);}}).show()).show();
        }catch(Exception invalid){show(invalid);}});
    }
    private void review(){if(current==null)return;try{
        JSONObject snapshot=new JSONObject(current.toString());String source=snapshot.getString("draftSource"),sha=snapshot.getString("preparedSourceSha256"),session=snapshot.getString("session");
        TextView code=ProductUi.text(page,"SHA-256\n"+sha+"\n\n"+source,13,ProductUi.INK,false);code.setTypeface(android.graphics.Typeface.MONOSPACE);code.setTextIsSelectable(true);ScrollView scroll=new ScrollView(page);scroll.addView(code);
        new AlertDialog.Builder(page).setTitle("审阅外部脚本 · 仅批准此版本").setView(scroll).setNegativeButton("返回",null).setPositiveButton("批准此版本部署",(d,w)->app.engine.execute(ControlWire.request("agentApprove","session",session,"sourceSha256",sha),(reply,error)->{if(error!=null)show(error);else{app.agents.message="已批准该源码摘要；等待客户端提交激活并核对回执";refresh();}})).show();
    }catch(Exception error){show(error);}}
    private void show(Exception error){app.agents.message=error.getClass().getSimpleName()+": "+error.getMessage();render();}
    void dispose(){disposed=true;main.removeCallbacks(poll);}
}
