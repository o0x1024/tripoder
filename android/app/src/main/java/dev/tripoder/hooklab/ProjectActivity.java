package dev.tripoder.hooklab;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;

/** Project workspace. The assistant generates checked drafts; deployment remains explicit. */
public final class ProjectActivity extends Activity {
    private ProjectStore store;private String projectId;private JSONObject project,app;
    private LinearLayout body,tabs;private EditText prompt;private TextView status;
    private int selected;private boolean busy,destroyed;
    private GuestClient client;
    private CompatibilityClient compatibility;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private String operation="";
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);store=new ProjectStore(this);projectId=ProjectStore.id(this);
        try{store.initialize();project=store.get(projectId);app=project.getJSONObject("app");}catch(Exception error){Toast.makeText(this,"项目无法打开",Toast.LENGTH_LONG).show();finish();return;}
        selected=saved==null?0:saved.getInt("tab",0);
        LinearLayout page=ProductUi.screen(this,project.optString("name"),app.optString("name")+" · "+app.optString("versionName"));
        LinearLayout overview=ProductUi.card(this);ProductUi.add(overview,ProductUi.text(this,store.runnable(projectId)?"应用已就绪":"等待兼容性适配",16,store.runnable(projectId)?ProductUi.GREEN:ProductUi.INK,true),0);
        ProductUi.add(overview,ProductUi.text(this,store.runnable(projectId)?"此项目拥有独立运行数据。切换项目会重启客体进程。":GameRuntime.PACKAGE.equals(app.optString("packageName"))?"已接入无 Hook 启动，可显示游戏登录页。目前仍有进程退出及外部授权回调问题，尚未通过正常运行验收。":"可以保存需求和脚本，检查应用结构；当前尚不能在容器中启动。",12,ProductUi.MUTED,false),8);
        ProductUi.add(overview,ProductUi.button(this,store.runnable(projectId)?"运行应用":"检查兼容性",true,()->{if(store.runnable(projectId))launch(null);else inspect();}),14);ProductUi.add(page,overview,16);
        if(GameRuntime.PACKAGE.equals(app.optString("packageName")))ProductUi.add(overview,ProductUi.button(this,"尝试启动游戏 · 无 Hook",false,()->startActivity(ProjectStore.intent(this,GameLaunchActivity.class,projectId))),12);
        tabs=ProductUi.row(this);ProductUi.add(page,tabs,18);body=ProductUi.column(this);ProductUi.add(page,body,4);render();page.setFocusableInTouchMode(true);page.requestFocus();
    }
    private void savePrompt(){if(prompt!=null)try{store.write(projectId,"ai/prompt.txt",prompt.getText().toString());}catch(Exception error){Toast.makeText(this,"需求保存失败",Toast.LENGTH_SHORT).show();}}
    private void render(){
        body.removeAllViews();tabs.removeAllViews();prompt=null;String[] titles={"Agent","脚本","测试","文件"};
        for(int i=0;i<4;i++){int tab=i;Button button=ProductUi.button(this,titles[i],selected==i,()->{savePrompt();selected=tab;render();});button.setTextSize(12);button.setContentDescription("项目"+titles[i]+"标签");LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i<3)lp.rightMargin=ProductUi.dp(this,5);tabs.addView(button,lp);}
        status=ProductUi.text(this,operation,12,ProductUi.MUTED,false);if(!operation.isEmpty())ProductUi.add(body,status,12);
        try{if(selected==0)agent();else if(selected==1)scripts();else if(selected==2)tests();else files();}catch(Exception error){ProductUi.add(body,ProductUi.text(this,"读取失败："+error.getMessage(),13,ProductUi.MUTED,false),12);}
    }
    private void agent() throws Exception {
        LinearLayout intro=ProductUi.card(this);ProductUi.add(intro,ProductUi.text(this,"描述你想改变的行为",19,ProductUi.INK,true),0);
        ProductUi.add(intro,ProductUi.text(this,"当前助手支持生成与检查 Hook 草稿。应用方案后，可到测试页验证；自动定位和自主界面测试仍待接入。",12,ProductUi.MUTED,false),10);
        prompt=ProductUi.field(intro,"需求","例如：把问候文字固定为测试通过",store.read(projectId,"ai/prompt.txt",""),true);prompt.setMaxLines(5);
        Button send=ProductUi.button(this,busy?"正在处理…":"发送并生成草稿",true,this::generate);send.setEnabled(!busy);ProductUi.add(intro,send,14);ProductUi.add(body,intro,14);
        JSONArray messages=new JSONArray(store.read(projectId,"ai/conversation.json","[]"));
        if(messages.length()==0)ProductUi.add(body,ProductUi.text(this,"对话和生成结果只保存在此项目中。",12,ProductUi.MUTED,false),18);
        for(int i=messages.length()-1;i>=Math.max(0,messages.length()-6);i--){JSONObject message=messages.getJSONObject(i);LinearLayout bubble=ProductUi.card(this);ProductUi.add(bubble,ProductUi.text(this,message.optString("role").equals("user")?"你":message.optString("role").equals("assistant")?"Agent":"运行器",12,ProductUi.ACCENT,true),0);ProductUi.add(bubble,ProductUi.text(this,message.optString("text"),14,ProductUi.INK,false),8);ProductUi.add(body,bubble,12);}
    }
    private void scripts(){
        LinearLayout card=ProductUi.card(this);card.addView(ProductUi.entry(this,"code","main.ts","当前项目的 Hook 草稿",()->{
            if(store.runnable(projectId))startActivity(ProjectStore.intent(this,ScriptActivity.class,projectId).putExtra("showCode",true));else openFile("scripts/main.ts");
        }));ProductUi.add(body,card,14);
        ProductUi.add(body,ProductUi.text(this,"编辑与保存不会自动执行。运行方案前需通过目标和类型检查。",12,ProductUi.MUTED,false),14);
        if(store.runnable(projectId)){LinearLayout config=ProductUi.card(this);config.addView(ProductUi.entry(this,"shield","环境配置","保存到 config/environment.json",()->startActivity(ProjectStore.intent(this,GuestToolsActivity.class,projectId).putExtra("environment",true))));ProductUi.add(body,config,14);}
    }
    private void tests() throws Exception {
        LinearLayout card=ProductUi.card(this);ProductUi.add(card,ProductUi.text(this,store.runnable(projectId)?"验证当前运行方案":"兼容性检查",18,ProductUi.INK,true),0);
        if(store.runnable(projectId)){
            JSONObject expected=new JSONObject(store.read(projectId,"tests/expectations.json","{}"));EditText java=ProductUi.field(card,"预期 Java 返回值","留空只记录实际值",expected.optString("java"),false);EditText nativeValue=ProductUi.field(card,"预期 Native 返回值","留空只记录实际值",expected.optString("native"),false);
            Button run=ProductUi.button(this,"执行验证并保存结果",true,()->verify(java.getText().toString(),nativeValue.getText().toString()));run.setEnabled(!busy);ProductUi.add(card,run,14);
            ProductUi.add(card,ProductUi.text(this,"调用真实目标并比较预期值。未填写断言时只记录观察结果，不判定通过。",12,ProductUi.MUTED,false),12);
        }else{ProductUi.add(card,ProductUi.text(this,"检查安装包、入口类和资源加载，不执行目标初始化或启动原应用。",13,ProductUi.MUTED,false),12);Button inspect=ProductUi.button(this,"运行兼容性检查",true,this::inspect);inspect.setEnabled(!busy);ProductUi.add(card,inspect,14);String report=store.read(projectId,"compatibility/latest.json","");if(!report.isEmpty()){ProductUi.add(card,ProductUi.text(this,CompatibilityProbe.summary(new JSONObject(report)),12,ProductUi.MUTED,false),14);ProductUi.add(card,ProductUi.button(this,"查看完整报告",false,()->openFile("compatibility/latest.json")),14);}}
        ProductUi.add(body,card,14);
        if(store.runnable(projectId)){LinearLayout actions=ProductUi.card(this);actions.addView(ProductUi.entry(this,"play","交互自动化","配置点击与输入步骤",()->launch("automation")));ProductUi.divider(actions);actions.addView(ProductUi.entry(this,"gamepad","手柄映射","配置当前项目的按键映射",()->launch("gamepad")));ProductUi.add(body,actions,14);}
        int count=0;java.util.List<String> files=store.files(projectId);java.util.Collections.reverse(files);for(String file:files)if(file.startsWith("runs/")&&count++<5){LinearLayout entry=ProductUi.card(this);entry.addView(ProductUi.entry(this,"file",file.substring(5),"运行记录 · 点击查看",()->openFile(file)));ProductUi.add(body,entry,12);}
    }
    private void files(){LinearLayout card=ProductUi.card(this);card.addView(ProductUi.entry(this,"file","浏览项目文件","scripts · tests · config · ai · runs",()->openFile("project.json")));if(store.runnable(projectId)){ProductUi.divider(card);card.addView(ProductUi.entry(this,"file","应用运行数据","当前项目 runtime 目录",()->startActivity(ProjectStore.intent(this,GuestToolsActivity.class,projectId))));}ProductUi.add(body,card,14);}
    private void openFile(String path){startActivity(ProjectStore.intent(this,ProjectFilesActivity.class,projectId).putExtra("path",path));}
    private void setOperation(String message){operation=message;if(!destroyed)render();}
    private void connected(java.util.function.Consumer<JSONObject> callback){if(client!=null)client.close();client=new GuestClient(this,projectId);client.connect(()->client.send(GuestService.EDITOR,new Bundle(),result->{if(!destroyed)callback.accept(result);}));}
    private void launch(String tool){if(busy)return;busy=true;if(client!=null)client.close();client=ProjectLauncher.launch(this,projectId,tool,message->{busy=message.equals("正在准备项目…");setOperation(message);});}
    private void generate(){
        if(busy)return;String task=prompt.getText().toString().trim();if(task.isEmpty()){prompt.setError("请描述需求");return;}if(task.length()>4000){prompt.setError("需求最多 4000 字符");return;}savePrompt();
        try{store.message(projectId,"user",task);if(!store.runnable(projectId)){store.message(projectId,"system","此应用尚未接入运行器，当前不能观察目标方法或部署 Hook。需求已保存，可先到测试页检查兼容性。");render();return;}}catch(Exception error){setOperation(error.getMessage());return;}
        busy=true;setOperation("正在连接当前项目…");connected(result->{if(result.has("error")){completeError(result.optString("error"));return;}
            setOperation("正在生成草稿…");io.execute(()->{try{
                android.content.SharedPreferences prefs=getSharedPreferences("ai-config",0);String endpoint=prefs.getString("endpoint","https://api.deepseek.com/chat/completions"),model=prefs.getString("model","deepseek-flash"),key=new ApiKeyVault(this).load(endpoint);
                if(key.isEmpty())throw new IllegalStateException("请先在设置 → AI 服务中保存 API Key");
                String original=store.read(projectId,"scripts/main.ts","");String template=original.isEmpty()?result.getString("template"):original;
                String draft=AiClient.generate(endpoint,model,key,task,result.getJSONObject("evidence"),template);
                if(!store.read(projectId,"scripts/main.ts","").equals(original)){String path="ai/draft-"+System.currentTimeMillis()+".ts";store.write(projectId,path,draft);throw new IllegalStateException("生成期间脚本已被编辑。新草稿另存于 "+path+"，原脚本已保留");}
                store.write(projectId,"scripts/main.ts",draft);
                main.post(()->{if(destroyed)return;Bundle data=new Bundle();data.putString("source",draft);data.putString("session",result.optJSONObject("evidence").optString("id"));client.send(GuestService.CHECK_SCRIPT,data,checked->{
                    try{boolean valid=!checked.has("error");store.message(projectId,"assistant",valid?"草稿已保存到 scripts/main.ts，语法与目标签名检查通过。可到脚本页查看并应用；尚未部署或进行界面测试。":"草稿已保存，但检查未通过："+checked.optString("error"));store.event(projectId,"draft-check",checked);busy=false;setOperation(valid?"草稿检查通过 · 等待应用":"草稿需要修改");}catch(Exception error){completeError("结果保存失败");}
                });});
            }catch(Exception error){main.post(()->completeError(error.getMessage()));}});
        });
    }
    private void completeError(String error){if(destroyed)return;busy=false;try{store.message(projectId,"system",error==null?"操作失败":error);}catch(Exception ignored){}setOperation("操作未完成："+error);}
    private void verify(String javaExpected,String nativeExpected){
        if(busy)return;try{if(!nativeExpected.isEmpty())Integer.parseInt(nativeExpected);store.write(projectId,"tests/expectations.json",new JSONObject().put("java",javaExpected).put("native",nativeExpected).toString(2));}catch(Exception error){ProductUi.notice(this,"预期值无效",error.getMessage());return;}
        busy=true;setOperation("正在验证当前运行方案…");connected(editor->{if(editor.has("error")){completeError(editor.optString("error"));return;}client.send(GuestService.SAMPLE,new Bundle(),sample->{try{
            if(sample.has("error")){completeError(sample.optString("error"));return;}boolean hasAssertions=!javaExpected.isEmpty()||!nativeExpected.isEmpty();boolean pass=(javaExpected.isEmpty()||javaExpected.equals(sample.optString("java")))&&(nativeExpected.isEmpty()||Integer.parseInt(nativeExpected)==sample.optInt("native"));
            JSONObject record=new JSONObject().put("sample",sample).put("assertionsProvided",hasAssertions).put("outcome",!hasAssertions?"observed":pass?"passed":"failed").put("expectedJava",javaExpected).put("expectedNative",nativeExpected);store.event(projectId,"verification",record);busy=false;setOperation((!hasAssertions?"已记录，未设置断言":pass?"验证通过":"验证未通过")+"\nJava → "+sample.optString("java")+"\nNative → "+sample.optInt("native"));
        }catch(Exception error){completeError(error.getMessage());}});});
    }
    private void inspect(){
        if(busy)return;savePrompt();busy=true;setOperation("正在独立进程中检查安装包、入口类和资源…");
        compatibility=new CompatibilityClient(this,app.optString("packageName"),report->{
            if(destroyed)return;
            try{if(report.has("error")){store.event(projectId,"compatibility-error",report);completeError(report.optString("error"));return;}
                store.write(projectId,"compatibility/latest.json",report.toString());store.event(projectId,"compatibility",new JSONObject().put("compatible",false).put("report","compatibility/latest.json"));busy=false;selected=2;setOperation("加载预检完成，运行兼容性尚未通过");
            }catch(Exception error){completeError(error.getMessage());}
        });compatibility.start();
    }
    @Override protected void onResume(){super.onResume();if(body!=null&&!busy)render();}
    @Override protected void onPause(){savePrompt();super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("tab",selected);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){destroyed=true;if(client!=null)client.close();if(compatibility!=null)compatibility.close();main.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy();}
}
