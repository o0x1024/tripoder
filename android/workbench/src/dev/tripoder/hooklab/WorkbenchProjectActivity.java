package dev.tripoder.hooklab;

import android.app.*;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.*;
import android.widget.*;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.util.*;

/** Local source editing and explicit generation binding. Activation needs a worker ACK. */
public final class WorkbenchProjectActivity extends Activity implements EngineConnection.Listener {
    private WorkbenchApplication app;private String id,savedSource="",lease="";private EditText editor;
    private TextView binding,status;private Button bind,save,launch,query;private WorkbenchCatalog catalog;
    private WorkbenchHookPanel hooks;private boolean destroyed,loading,wasPending;private final Runnable operationObserver=this::operationChanged;
    private WorkbenchAgentPanel agents;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);app=(WorkbenchApplication)getApplication();
        try{
            id=WorkbenchProjects.id(getIntent().getStringExtra("project"));JSONObject project=app.projects.get(id);savedSource=app.projects.script(id);
            LinearLayout page=ProductUi.screen(this,project.getString("name"),"脚本项目 · main.ts");
            LinearLayout target=ProductUi.card(this);ProductUi.add(target,ProductUi.label(this,"运行目标"),0);binding=ProductUi.text(this,"正在查询绑定…",14,ProductUi.INK,true);ProductUi.add(target,binding,12);
            bind=ProductUi.button(this,"选择运行目标",false,this::chooseTarget);ProductUi.add(target,bind,14);launch=ProductUi.button(this,"启动空间应用",true,this::launch);ProductUi.add(target,launch,10);ProductUi.add(page,target,16);
            ProductUi.add(target,ProductUi.button(this,"处理未确认启动",false,()->WorkbenchEntryClosure.show(this,app,id)),10);
            ProductUi.add(target,ProductUi.button(this,"核验并结束未知启动等待",false,()->new AlertDialog.Builder(this).setTitle("保留未知结果并结束等待？").setMessage("引擎会核验原入口已关闭或退休、同一目标没有其他开放入口与运行进程。核验通过后，只结束本地等待，不重试原操作，也不改写结果。").setNegativeButton("返回",null).setPositiveButton("核验并保存回执",(d,w)->app.operations.acknowledgeUnknown(id)).show()),10);
            editor=ProductUi.field(page,"项目脚本","输入脚本",saved==null?savedSource:saved.getString("source",savedSource),true);editor.setTypeface(Typeface.MONOSPACE);editor.setMinLines(12);editor.setHorizontallyScrolling(true);editor.setContentDescription("项目脚本编辑器");
            save=ProductUi.button(this,"保存脚本",true,this::save);ProductUi.add(page,save,14);status=ProductUi.text(this,"",13,ProductUi.MUTED,false);status.setTextIsSelectable(true);ProductUi.add(page,status,12);query=ProductUi.button(this,"查询启动或绑定操作",false,()->app.operations.query());ProductUi.add(page,query,10);
            editor.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){updateSave();}public void afterTextChanged(Editable s){}});updateSave();
            hooks=new WorkbenchHookPanel(this,app,id,()->editor.getText().toString(),page);
            agents=new WorkbenchAgentPanel(this,app,id,page);
            app.operations.observe(operationObserver);app.engine.observe(this);operationChanged();
        }catch(Exception error){ProductUi.notice(this,"无法打开项目",error.getMessage());}
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);if(editor!=null)out.putString("source",editor.getText().toString());}
    @Override protected void onResume(){super.onResume();if(binding!=null&&app!=null&&app.engine.ready()){app.operations.query();refresh();}}
    @Override public void changed(){if(destroyed||binding==null)return;updateActions();if(!app.engine.ready()){catalog=null;lease="";binding.setText("引擎未连接 · 尚不能确认绑定");bind.setText("连接引擎");return;}if(!lease.equals(app.engine.lease)){lease=app.engine.lease;refresh();}}
    private void updateActions(){boolean busy=app.operations.pending()||app.imports.preparing;bind.setEnabled(!busy);launch.setEnabled(!busy&&app.engine.ready()&&catalog!=null&&catalog.bindings.containsKey(id)&&"COMMITTED".equals(catalog.bindings.get(id).optString("state")));query.setVisibility(app.operations.pending()?android.view.View.VISIBLE:android.view.View.GONE);}
    private void operationChanged(){if(destroyed||status==null)return;status.setText(app.operations.message+(app.operations.pending()?"\n操作 "+app.operations.pendingId():""));updateActions();boolean pending=app.operations.pending(),completed=wasPending&&!pending;wasPending=pending;if(completed)refresh();}
    private void refresh(){
        if(loading||!app.engine.ready())return;loading=true;String current=app.engine.lease;
        app.engine.execute(ControlWire.request("catalog"),(value,error)->{loading=false;if(destroyed||!current.equals(app.engine.lease))return;if(error!=null){show(error);return;}try{
            catalog=new WorkbenchCatalog(value);JSONObject ref=catalog.bindings.get(id);bind.setText(ref==null?"选择运行目标":"重新绑定目标");updateActions();
            if(ref==null){binding.setText("尚未绑定应用");return;}if(!"COMMITTED".equals(ref.getString("state"))){binding.setText(ref.getString("package")+"\n目标已变更，旧绑定已失效。");return;}
            binding.setText(ref.getString("package")+"\n正在验证安装代次…");app.engine.execute(ControlWire.request("resolveProject","project",id),(target,failure)->{if(destroyed||!current.equals(app.engine.lease))return;if(failure!=null){binding.setText("绑定不可用："+failure.getMessage());return;}binding.setText(target.getString("package")+"\n空间："+target.getString("space")+"\n安装代次："+target.getString("generation")+"\n已验证绑定 · 脚本状态见下方部署面板");});
        }catch(Exception invalid){show(invalid);}});
    }
    private void chooseTarget(){
        if(!app.engine.ready()){app.engine.connect(this);return;}if(catalog==null){refresh();return;}var choices=catalog.committed();if(choices.isEmpty()){ProductUi.notice(this,"还没有可绑定的应用","请返回工作台，创建空间并导入原始 APK。");return;}
        String[] labels=choices.stream().map(WorkbenchCatalog::label).toArray(String[]::new);new AlertDialog.Builder(this).setTitle("选择已安装的应用").setItems(labels,(dialog,index)->{
            JSONObject install=choices.get(index),prior=catalog.bindings.get(id);new AlertDialog.Builder(this).setTitle("确认运行目标").setMessage(WorkbenchCatalog.label(install)+"\n\n本次绑定固定当前安装代次。应用升级后，需要再次确认；保存的脚本不会自动部署。").setNegativeButton("返回",null).setPositiveButton("确认绑定",(d,w)->{
                try{Bundle input=ControlWire.request("bindProject","operation",UUID.randomUUID().toString(),"project",id,"space",install.getString("space"),"package",install.getString("package"),"generation",install.getString("generation"));if(prior!=null)input.putString("expectedBinding",prior.getString("bindingOperation"));app.operations.submit(input,"绑定运行目标",null);}catch(Exception error){show(error);}
            }).show();
        }).show();
    }
    private void launch(){
        launch.setEnabled(false);app.engine.execute(ControlWire.request("launchTargets","project",id),(target,error)->{
            if(destroyed)return;updateActions();if(error!=null){show(error);return;}try{
                JSONArray array=new JSONArray(target.getString("targets"));List<JSONObject> choices=new ArrayList<>();for(int i=0;i<array.length();i++)choices.add(array.getJSONObject(i));choices.sort(Comparator.comparing(value->!value.optBoolean("launcher")));
                if(choices.isEmpty()){ProductUi.notice(this,"没有可启动的组件","当前安装没有启用且允许外部启动的 Activity。");return;}
                String[] labels=choices.stream().map(value->(value.optBoolean("launcher")?"应用入口 · ":"")+value.optString("component")+(value.optBoolean("supported")?"":"\n尚不支持："+value.optString("reason"))).toArray(String[]::new);
                new AlertDialog.Builder(this).setTitle("选择启动组件").setItems(labels,(dialog,index)->{
                    JSONObject component=choices.get(index);if(!component.optBoolean("supported")){ProductUi.notice(this,"该组件尚不能启动",component.optString("reason"));return;}
                    try{Bundle input=ControlWire.request("prepareLaunch","operation",UUID.randomUUID().toString(),"project",id,"bindingOperation",target.getString("bindingOperation"),"generation",target.getString("generation"),"component",component.getString("component"));
                        app.operations.submit(input,"启动空间应用",null,(prepared,failure)->{
                            if(destroyed||isFinishing()||!hasWindowFocus()){app.operations.inform("启动已准备，但项目页面不在前台；未提交组件启动。请稍后查询原操作。");return;}
                            try{Intent gateway=new Intent().setComponent(new android.content.ComponentName(EngineConnection.ENGINE,"dev.tripoder.runtime.RuntimeGuestGateway")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK).putExtra("operation",prepared.getString("operation")).putExtra("capability",prepared.getString("capability"));startActivity(gateway,ActivityOptions.makeBasic().setShareIdentityEnabled(true).toBundle());}
                            catch(Exception rejected){app.operations.inform("启动入口未确认："+rejected.getMessage());}
                        });
                    }catch(Exception rejected){show(rejected);}
                }).show();
            }catch(Exception invalid){show(invalid);}
        });
    }
    private void updateSave(){if(hooks!=null)hooks.sourceChanged();if(save!=null){boolean dirty=!editor.getText().toString().equals(savedSource);save.setText(dirty?"保存脚本 · 有未保存修改":"脚本已保存");save.setEnabled(dirty);}}
    private void save(){try{String source=editor.getText().toString();app.projects.script(id,source);savedSource=source;updateSave();status.setText("脚本已保存到此项目。");}catch(Exception error){show(error);}}
    private void show(Exception error){if(status!=null)status.setText(error.getClass().getSimpleName()+": "+error.getMessage());}
    @Override public void finish(){if(editor!=null&&!editor.getText().toString().equals(savedSource)){new AlertDialog.Builder(this).setTitle("脚本还有未保存的修改").setNegativeButton("继续编辑",null).setNeutralButton("放弃修改",(d,w)->super.finish()).setPositiveButton("保存并返回",(d,w)->{save();if(editor.getText().toString().equals(savedSource))super.finish();}).show();return;}super.finish();}
    @Override protected void onDestroy(){destroyed=true;if(hooks!=null)hooks.dispose();if(agents!=null)agents.dispose();if(app!=null){app.engine.remove(this);app.operations.remove(operationObserver);}super.onDestroy();}
}
