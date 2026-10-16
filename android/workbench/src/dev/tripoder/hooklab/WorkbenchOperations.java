package dev.tripoder.hooklab;

import android.content.*;
import android.os.*;
import dev.tripoder.control.ControlWire;
import org.json.JSONObject;
import java.io.Closeable;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** One durable pending mutation; navigation observes it and never replays it. */
final class WorkbenchOperations {
    private final EngineConnection engine;private final SharedPreferences state;
    private final List<Runnable> observers=new CopyOnWriteArrayList<>();
    String message="";private boolean querying;
    WorkbenchOperations(Context host,EngineConnection engine){this.engine=engine;state=host.getSharedPreferences("operations",Context.MODE_PRIVATE);}
    void observe(Runnable observer){observers.add(observer);}
    void remove(Runnable observer){observers.remove(observer);}
    private void changed(){for(var observer:observers)observer.run();}
    boolean pending(){return state.contains("pending")||state.contains("resolution");}
    String pendingId(){return state.getString("pending",state.getString("resolution",""));}
    void inform(String text){message=text;changed();}
    void submit(Bundle input,String label,Closeable resource)throws Exception{
        submit(input,label,resource,null);
    }
    void submit(Bundle input,String label,Closeable resource,EngineConnection.Result accepted)throws Exception{
        if(pending())throw new IllegalStateException("请先确认上一项操作的结果");
        if(!engine.ready())throw new IllegalStateException("引擎尚未连接，本次操作未提交");
        String id=input.getString("operation");if(id==null)throw new IllegalArgumentException("操作 ID 缺失");
        if(!state.edit().putString("pending",id).putString("label",label).commit())throw new IllegalStateException("操作记录保存失败，尚未提交");
        inform(label+" · 处理中");
        engine.execute(input,(value,error)->{
            if(resource!=null)try{resource.close();}catch(Exception close){inform("临时文件清理失败："+close.getMessage());}
            if(!id.equals(pendingId()))return;
            if(error!=null){inform(label+" · 结果待确认\n"+error.getMessage());query();return;}
            if(accepted!=null)accepted.complete(value,null);
            // A response is not a substitute for the authority's durable operation record.
            query();
        });
    }
    void query(){resolve("query");}
    void cancelIfNotAccepted(){resolve("cancelNotAccepted");}
    void closeEntry(Bundle input)throws Exception{
        if(!engine.ready()||state.contains("resolution")||querying)throw new IllegalStateException("请先查询当前操作");
        String target=input.getString("launchOperation"),operation=input.getString("operation"),pending=state.getString("pending","");
        if(target==null||operation==null)throw new IllegalArgumentException("关闭操作信息缺失");
        if(pending.isEmpty()||target.equals(pending)){beginClose(input);return;}
        querying=true;engine.execute(ControlWire.request("launchRecovery","project",input.getString("project"),"operation",pending),(scope,error)->{
            querying=false;if(!pending.equals(state.getString("pending",""))||state.contains("resolution"))return;
            if(error!=null||!input.getString("bindingOperation").equals(scope.getString("bindingOperation"))||!input.getString("generation").equals(scope.getString("generation"))){inform("当前待确认操作不属于这个项目入口，请先处理原操作"+(error==null?"":"："+error.getMessage()));return;}
            try{beginClose(input);}catch(Exception failure){inform(failure.getMessage());}
        });
    }
    private void beginClose(Bundle input)throws Exception{
        String target=input.getString("launchOperation"),operation=input.getString("operation");
        if(!state.edit().putString("resolution",operation).putString("resolutionTarget",target).commit())throw new IllegalStateException("关闭操作记录保存失败，尚未提交");
        inform("正在永久关闭旧启动入口，历史结果与资源引用仍保留");
        engine.execute(input,(value,error)->{if(error!=null)inform("入口关闭结果待确认："+error.getMessage());query();});
    }
    void acknowledgeUnknown(String project){
        String pending=state.getString("pending","");if(pending.isEmpty()||state.contains("resolution")||querying||!engine.ready()){inform("请先查询当前操作");return;}
        querying=true;engine.execute(ControlWire.request("launchRecovery","project",project,"operation",pending),(proof,error)->{
            querying=false;if(!pending.equals(state.getString("pending",""))||state.contains("resolution"))return;
            if(error!=null){inform("不能结束等待："+error.getMessage());return;}
            if(!proof.getBoolean("canAcknowledge")){inform("仍有未关闭入口或运行进程，原操作继续保留。入口："+proof.getInt("openEntries")+"，进程："+proof.getInt("liveProcesses"));return;}
            try{
                JSONObject receipt=new JSONObject().put("operation",pending).put("state","UNKNOWN").put("detail",proof.getString("detail")).put("entryInert",true).put("ticketState",proof.getString("ticketState")).put("acknowledgedLocally",true).put("resourceRetirementInferred",false);
                if(!state.edit().putString("receipt-"+pending,receipt.toString()).remove("pending").remove("label").remove("space").commit())throw new IllegalStateException("未知回执保存失败");
                inform("已保存未知回执并结束本地等待。旧入口不能继续执行；新的启动需要另行提交。");
            }catch(Exception failure){inform(failure.getMessage());}
        });
    }
    private void resolve(String command){
        if(state.contains("resolution")){resolveEntry(command);return;}
        String id=pendingId();if(id.isEmpty()||querying||!engine.ready())return;querying=true;
        engine.execute(ControlWire.request(command,"operation",id),(value,error)->{
            querying=false;if(state.contains("resolution")){query();return;}if(!id.equals(pendingId()))return;
            if(error!=null){inform("待确认操作 "+id+"\n"+error.getMessage());return;}
            String status=value.getString("state"),label=state.getString("label","操作");
            if("SUCCEEDED".equals(status)||"FAILED".equals(status)){
                try{
                    String receipt=new JSONObject().put("operation",id).put("state",status).put("result",value.getString("result")).put("detail",value.getString("detail")).toString();
                    if(!state.edit().putString("receipt-"+id,receipt).remove("pending").remove("label").remove("space").commit())throw new IllegalStateException("无法保存回执，请继续查询原操作");
                    inform(label+("SUCCEEDED".equals(status)?" · 已完成":" · 未完成："+value.getString("detail")));
                }catch(Exception failure){inform(failure.getMessage());}
            }else inform(label+" · "+status+"\n操作 "+id+" 保留，未重新执行。");
        });
    }
    private void resolveEntry(String command){
        String id=state.getString("resolution",""),target=state.getString("resolutionTarget","");
        if(id.isEmpty()||querying||!engine.ready())return;querying=true;
        engine.execute(ControlWire.request(command,"operation",id),(value,error)->{
            querying=false;if(!id.equals(state.getString("resolution","")))return;
            if(error!=null){inform("入口关闭操作待确认 "+id+"\n"+error.getMessage());return;}
            String status=value.getString("state");
            if("FAILED".equals(status)){
                if(!state.edit().remove("resolution").remove("resolutionTarget").commit()){inform("无法保存关闭失败回执，请继续查询");return;}
                inform("旧入口尚未关闭："+value.getString("detail"));return;
            }
            if(!"SUCCEEDED".equals(status)||!String.valueOf(value.getString("result")).startsWith("ENTRY_FENCED_RESOURCES_RETAINED:")){inform("入口关闭仍待确认 · "+status);return;}
            querying=true;engine.execute(ControlWire.request("entryClosureReceipt","operation",id),(original,failure)->{
                querying=false;if(!id.equals(state.getString("resolution","")))return;
                if(failure!=null||!"UNKNOWN".equals(original.getString("state"))||!target.equals(original.getString("launchOperation"))||!original.getBoolean("entryClosed")){inform("历史启动回执尚未核对，请继续查询");return;}
                try{
                    String receipt=new JSONObject().put("operation",target).put("state","UNKNOWN").put("detail",original.getString("detail")).put("entryClosedBy",id).put("resourcesRetired",false).toString();
                    var edit=state.edit().putString("receipt-"+target,receipt).putString("receipt-"+id,new JSONObject().put("operation",id).put("state","SUCCEEDED").put("result",value.getString("result")).toString()).remove("resolution").remove("resolutionTarget");
                    if(target.equals(state.getString("pending","")))edit.remove("pending").remove("label").remove("space");
                    if(!edit.commit())throw new IllegalStateException("无法保存入口关闭回执");
                    inform("旧启动入口已关闭，原结果仍未知，资源引用保留。"+(state.contains("pending")?"请继续处理同项目入口，随后核验并结束等待。":"新的启动将由引擎重新校验。"));
                }catch(Exception invalid){inform(invalid.getMessage());}
            });
        });
    }
}
