package dev.tripoder.hooklab;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import dev.tripoder.control.ControlWire;
import java.util.*;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Tap launches through the bound project (asking only when several exist). Long press manages the application. */
final class WorkbenchAppActions {
    private final Activity page;private final WorkbenchApplication app;private final SharedPreferences choices;
    WorkbenchAppActions(Activity page,WorkbenchApplication app){this.page=page;this.app=app;choices=page.getSharedPreferences("application-launch",Context.MODE_PRIVATE);}
    /** Tap: the space comes first (the space the library is filtered to, else the remembered default, else the only one, else asked).
     *  Then one project bound in that space starts directly, several ask which project, none keeps the plain installation launch. */
    void launch(WorkbenchCatalog catalog,String pkg,String filter){
        if(!ready())return;
        List<JSONObject> installs=catalog.installations(pkg);String remembered=choices.getString(pkg,"");
        for(JSONObject install:installs)if(install.optString("space").equals(filter)){launchIn(catalog,install);return;}
        for(JSONObject install:installs)if(remembered.equals(install.optString("space"))){launchIn(catalog,install);return;}
        choose(installs,"在哪个空间运行？",install->{if(remember(pkg,install))launchIn(catalog,install);});
    }
    /** Only COMMITTED bindings to this installation count; a stale binding (its installation was removed or updated) never takes over the tap. */
    private void launchIn(WorkbenchCatalog catalog,JSONObject install){
        String pkg=install.optString("package"),space=install.optString("space");List<JSONObject> projects=new ArrayList<>();
        try{for(JSONObject project:catalog.projects(app.projects.all(),pkg)){JSONObject ref=catalog.bindings.get(project.optString("id"));if("COMMITTED".equals(ref.optString("state"))&&space.equals(ref.optString("space")))projects.add(project);}}catch(Exception error){ProductUi.notice(page,"读取项目失败",UiText.error(error));return;}
        if(projects.size()==1){launchProject(projects.get(0));return;}
        if(projects.size()>1){
            String[] names=projects.stream().map(project->project.optString("name")).toArray(String[]::new);
            new AlertDialog.Builder(page).setTitle("用哪个项目启动？").setItems(names,(dialog,index)->launchProject(projects.get(index))).setNegativeButton("取消",null).show();return;
        }
        launch(install);
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
        if(project!=null&&message.startsWith("LAUNCH_TARGET_REJECTED"))new AlertDialog.Builder(page).setTitle("应用没有启动").setMessage(UiText.error(error)+"。关闭旧入口不会影响应用数据。").setNegativeButton("取消",null).setPositiveButton("处理旧入口",(d,w)->WorkbenchEntryClosure.show(page,app,project)).show();
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
    void menu(WorkbenchCatalog catalog,String pkg,String name){
        new AlertDialog.Builder(page).setTitle(name).setItems(new String[]{"查看项目","选择默认空间","强行停止","从空间移除","删除应用"},(dialog,index)->{
            if(index==0)page.startActivity(new Intent(page,WorkbenchActivity.class).putExtra("application",pkg));
            else if(index==1)choose(catalog.installations(pkg),"轻点图标时使用的空间",install->{if(remember(pkg,install))app.operations.inform("「"+name+"」将在「"+app.spaces.name(install.optString("space"))+"」中运行");});
            else if(index==2)choose(catalog.installations(pkg),"停止哪个空间中的应用？",install->ProductUi.confirm(page,"强行停止「"+name+"」？","「"+app.spaces.name(install.optString("space"))+"」中正在运行的界面和后台任务会立即结束，尚未保存的内容可能丢失。","强行停止",true,()->stop(install,name)));
            else if(index==3)choose(catalog.installations(pkg),"从哪个空间移除？",install->{int bound=boundProjects(catalog,pkg,install.optString("space"));ProductUi.confirm(page,"从「"+app.spaces.name(install.optString("space"))+"」移除「"+name+"」？","这个空间中该应用的全部数据会被删除，无法恢复。"+(bound>0?bound+" 个项目会保留，但需要重新选择运行目标。":""),"移除",true,()->uninstall(install,name));});
            else confirmDelete(catalog,pkg,name);
        }).show();
    }
    private int boundProjects(WorkbenchCatalog catalog,String pkg,String space){int count=0;for(JSONObject ref:catalog.bindings.values())if(pkg.equals(ref.optString("package"))&&(space==null||space.equals(ref.optString("space"))))count++;return count;}
    private void confirmDelete(WorkbenchCatalog catalog,String pkg,String name){
        int spaces=catalog.installations(pkg).size(),projects=boundProjects(catalog,pkg,null);
        ProductUi.confirm(page,"删除「"+name+"」？",(spaces>1?"将从 "+spaces+" 个空间中删除这个应用及其全部数据":"将删除这个应用及其全部数据")+(projects>0?"，并删除绑定到它的 "+projects+" 个项目（包括脚本）":"")+"。无法恢复。","删除",true,()->delete(pkg,name));
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
    private void launch(JSONObject install){
        if(!ready())return;
        try{app.operations.submit(request("prepareApplicationLaunch",install),"启动应用",null,(prepared,error)->{
            if(error!=null){launchFailed(page,app,null,error);return;}
            if(prepared.getBoolean("woken",false))return;
            enter(page,app,prepared,!page.isDestroyed()&&!page.isFinishing()&&visible(),"应用已准备好，但 Tripoder 不在前台，没有打开界面。请回到 Tripoder 后重新启动。");
        });}catch(Exception error){launchFailed(page,app,null,error);}
    }
    private void stop(JSONObject install,String name){
        if(!ready())return;
        try{app.operations.submit(request("stopApplication",install),"停止「"+name+"」",null);}
        catch(Exception error){ProductUi.notice(page,"没有停止",UiText.error(error));}
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
}
