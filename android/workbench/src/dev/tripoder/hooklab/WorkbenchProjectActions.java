package dev.tripoder.hooklab;

import android.app.*;
import android.widget.*;
import dev.tripoder.control.ControlWire;
import org.json.JSONObject;
import java.util.UUID;

/** Rename and delete a project, from the project list and the project page alike. */
final class WorkbenchProjectActions {
    static void menu(Activity page,WorkbenchApplication app,WorkbenchCatalog catalog,String id,String name,Runnable changed){
        new AlertDialog.Builder(page).setTitle(name).setItems(new String[]{"重命名","删除项目"},(dialog,index)->{if(index==0)rename(page,app,id,name,changed);else delete(page,app,catalog,id,name,changed);}).show();
    }
    static void rename(Activity page,WorkbenchApplication app,String id,String name,Runnable changed){
        LinearLayout form=ProductUi.card(page);EditText input=ProductUi.field(form,"项目名称","",name,false);input.setContentDescription("项目名称");
        AlertDialog dialog=new AlertDialog.Builder(page).setTitle("重命名项目").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{try{app.projects.rename(id,input.getText().toString());dialog.dismiss();changed.run();}catch(Exception error){input.setError(UiText.error(error));}}));dialog.show();
    }
    /** The binding is removed by the engine first; the local source goes with the receipt, so a failed deletion leaves the project intact. */
    static void delete(Activity page,WorkbenchApplication app,WorkbenchCatalog catalog,String id,String name,Runnable changed){
        var session=app.hooks.peek(id);String state=session==null||session.status==null?"":session.status.optString("state");
        if(state.equals("ACTIVE")||state.equals("DRAINING")){ProductUi.notice(page,"脚本仍在运行","请先在项目的「运行与部署」中停用脚本，再删除项目。");return;}
        if(catalog==null||!app.engine.ready()){ProductUi.notice(page,"暂时不能删除","运行引擎还没有连接，无法确认项目的运行目标。请稍后再试。");return;}
        JSONObject binding=catalog.bindings.get(id);
        ProductUi.confirm(page,"删除项目「"+name+"」？","项目的脚本、目标声明和运行记录都会被删除，无法恢复。"+(binding==null?"":"绑定的应用和它的数据会保留。"),"删除",true,()->{
            try{
                if(binding==null){app.projects.delete(id);app.hooks.forget(id);app.operations.inform("已删除项目「"+name+"」");changed.run();return;}
                app.operations.submit(ControlWire.request("deleteProject","operation",UUID.randomUUID().toString(),"project",id),"删除项目「"+name+"」",null);changed.run();
            }catch(Exception error){ProductUi.notice(page,"项目没有删除",UiText.error(error));}
        });
    }
    private WorkbenchProjectActions(){}
}
