package dev.tripoder.hooklab;

import android.app.*;
import android.os.Bundle;
import android.widget.*;
import dev.tripoder.control.ControlWire;
import org.json.JSONObject;
import java.util.*;
import java.util.function.Consumer;

/** New projects are created from an explicit application and installation. */
final class WorkbenchProjectCreation {
    static void show(Activity activity,WorkbenchApplication app,WorkbenchCatalog catalog,String pkg,Consumer<String> open){
        if(catalog==null||!app.engine.ready()||app.operations.pending()||app.imports.preparing){ProductUi.notice(activity,"暂时无法创建项目","请连接引擎，并等待当前操作确认。");return;}
        var choices=pkg==null?catalog.committed():catalog.installations(pkg);
        if(choices.isEmpty()){ProductUi.notice(activity,"先添加应用","从应用页添加应用后，再为它创建项目。");return;}
        if(choices.size()==1){form(activity,app,choices.get(0),open);return;}
        new AlertDialog.Builder(activity).setTitle(pkg==null?"选择项目所属应用与空间":"选择运行空间").setItems(choices.stream().map(WorkbenchCatalog::label).toArray(String[]::new),(dialog,index)->form(activity,app,choices.get(index),open)).show();
    }
    private static void form(Activity activity,WorkbenchApplication app,JSONObject install,Consumer<String> open){
        LinearLayout form=ProductUi.card(activity);ProductUi.add(form,ProductUi.text(activity,WorkbenchCatalog.label(install),13,ProductUi.MUTED,false),0);
        EditText name=ProductUi.field(form,"项目名称","例如：登录流程分析","",false);
        name.setContentDescription("新项目名称");
        ProductUi.add(form,ProductUi.text(activity,"脚本独立保存。同一空间内的项目共享应用数据。",12,ProductUi.MUTED,false),12);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("新建项目").setView(form).setNegativeButton("取消",null).setPositiveButton("创建项目",null).create();
        dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(button->{
            if(!app.engine.ready()||app.operations.pending()||app.imports.preparing){name.setError("请等待当前操作确认");return;}
            String id;
            try{id=app.projects.create(name.getText().toString());}catch(Exception error){name.setError(error.getMessage());return;}
            dialog.dismiss();
            try{Bundle input=ControlWire.request("bindProject","operation",UUID.randomUUID().toString(),"project",id,"space",install.getString("space"),"package",install.getString("package"),"generation",install.getString("generation"));app.operations.submit(input,"绑定项目所属应用",null);}
            catch(Exception error){app.operations.inform("项目已保存，应用绑定未完成："+error.getMessage());}
            open.accept(id);
        }));dialog.show();
    }
    private WorkbenchProjectCreation(){}
}
