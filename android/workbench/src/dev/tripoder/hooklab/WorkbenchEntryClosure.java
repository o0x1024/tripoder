package dev.tripoder.hooklab;

import android.app.*;
import dev.tripoder.control.ControlWire;
import java.util.UUID;
import org.json.*;

/** Explicit recovery choice: it does not replay the original launch or call it successful. */
final class WorkbenchEntryClosure {
    static void show(Activity page,WorkbenchApplication app,String project){
        app.engine.execute(ControlWire.request("launchEntries","project",project),(value,error)->{
            if(page.isFinishing()||page.isDestroyed())return;
            if(error!=null){ProductUi.notice(page,"无法读取启动记录",UiText.error(error));return;}
            try{
                JSONArray entries=new JSONArray(value.getString("entries"));if(entries.length()==0){ProductUi.notice(page,"没有需要处理的旧入口","目前没有卡住的启动。只有应用已经退出、界面没有打开、结果也未知的启动才需要在这里处理。");return;}
                String[] labels=new String[entries.length()];for(int i=0;i<labels.length;i++)labels[i]=(entries.getJSONObject(i).optString("kind").equals("ACTIVITY_ROUTE")?"应用内部跳转 · ":"从 Tripoder 启动 · ")+entries.getJSONObject(i).optString("component","");
                new AlertDialog.Builder(page).setTitle("选择要关闭的旧入口").setItems(labels,(dialog,index)->{
                    try{String old=entries.getJSONObject(index).getString("operation");ProductUi.confirm(page,"关闭这个旧入口？","关闭后，这次卡住的启动不会再继续，之后可以重新启动应用。应用数据不受影响；如果还有其他旧入口，需要分别处理。","关闭旧入口",false,()->{
                        try{app.operations.closeEntry(ControlWire.request("closeLaunchEntry","operation",UUID.randomUUID().toString(),"project",project,"bindingOperation",value.getString("bindingOperation"),"generation",value.getString("generation"),"launchOperation",old));}
                        catch(Exception failure){ProductUi.notice(page,"没有关闭",UiText.error(failure));}
                    });}catch(Exception invalid){ProductUi.notice(page,"启动记录不可用",UiText.error(invalid));}
                }).show();
            }catch(Exception invalid){ProductUi.notice(page,"启动记录不可用",UiText.error(invalid));}
        });
    }
    private WorkbenchEntryClosure(){}
}
