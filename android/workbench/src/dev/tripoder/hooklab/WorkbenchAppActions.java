package dev.tripoder.hooklab;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import dev.tripoder.control.ControlWire;
import java.util.*;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Tap launches through the bound project (asking only when several exist). Long press manages the application. */
final class WorkbenchAppActions {
    private final Activity page;private final WorkbenchApplication app;private final SharedPreferences choices;
    WorkbenchAppActions(Activity page,WorkbenchApplication app){this.page=page;this.app=app;choices=page.getSharedPreferences("application-launch",Context.MODE_PRIVATE);}
    /** Douyin's sign-in eligibility is decided server-side from the official-channel attribution of the runtime
     *  environment, which a container cannot produce (docs/douyin-risk-control-strategy-analysis-20261112.md §4).
     *  Say so at the entry instead of letting the tap burn a launch on a login that is guaranteed to be blocked. */
    private static final String RISK_CONTROL_UNSUPPORTED="com.ss.android.ugc.aweme";
    private static final String RISK_CONTROL_DETAIL="抖音在服务端按运行环境的官方渠道归属判定登录资格，"
        +"容器产生不了这条记录：一键登录与验证码登录都会被拦下。拦截弹窗和「立即升级」由抖音自己渲染，我们无法修改或移除。"
        +"已经登录过的账号可以继续浏览；需要登录时请回到手机系统里用官方抖音。";
    private boolean riskControlNoticeShown;
    /** Tap: the space comes first (the space the library is filtered to, else the remembered default, else the only one, else asked).
     *  Then one project bound in that space starts directly, several ask which project, none keeps the plain installation launch. */
    void launch(WorkbenchCatalog catalog,String pkg,String filter){
        if(!ready())return;
        if(RISK_CONTROL_UNSUPPORTED.equals(pkg)&&!riskControlNoticeShown){riskControlNoticeShown=true;
            ProductUi.confirm(page,"容器环境不支持抖音平台风控校验",RISK_CONTROL_DETAIL,"仍要进入",false,
                ()->launchNow(catalog,pkg,filter));
            return;
        }
        launchNow(catalog,pkg,filter);
    }
    private void launchNow(WorkbenchCatalog catalog,String pkg,String filter){
        List<JSONObject> installs=catalog.installations(pkg);String remembered=choices.getString(pkg,"");
        for(JSONObject install:installs)if(install.optString("space").equals(filter)){launchIn(catalog,install);return;}
        for(JSONObject install:installs)if(remembered.equals(install.optString("space"))){launchIn(catalog,install);return;}
        choose(installs,"在哪个空间运行？",install->{if(remember(pkg,install))launchIn(catalog,install);});
    }
    /** Only COMMITTED bindings to this installation count; a stale binding (its installation was removed or updated) never takes over the tap. */
    private void launchIn(WorkbenchCatalog catalog,JSONObject install){
        // Launch the requested installation; project ownership never adds a second choice.
        launch(install,catalog);
    }
    private void launchProject(JSONObject project){
        if(!ready())return;String id=project.optString("id");
        app.engine.execute(ControlWire.request("launchTargets","project",id),(target,error)->{
            if(page.isDestroyed()||page.isFinishing())return;
            if(error!=null){launchFailed(page,app,id,error);return;}
            try{
                JSONArray array=new JSONArray(target.getString("targets"));List<JSONObject> launchers=new ArrayList<>(),all=new ArrayList<>();
                for(int i=0;i<array.length();i++){JSONObject value=array.getJSONObject(i);all.add(value);if(value.optBoolean("launcher")&&value.optBoolean("supported"))launchers.add(value);}
                // A single supported application entry needs no further question; anything ambiguous is asked, never guessed.
                if(launchers.size()==1){startProject(id,target,launchers.get(0));return;}
                List<JSONObject> choices=all.stream().filter(value->value.optBoolean("supported")).collect(java.util.stream.Collectors.toList());
                if(choices.isEmpty()){ProductUi.notice(page,"没有可以打开的界面","这个应用没有允许直接打开的界面。");return;}
                String[] labels=choices.stream().map(WorkbenchAppActions::componentLabel).toArray(String[]::new);
                new AlertDialog.Builder(page).setTitle("打开哪个界面？").setItems(labels,(dialog,index)->startProject(id,target,choices.get(index))).setNegativeButton("取消",null).show();
            }catch(Exception invalid){launchFailed(page,app,id,invalid);}
        });
    }
    static String componentLabel(JSONObject value){return (value.optBoolean("launcher")?"应用入口 · ":"")+value.optString("component");}
    /** A dead earlier launch blocks new ones; the way out is offered where the failure is seen. */
    static void launchFailed(Activity page,WorkbenchApplication app,String project,Exception error){
        if(page.isDestroyed()||page.isFinishing())return;
        String message=error.getMessage()==null?"":error.getMessage();
        if(project!=null&&message.equals("LAUNCH_TARGET_REJECTED"))new AlertDialog.Builder(page).setTitle("应用没有启动").setMessage(UiText.error(error)+"。关闭旧入口不会影响应用数据。").setNegativeButton("取消",null).setPositiveButton("处理旧入口",(d,w)->WorkbenchEntryClosure.show(page,app,project)).show();
        else ProductUi.notice(page,"应用没有启动",UiText.error(error));
    }
    private void startProject(String id,Bundle target,JSONObject component){
        if(!ready())return;
        try{
            Bundle input=ControlWire.request("prepareLaunch","operation",UUID.randomUUID().toString(),"project",id,"bindingOperation",target.getString("bindingOperation"),"generation",target.getString("generation"),"component",component.getString("component"));
            app.operations.submit(input,"启动应用",null,(prepared,failure)->{
                if(failure!=null){launchFailed(page,app,id,failure);return;}
                if(prepared.getBoolean("woken",false))return;
                enter(page,app,prepared,!page.isDestroyed()&&!page.isFinishing()&&visible(),"应用已准备好，但 Tripoder 不在前台，没有打开界面。请回到 Tripoder 后重新启动。");
            });
        }catch(Exception rejected){launchFailed(page,app,id,rejected);}
    }
    /** Starts the guest root the engine prepared. An entry that is not started is handed back, so it never holds the application open. */
    static void enter(Activity page,WorkbenchApplication app,Bundle prepared,boolean canShow,String hidden){
        String operation=prepared.getString("operation");Intent entry=prepared.getParcelable("intent",Intent.class);
        if(canShow&&entry!=null){try{page.startActivity(entry);return;}catch(Exception rejected){app.operations.inform("应用界面没有打开："+UiText.error(rejected));}}
        else app.operations.inform(hidden);
        app.engine.execute(ControlWire.request("abandonLaunch","operation",operation),(value,error)->{
            if(error!=null)app.operations.inform("未打开的启动入口没有收回："+UiText.error(error));
            app.operations.query();
        });
    }
    /** Window focus can be held briefly by system overlays while this page is still on screen; what matters is that this app is visible. */
    static boolean visible(){
        ActivityManager.RunningAppProcessInfo state=new ActivityManager.RunningAppProcessInfo();ActivityManager.getMyMemoryState(state);
        return state.importance<=ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
    }
    void menu(WorkbenchCatalog catalog,String pkg,String name,String filter,Runnable chooseMultiple){
        List<String> items=List.of("应用分析","选择默认空间","强行停止","从空间移除","选择多个","删除应用");
        new AlertDialog.Builder(page).setTitle(name).setItems(items.toArray(String[]::new),(dialog,index)->{
            String choice=items.get(index);
            switch(choice){
                case "应用分析":{String id=catalog.project(pkg);if(id!=null)page.startActivity(new Intent(page,WorkbenchProjectActivity.class).putExtra("project",id));break;}
                case "选择默认空间":choose(catalog.installations(pkg),"轻点图标时使用的空间",install->{if(remember(pkg,install))app.operations.inform("「"+name+"」将在「"+app.spaces.name(install.optString("space"))+"」中运行");});break;
                case "强行停止":stopEverywhere(catalog,pkg,name);break;
                case "从空间移除":choose(catalog.installations(pkg),"从哪个空间移除？",install->{int bound=boundProjects(catalog,pkg,install.optString("space"));ProductUi.confirm(page,"从「"+app.spaces.name(install.optString("space"))+"」移除「"+name+"」？","这个空间中该应用的全部数据会被删除，无法恢复。"+"应用脚本会保留。","移除",true,()->uninstall(install,name));});break;
                case "选择多个":dialog.dismiss();chooseMultiple.run();break;
                case "删除应用":confirmDelete(catalog,pkg,name);break;
            }
        }).show();
    }
    int boundProjects(WorkbenchCatalog catalog,String pkg,String space){int count=0;for(JSONObject ref:catalog.bindings.values())if(pkg.equals(ref.optString("package"))&&(space==null||space.equals(ref.optString("space"))))count++;return count;}
    private void confirmDelete(WorkbenchCatalog catalog,String pkg,String name){
        int spaces=catalog.installations(pkg).size(),projects=boundProjects(catalog,pkg,null);
        ProductUi.confirm(page,"删除「"+name+"」？",(spaces>1?"将从 "+spaces+" 个空间中删除这个应用及其全部数据":"将删除这个应用及其全部数据")+"，并删除这个应用的脚本"+"。无法恢复。","删除",true,()->delete(pkg,name));
    }
    private void choose(List<JSONObject> installs,String title,Consumer<JSONObject> selected){
        if(installs.isEmpty()){ProductUi.notice(page,"应用暂时不可用","请刷新后重试。");return;}
        if(installs.size()==1){selected.accept(installs.get(0));return;}
        String[] labels=installs.stream().map(i->app.spaces.name(i.optString("space"))+" · 版本 "+i.optLong("version")).toArray(String[]::new);
        new AlertDialog.Builder(page).setTitle(title).setItems(labels,(dialog,index)->selected.accept(installs.get(index))).setNegativeButton("取消",null).show();
    }
    private boolean remember(String pkg,JSONObject install){
        if(choices.edit().putString(pkg,install.optString("space")).commit())return true;
        ProductUi.notice(page,"没有保存空间选择","请重试。");return false;
    }
    private boolean ready(){
        // Unconfirmed operations do not block; the engine rejects a conflicting request itself.
        if(app.engine.ready())return true;
        ProductUi.notice(page,"暂时不能操作","运行引擎还没有连接，请稍后再试。");return false;
    }
    private Bundle request(String command,JSONObject install){return ControlWire.request(command,"operation",UUID.randomUUID().toString(),"space",install.optString("space"),"package",install.optString("package"),"generation",install.optString("generation"));}
    private void launch(JSONObject install){launch(install,null);}
    /** A launch that finds every process slot taken offers to stop a running application instead of failing on an internal code. */
    private void launch(JSONObject install,WorkbenchCatalog catalog){
        if(!ready())return;
        String name=WorkbenchCatalog.name(app,install);
        try{app.operations.submit(request("prepareApplicationLaunch",install),"启动应用「"+name+"」",null,(prepared,error)->{
            if(error!=null){
                if(catalog!=null&&capacity(error)){offerSlot(catalog,install);return;}
                if(catalog!=null&&startTimeout(error)){offerTimeout(catalog,install,name);return;}
                launchFailed(page,app,null,error);return;
            }
            if(prepared.getBoolean("woken",false))return;
            enter(page,app,prepared,!page.isDestroyed()&&!page.isFinishing()&&visible(),"应用已准备好，但 Tripoder 不在前台，没有打开界面。请回到 Tripoder 后重新启动。");
        });}catch(Exception error){launchFailed(page,app,null,error);}
    }
    /** The startup budget is two minutes (RuntimeStartupDeadline): a launch that runs it out says something about contention,
     *  not about the application. Name the application and offer the two real next steps instead of an internal code. */
    private static boolean startTimeout(Exception error){
        String message=error==null?null:error.getMessage();
        return message!=null&&(message.contains("PROCESS_START_TIMEOUT")||message.contains("ENGINE_CONSTRUCTION_TIMEOUT")||message.contains("WORKER_BIND_TIMEOUT"));
    }
    private void offerTimeout(WorkbenchCatalog catalog,JSONObject install,String name){
        new AlertDialog.Builder(page).setTitle("应用没有启动")
            .setMessage("「"+name+"」的启动在 2 分钟内没有完成。容器同时运行的应用越多，启动越慢；先停掉其它正在运行的应用再重试，通常就能起来。")
            .setPositiveButton("重试",(dialog,which)->launch(install,catalog))
            .setNeutralButton("先停掉其它应用",(dialog,which)->offerSlot(catalog,install))
            .setNegativeButton("取消",null).show();
    }
    /** The process pool is a real budget (RuntimeSlot.CAPACITY, shared by every process an app declares): a full pool must
     *  offer a way forward instead of handing the user an internal code. Nothing is stopped without the user choosing it. */
    private static final String PROCESS_CAPACITY="PROCESS_CAPACITY_EXHAUSTED";
    private static boolean capacity(Exception error){String message=error==null?null:error.getMessage();return message!=null&&message.contains(PROCESS_CAPACITY);}
    private void offerSlot(WorkbenchCatalog catalog,JSONObject install){
        String target=install.optString("package");
        List<JSONObject> live=new ArrayList<>();
        for(JSONObject row:catalog.running){String pkg=row.optString("package");if(pkg.isEmpty()||pkg.equals(target)||"DEAD".equals(row.optString("state")))continue;live.add(row);}
        String name=WorkbenchCatalog.name(app,install);
        if(live.isEmpty()){ProductUi.notice(page,"运行槽位已满","容器同时运行的应用进程已经达到上限，「"+name+"」需要其中一个。现在没有可以停止的应用，请稍后再试。");return;}
        String[] labels=live.stream().map(row->catalog.appName(app,row.optString("package"))+" · "+app.spaces.name(row.optString("space"))).toArray(String[]::new);
        new AlertDialog.Builder(page).setTitle("运行槽位已满")
            .setMessage("容器同时运行的应用进程已经达到上限，「"+name+"」需要其中一个。停止一个正在运行的应用后再启动它：停止不会删除任何数据。")
            .setItems(labels,(dialog,index)->stopThenLaunch(live.get(index),install))
            .setNegativeButton("取消",null).show();
    }
    private void stopThenLaunch(JSONObject victim,JSONObject install){
        if(!ready())return;
        try{app.operations.submit(request("stopApplication",victim),"停止正在运行的应用",null,(value,failure)->{
            if(failure!=null){ProductUi.notice(page,"没有停止成功",UiText.error(failure));return;}
            launch(install);
        });}catch(Exception error){ProductUi.notice(page,"没有停止成功",UiText.error(error));}
    }
    /** 强行停止就是这个应用在所有空间中的运行一起停止：一次确认，逐空间停止，结束时只报一次结果。 */
    void stopEverywhere(WorkbenchCatalog catalog,String pkg,String name){
        if(!ready())return;
        List<JSONObject> installs=catalog.installations(pkg);
        if(installs.isEmpty()){ProductUi.notice(page,"没有正在运行的应用","「"+name+"」已不在任何空间中，项目与脚本仍然保留。");return;}
        List<String> where=new ArrayList<>();boolean running=false;
        for(JSONObject install:installs){String space=install.optString("space");where.add(app.spaces.name(space));running|=!catalog.runState(pkg,space).isEmpty();}
        String listed=String.join("、",where);
        String message=(where.size()==1?("「"+listed+"」中「"+name+"」正在运行的界面和后台任务会立即结束。"):("「"+name+"」同时在 "+where.size()+" 个空间中（"+listed+"）；这些空间里正在运行的界面和后台任务会立即结束，正在进行的下载和未保存的内容可能丢失。"));
        if(!running)message+="现在没有检测到运行中的进程，仍会逐个空间确认已停止。";
        ProductUi.confirm(page,"强行停止「"+name+"」？",message,"强行停止",true,()->batchStop(catalog,installs,failed->{}));
    }
    private void uninstall(JSONObject install,String name){
        if(!ready())return;
        try{app.operations.submit(request("uninstallApplication",install),"从「"+app.spaces.name(install.optString("space"))+"」移除「"+name+"」",null);}
        catch(Exception error){ProductUi.notice(page,"没有移除",UiText.error(error));}
    }
    /** Every installation, its data and the projects bound to it go; the confirmation states exactly that before anything is sent. */
    private void delete(String pkg,String name){
        if(!ready())return;
        try{
            app.operations.submit(ControlWire.request("deleteApplication","operation",UUID.randomUUID().toString(),"package",pkg),"删除应用「"+name+"」",null);
        }catch(Exception error){ProductUi.notice(page,"没有删除",UiText.error(error));}
    }
    /** Batch work is a queue of independent engine operations: one at a time keeps every result attributable to the app it belongs to. */
    private boolean batching;
    boolean batching(){return batching;}
    private static final class BatchSummary{
        int ok;final List<String> failures=new ArrayList<>(),failed=new ArrayList<>();
        void failed(String pkg,String name,Throwable error){fail(pkg,name,UiText.error(error));}
        void fail(String pkg,String name,String reason){failed.add(pkg);failures.add(name+"："+reason);}
    }
    void batchDelete(WorkbenchCatalog catalog,List<String> pkgs,Consumer<List<String>> onDone){
        if(batching||pkgs.isEmpty())return;batching=true;BatchSummary summary=new BatchSummary();deleteStep(catalog,pkgs,0,summary,onDone);
    }
    /** One delete per call: the next request starts only after the previous result is known, so a failure never hides the rest. */
    private void deleteStep(WorkbenchCatalog catalog,List<String> pkgs,int index,BatchSummary summary,Consumer<List<String>> onDone){
        if(index>=pkgs.size()){finish("批量删除",summary,onDone);return;}
        String pkg=pkgs.get(index),name=catalog.appName(app,pkg);
        app.operations.inform("正在删除 ("+(index+1)+"/"+pkgs.size()+")「"+name+"」…");
        try{
            app.operations.submit(ControlWire.request("deleteApplication","operation",UUID.randomUUID().toString(),"package",pkg),"删除应用「"+name+"」",null,(value,error)->{
                if(error==null)summary.ok++;else summary.failed(pkg,name,error);
                deleteStep(catalog,pkgs,index+1,summary,onDone);
            });
        }catch(Exception error){summary.failed(pkg,name,error);deleteStep(catalog,pkgs,index+1,summary,onDone);}
    }
    void batchStop(WorkbenchCatalog catalog,List<JSONObject> installs,Consumer<List<String>> onDone){
        if(batching||installs.isEmpty())return;batching=true;BatchSummary summary=new BatchSummary();stopStep(catalog,installs,0,summary,onDone);
    }
    private void stopStep(WorkbenchCatalog catalog,List<JSONObject> installs,int index,BatchSummary summary,Consumer<List<String>> onDone){
        if(index>=installs.size()){finish("批量强行停止",summary,onDone);return;}
        JSONObject install=installs.get(index);String pkg=install.optString("package"),spaceName=app.spaces.name(install.optString("space")),name=catalog.appName(app,pkg);
        app.operations.inform("正在停止 ("+(index+1)+"/"+installs.size()+")「"+name+"」("+spaceName+")…");
        try{
            app.operations.submit(request("stopApplication",install),"停止「"+name+"」",null,(value,error)->{
                if(error==null)summary.ok++;else summary.failed(pkg,name,error);
                stopStep(catalog,installs,index+1,summary,onDone);
            });
        }catch(Exception error){summary.failed(pkg,name,error);stopStep(catalog,installs,index+1,summary,onDone);}
    }
    void batchRemove(WorkbenchCatalog catalog,List<JSONObject> installs,Consumer<List<String>> onDone){
        if(batching||installs.isEmpty())return;batching=true;BatchSummary summary=new BatchSummary();removeStep(catalog,installs,0,summary,onDone);
    }
    private void removeStep(WorkbenchCatalog catalog,List<JSONObject> installs,int index,BatchSummary summary,Consumer<List<String>> onDone){
        if(index>=installs.size()){finish("批量从空间移除",summary,onDone);return;}
        JSONObject install=installs.get(index);String pkg=install.optString("package"),spaceName=app.spaces.name(install.optString("space")),name=catalog.appName(app,pkg);
        app.operations.inform("正在移除 ("+(index+1)+"/"+installs.size()+")「"+name+"」("+spaceName+")…");
        try{
            app.operations.submit(request("uninstallApplication",install),"从「"+spaceName+"」移除「"+name+"」",null,(value,error)->{
                if(error==null)summary.ok++;else summary.failed(pkg,name,error);
                removeStep(catalog,installs,index+1,summary,onDone);
            });
        }catch(Exception error){summary.failed(pkg,name,error);removeStep(catalog,installs,index+1,summary,onDone);}
    }
    /** One summary instead of a stream of status lines; failed targets come back so the caller keeps exactly them selected. */
    private void finish(String verb,BatchSummary summary,Consumer<List<String>> onDone){
        batching=false;int total=summary.ok+summary.failures.size();
        app.operations.inform(verb+"："+summary.ok+"/"+total+" 个完成"+(summary.failures.isEmpty()?"":"，"+summary.failures.size()+" 个失败"));
        if(!summary.failures.isEmpty()){
            StringBuilder body=new StringBuilder("这些项目没有完成，可以重试：\n");
            for(int i=0;i<Math.min(6,summary.failures.size());i++)body.append("· ").append(summary.failures.get(i)).append('\n');
            if(summary.failures.size()>6)body.append("…共 ").append(summary.failures.size()).append(" 项\n");
            ProductUi.notice(page,verb+"未全部完成",body.toString().trim());
        }
        if(onDone!=null)onDone.accept(new ArrayList<>(summary.failed));
    }
}
