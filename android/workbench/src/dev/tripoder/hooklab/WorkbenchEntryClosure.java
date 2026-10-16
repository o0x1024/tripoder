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
            if(error!=null){ProductUi.notice(page,"无法查询旧启动入口",error.getMessage());return;}
            try{
                JSONArray entries=new JSONArray(value.getString("entries"));if(entries.length()==0){ProductUi.notice(page,"没有可关闭的旧入口","只有进程已退出、界面尚未附着且结果未知的启动，才支持此操作。");return;}
                String[] labels=new String[entries.length()];for(int i=0;i<labels.length;i++)labels[i]=(entries.getJSONObject(i).optString("kind").equals("ACTIVITY_ROUTE")?"应用内部跳转 · ":"工作台启动 · ")+entries.getJSONObject(i).optString("component","")+"\n"+entries.getJSONObject(i).getString("operation");
                new AlertDialog.Builder(page).setTitle("处理未确认启动").setItems(labels,(dialog,index)->{
                    try{String old=entries.getJSONObject(index).getString("operation");new AlertDialog.Builder(page).setTitle("关闭这个旧启动入口？").setMessage("迟到的旧启动将被拒绝。其他未确认入口仍需分别处理；历史结果仍为未知，资源引用继续保留。").setNegativeButton("返回",null).setPositiveButton("关闭旧入口",(d,w)->{
                        try{app.operations.closeEntry(ControlWire.request("closeLaunchEntry","operation",UUID.randomUUID().toString(),"project",project,"bindingOperation",value.getString("bindingOperation"),"generation",value.getString("generation"),"launchOperation",old));}
                        catch(Exception failure){ProductUi.notice(page,"尚未提交关闭",failure.getMessage());}
                    }).show();}catch(Exception invalid){ProductUi.notice(page,"入口记录不可用",invalid.getMessage());}
                }).show();
            }catch(Exception invalid){ProductUi.notice(page,"入口记录不可用",invalid.getMessage());}
        });
    }
    private WorkbenchEntryClosure(){}
}
