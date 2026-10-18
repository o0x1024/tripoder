package dev.tripoder.hooklab;

import android.app.*;
import android.os.Bundle;
import android.widget.*;
import dev.tripoder.control.ControlWire;
import java.util.*;
import java.util.function.Supplier;
import org.json.*;

/** One explicit observe/prepare/activate flow through the production controller. */
final class WorkbenchHookPanel implements EngineConnection.Listener {
    private final Activity page;private final WorkbenchApplication app;private final String project;private final Supplier<String> source;
    private final WorkbenchHooks.Session session;private final TextView info;private final Button process,observe,prepare,activate,disable,discard,query,params,modules,configure;
    private boolean destroyed,loading;private final Runnable operationObserver=this::render;
    WorkbenchHookPanel(Activity page,WorkbenchApplication app,String project,Supplier<String> source,LinearLayout parent){
        this.page=page;this.app=app;this.project=project;this.source=source;session=app.hooks.project(project);
        LinearLayout card=ProductUi.card(page);ProductUi.add(card,ProductUi.text(page,"脚本部署",18,ProductUi.INK,true),0);
        ProductUi.add(card,ProductUi.text(page,"观察真实目标，检查已保存的脚本，再确认激活。每次部署只作用于选中的进程。",13,ProductUi.MUTED,false),8);
        info=ProductUi.text(page,"",13,ProductUi.INK,false);info.setTextIsSelectable(true);ProductUi.add(card,info,14);
        process=ProductUi.button(page,"选择运行进程",false,this::chooseProcess);configure=ProductUi.button(page,"配置目标声明",false,this::configure);ProductUi.pair(card,process,configure);
        observe=ProductUi.button(page,"1 · 观察目标",false,()->perform("observe",()->new JSONObject().put("selectors",new JSONArray(app.projects.selectors(project))),"观察目标"));
        prepare=ProductUi.button(page,"2 · 检查脚本",false,this::prepare);ProductUi.pair(card,observe,prepare);
        activate=ProductUi.button(page,"3 · 确认激活",true,this::activate);disable=ProductUi.button(page,"停用脚本",false,()->perform("disable",()->new JSONObject().put("revision",session.status.getString("revision")),"停用脚本"));ProductUi.pair(card,activate,disable);
        query=ProductUi.button(page,"查询 Hook 状态",false,()->perform("status",JSONObject::new,"查询 Hook 状态"));discard=ProductUi.button(page,"放弃未激活准备",false,()->perform("discard",JSONObject::new,"放弃准备"));ProductUi.pair(card,query,discard);
        params=ProductUi.button(page,"调整参数",false,this::parameters);modules=ProductUi.button(page,"查看 Native 模块",false,()->perform("modules",JSONObject::new,"读取模块"));ProductUi.pair(card,params,modules);
        ProductUi.add(parent,card,22);app.operations.observe(operationObserver);app.engine.observe(this);render();
    }
    void dispose(){destroyed=true;app.operations.remove(operationObserver);app.engine.remove(this);}
    @Override public void changed(){
        if(app.engine.ready()&&!session.lease.equals(app.engine.lease)){session.clearEvidence();session.lease=app.engine.lease;session.message="连接已改变，请重新查询状态；旧准备令牌已清除。";}render();
    }
    void sourceChanged(){render();}
    private void render(){
        if(destroyed)return;boolean ready=app.engine.ready(),busy=app.operations.busyProject(project)||loading;
        String state=session.status==null?"UNCONFIRMED":session.status.optString("state","UNCONFIRMED");boolean active=state.equals("ACTIVE")||state.equals("DRAINING");
        String heading=session.target==null?"尚未选择运行进程":session.name+" · PID "+session.pid+"\n空间："+session.target.getString("space");
        String detail="";if(session.status!=null){detail="\n最近确认："+state;if(session.status.has("revision"))detail+="\n版本："+session.status.optString("revision");if(session.status.has("sourceSha256"))detail+="\n源码 SHA："+session.status.optString("sourceSha256");if(session.status.has("hits"))detail+="\n命中 "+session.status.optLong("hits")+" · 错误 "+session.status.optLong("errors")+" · 在途 "+session.status.optInt("inFlight");}
        info.setText((ready?"":"引擎未连接\n")+heading+detail+"\n"+session.message);
        process.setEnabled(ready&&!busy);configure.setEnabled(!busy&&!active&&session.prepared==null);
        boolean selected=ready&&session.target!=null&&!busy;
        query.setEnabled(selected);modules.setEnabled(selected);selected&=!session.uncertain;observe.setEnabled(selected&&!active&&session.prepared==null&&!state.equals("PREPARED"));
        prepare.setEnabled(selected&&session.observed!=null&&session.prepared==null&&!active&&!state.equals("PREPARED"));
        activate.setEnabled(selected&&session.prepared!=null&&source.get().equals(session.preparedSource));
        disable.setEnabled(selected&&state.equals("ACTIVE"));discard.setEnabled(selected&&!active);params.setEnabled(selected&&state.equals("ACTIVE"));
    }
    private void chooseProcess(){
        loading=true;render();String lease=app.engine.lease;
        app.engine.execute(ControlWire.request("hookProcesses","project",project),(value,error)->{
            loading=false;if(destroyed)return;if(error!=null){show(error);return;}if(!lease.equals(app.engine.lease)){show(new IllegalStateException("连接已改变，请重新选择"));return;}
            try{JSONArray choices=new JSONArray(value.getString("processes"));if(choices.length()==0){session.clearEvidence();session.target=null;session.message="应用尚未运行。请先从本项目启动空间应用。";render();return;}
                String[] labels=new String[choices.length()];for(int i=0;i<labels.length;i++){JSONObject item=choices.getJSONObject(i);labels[i]=item.getString("processName")+" · PID "+item.getInt("pid");}
                new AlertDialog.Builder(page).setTitle("选择实际运行的进程").setItems(labels,(dialog,index)->{try{session.select(value,choices.getJSONObject(index),lease);session.message="已选择，正在查询…";perform("status",JSONObject::new,"查询 Hook 状态");}catch(Exception invalid){show(invalid);}}).show();
            }catch(Exception invalid){show(invalid);}
        });
    }
    private void configure(){try{
        LinearLayout form=ProductUi.column(page);int padding=ProductUi.dp(page,18);form.setPadding(padding,0,padding,padding);
        ProductUi.add(form,ProductUi.text(page,"Java 填写类名、方法名与参数类型；Native 需填写模块、Build ID 和已确认的 C ABI。不会从符号名猜测签名。",13,ProductUi.MUTED,false),8);
        EditText input=ProductUi.field(form,"目标声明 JSON","[]",app.projects.selectors(project),true);input.setMinLines(7);input.setTypeface(android.graphics.Typeface.MONOSPACE);input.setContentDescription("Hook 目标声明");
        AlertDialog dialog=new AlertDialog.Builder(page).setTitle("配置脚本目标").setView(form).setNegativeButton("返回",null).setPositiveButton("保存声明",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{try{app.projects.selectors(project,input.getText().toString());session.observed=null;session.message="声明已保存，需要重新观察。";render();dialog.dismiss();}catch(Exception error){input.setError(UiText.error(error));}}));dialog.show();
    }catch(Exception error){show(error);}}
    private void prepare(){perform("prepare",()->{
        String current=source.get();if(!current.equals(app.projects.script(project)))throw new IllegalStateException("请先保存当前脚本，再进行检查");
        session.preparedSource=current;return new JSONObject().put("evidenceId",session.observed.getJSONObject("evidence").getString("id")).put("source",current);
    },"检查脚本");}
    private void activate(){
        new AlertDialog.Builder(page).setTitle("激活到当前进程").setMessage(session.name+" · PID "+session.pid+"\n\n仅部署本次已检查的源码。应用重启后需要重新观察与确认。").setNegativeButton("返回",null).setPositiveButton("确认激活",(dialog,index)->perform("activate",()->{
            if(session.prepared==null||!source.get().equals(session.preparedSource))throw new IllegalStateException("源码已变化，请重新检查");return new JSONObject().put("token",session.prepared.getString("token")).put("sourceSha256",session.prepared.getString("sourceSha256"));
        },"激活脚本")).show();
    }
    private void parameters(){try{
        String revision=session.status.getString("revision");int version=session.status.getInt("parameterVersion");
        LinearLayout form=ProductUi.column(page);EditText input=ProductUi.field(form,"参数 JSON · 版本 "+version,"{}",session.status.getJSONObject("parameters").toString(2),true);
        new AlertDialog.Builder(page).setTitle("更新参数").setView(form).setNegativeButton("返回",null).setPositiveButton("确认更新",(dialog,index)->perform("updateParameters",()->new JSONObject().put("revision",revision).put("parameterVersion",version).put("parameters",new JSONObject(input.getText().toString())),"更新参数")).show();
    }catch(Exception error){show(error);}}
    private interface Fields {JSONObject get()throws Exception;}
    private void perform(String command,Fields fields,String label){try{
        if(session.target==null||!session.lease.equals(app.engine.lease))throw new IllegalStateException("请重新选择运行进程");
        JSONObject raw=fields.get().put("command",command).put("processGeneration",session.process);String process=session.process,lease=session.lease;
        Bundle input=ControlWire.request("hook","operation",UUID.randomUUID().toString(),"project",project,"bindingOperation",session.target.getString("bindingOperation"),"generation",session.target.getString("generation"),"processGeneration",process,"request",raw.toString());
        boolean previouslyUncertain=session.uncertain;session.uncertain=true;
        app.operations.submit(input,label,null,(reply,error)->{
            if(!process.equals(session.process)||!lease.equals(app.engine.lease))return;
            if(error!=null){render();return;}
            try{JSONObject result=new JSONObject(reply.getString("result")).getJSONObject("result");session.uncertain=command.equals("modules")?previouslyUncertain:false;
                if(command.equals("observe")){session.observed=result;session.prepared=null;session.status=new JSONObject().put("state","OBSERVED");session.message="已观察 "+result.getJSONObject("evidence").getJSONArray("symbols").length()+" 个真实目标。";}
                else if(command.equals("modules")){session.message="已读取实际模块。";if(!destroyed)ProductUi.notice(page,"客体 Native 模块",result.getJSONArray("modules").toString(2));}
                else{session.status=result;session.message=command.equals("prepare")?"检查通过，尚未激活。":command.equals("activate")?"该进程已确认激活，业务效果仍需实际操作验证。":"已取得引擎回执。";
                    if(command.equals("prepare"))session.prepared=result;else if(command.equals("activate")||command.equals("disable")||command.equals("discard"))session.prepared=null;
                    if(command.equals("status")&&!result.optString("state").equals("PREPARED"))session.prepared=null;
                    if(result.optString("state").equals("IDLE")||result.optString("state").equals("DISABLED")){session.observed=null;session.prepared=null;}
                }
                render();
            }catch(Exception invalid){show(invalid);}
        });
    }catch(Exception error){show(error);}}
    private void show(Exception error){session.message=UiText.error(error);render();}
}
