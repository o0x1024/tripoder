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
        if(catalog==null||!app.engine.ready()){ProductUi.notice(activity,"暂时不能新建项目","运行引擎还没有连接，请稍后再试。");return;}
        var choices=pkg==null?catalog.committed():catalog.installations(pkg);
        if(choices.isEmpty()){ProductUi.notice(activity,"先添加一个应用","项目需要绑定一个应用。请先在应用页添加应用。");return;}
        if(choices.size()==1){form(activity,app,choices.get(0),open);return;}
        new AlertDialog.Builder(activity).setTitle(pkg==null?"新项目绑定哪个应用？":"选择空间").setItems(choices.stream().map(install->WorkbenchCatalog.label(app,install)).toArray(String[]::new),(dialog,index)->form(activity,app,choices.get(index),open)).show();
    }
    private static void form(Activity activity,WorkbenchApplication app,JSONObject install,Consumer<String> open){
        LinearLayout form=ProductUi.card(activity);ProductUi.add(form,ProductUi.text(activity,WorkbenchCatalog.label(app,install),13,ProductUi.MUTED,false),0);
        EditText name=ProductUi.field(form,"项目名称","例如：登录流程分析","",false);
        name.setContentDescription("新项目名称");
        ProductUi.add(form,ProductUi.text(activity,"每个项目保存自己的脚本；同一空间中的项目共享应用数据。",12,ProductUi.MUTED,false),12);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("新建项目").setView(form).setNegativeButton("取消",null).setPositiveButton("创建项目",null).create();
        dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(button->{
            if(!app.engine.ready()){name.setError("请先连接引擎");return;}
            String id;
            try{id=app.projects.create(name.getText().toString());}catch(Exception error){name.setError(UiText.error(error));return;}
            dialog.dismiss();
            try{Bundle input=ControlWire.request("bindProject","operation",UUID.randomUUID().toString(),"project",id,"space",install.getString("space"),"package",install.getString("package"),"generation",install.getString("generation"));app.operations.submit(input,"设置运行目标",null);}
            catch(Exception error){app.operations.inform("项目已创建，但运行目标没有设置："+UiText.error(error));}
            open.accept(id);
        }));dialog.show();
    }
    private WorkbenchProjectCreation(){}
}
