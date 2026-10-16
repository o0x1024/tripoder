package dev.tripoder.hooklab;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import dev.tripoder.control.ControlWire;
import java.util.*;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Tap runs an installation. Long press manages it without choosing a project implicitly. */
final class WorkbenchAppActions {
    private final Activity page;private final WorkbenchApplication app;private final SharedPreferences choices;
    WorkbenchAppActions(Activity page,WorkbenchApplication app){this.page=page;this.app=app;choices=page.getSharedPreferences("application-launch",Context.MODE_PRIVATE);}
    void launch(WorkbenchCatalog catalog,String pkg){
        if(!ready())return;
        List<JSONObject> installs=catalog.installations(pkg);String remembered=choices.getString(pkg,"");
        for(JSONObject install:installs)if(remembered.equals(install.optString("space"))){launch(install);return;}
        choose(installs,"选择运行空间",install->{if(remember(pkg,install))launch(install);});
    }
    void menu(WorkbenchCatalog catalog,String pkg,String name){
        new AlertDialog.Builder(page).setTitle(name).setItems(new String[]{"进入项目空间","选择运行空间","删除应用"},(dialog,index)->{
            if(index==0)page.startActivity(new Intent(page,WorkbenchActivity.class).putExtra("application",pkg));
            else if(index==1)choose(catalog.installations(pkg),"选择运行空间",install->{if(remember(pkg,install))app.operations.inform("运行空间已设为 "+install.optString("space"));});
            else{List<JSONObject> installs=new ArrayList<>();for(JSONObject value:catalog.installs)if(pkg.equals(value.optString("package")))installs.add(value);choose(installs,"选择要删除的运行空间",install->confirmDelete(install,name));}
        }).show();
    }
    private void choose(List<JSONObject> installs,String title,Consumer<JSONObject> selected){
        if(installs.isEmpty()){ProductUi.notice(page,"应用尚不可用","请先添加应用，或刷新后重试。");return;}
        if(installs.size()==1){selected.accept(installs.get(0));return;}
        String[] labels=installs.stream().map(i->i.optString("space")+" · 版本 "+i.optLong("version")).toArray(String[]::new);
        new AlertDialog.Builder(page).setTitle(title).setItems(labels,(dialog,index)->selected.accept(installs.get(index))).setNegativeButton("取消",null).show();
    }
    private boolean remember(String pkg,JSONObject install){
        if(choices.edit().putString(pkg,install.optString("space")).commit())return true;
        ProductUi.notice(page,"未能保存运行空间","请重试。");return false;
    }
    private boolean ready(){
        if(app.engine.ready()&&!app.operations.pending()&&!app.imports.preparing)return true;
        ProductUi.notice(page,"暂时无法操作",app.engine.ready()?"请先等待当前操作确认。":"请先连接运行引擎。");app.operations.query();return false;
    }
    private Bundle request(String command,JSONObject install){return ControlWire.request(command,"operation",UUID.randomUUID().toString(),"space",install.optString("space"),"package",install.optString("package"),"generation",install.optString("generation"));}
    private void launch(JSONObject install){
        if(!ready())return;
        try{app.operations.submit(request("prepareApplicationLaunch",install),"启动应用",null,(prepared,error)->{
            if(page.isDestroyed()||page.isFinishing()||!page.hasWindowFocus()){app.operations.inform("应用已准备，但页面不在前台。请返回后查询启动结果。");return;}
            try{
                Intent gateway=new Intent().setComponent(new ComponentName(EngineConnection.ENGINE,"dev.tripoder.runtime.RuntimeGuestGateway"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK).putExtra("operation",prepared.getString("operation")).putExtra("capability",prepared.getString("capability"));
                page.startActivity(gateway,ActivityOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
            }catch(Exception failure){app.operations.inform("启动入口未确认："+failure.getMessage());}
        });}catch(Exception error){ProductUi.notice(page,"启动未提交",error.getMessage());}
    }
    private void confirmDelete(JSONObject install,String name){
        if(!ready())return;
        new AlertDialog.Builder(page).setTitle("删除应用？")
            .setMessage("将停止并删除「"+name+"」在空间「"+install.optString("space")+"」中的应用及数据。\n\n项目和脚本会保留，之后可重新绑定应用。其他空间不受影响。")
            .setNegativeButton("取消",null).setPositiveButton("删除应用",(dialog,which)->{
                if(!ready())return;
                try{app.operations.submit(request("uninstallApplication",install),"删除应用",null);}catch(Exception error){ProductUi.notice(page,"删除未提交",error.getMessage());}
            }).show();
    }
}
