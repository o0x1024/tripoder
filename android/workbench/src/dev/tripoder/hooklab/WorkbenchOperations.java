package dev.tripoder.hooklab;

import android.content.*;
import android.os.*;
import dev.tripoder.control.ControlWire;
import org.json.JSONObject;
import java.io.Closeable;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** One durable record per unconfirmed mutation; navigation observes them and never replays them.
 *  Operations on different targets proceed independently and the engine judges conflicts; only a request still crossing Binder blocks a second one on the same target. */
final class WorkbenchOperations {
    private static final String RECORD="op-";
    private final Context host;private final EngineConnection engine;private final SharedPreferences state;
    private final WorkbenchProjects projects;
    private final List<Runnable> observers=new CopyOnWriteArrayList<>();
    // Main-thread confined: submissions and engine callbacks both run on the main looper.
    private final Set<String> submitting=new HashSet<>(),querying=new HashSet<>();
    String message="";int settled;
    WorkbenchOperations(Context host,EngineConnection engine){this.host=host;this.engine=engine;state=host.getSharedPreferences("operations",Context.MODE_PRIVATE);projects=((WorkbenchApplication)host.getApplicationContext()).projects;migrate();}
    /** The journal used to hold a single "pending" operation; it becomes an ordinary record of unknown command. */
    private void migrate(){
        String id=state.getString("pending",null);if(id==null)return;
        try{if(!state.edit().putString(RECORD+id,new JSONObject().put("label",state.getString("label","操作")).put("scope","").put("command","").toString()).remove("pending").remove("label").remove("space").commit())throw new IllegalStateException("操作记录迁移失败");}
        catch(org.json.JSONException error){throw new IllegalStateException(error);}
    }
    /** Main-thread invalidation after an external control request; this never replays the request. */
    void externalChanged(){settled++;changed();}
    void observe(Runnable observer){observers.add(observer);}
    void remove(Runnable observer){observers.remove(observer);}
    private void changed(){for(var observer:observers)observer.run();}
    boolean pending(){return !pendingIds().isEmpty()||state.contains("resolution");}
    List<String> pendingIds(){List<String> ids=new ArrayList<>();for(String key:state.getAll().keySet())if(key.startsWith(RECORD))ids.add(key.substring(RECORD.length()));Collections.sort(ids);return ids;}
    String pendingId(){var ids=pendingIds();return ids.isEmpty()?state.getString("resolution",""):ids.get(0);}
    boolean busyProject(String project){return submitting.contains("project:"+project);}
    boolean busyPackage(String pkg){return submitting.contains("package:"+pkg);}
    /** Imports and space creation name neither a project nor a package. */
    boolean busyUnscoped(){return submitting.contains("");}
    /** Something is crossing Binder or being reconciled right now; pages show a progress indicator. */
    boolean busy(){return !submitting.isEmpty()||!querying.isEmpty();}
    /** Changes whenever the set of in-flight targets changes, so pages can redraw busy tiles and buttons only then. */
    String busySignature(){return new TreeSet<>(submitting).toString();}
    /** Launch records of one project the engine has not settled yet. */
    int pendingLaunches(String project){int count=0;for(String id:pendingIds()){JSONObject record=record(id);if(record==null)continue;String command=record.optString("command");if((command.equals("prepareLaunch")||command.isEmpty())&&record.optString("scope").equals("project:"+project))count++;}return count;}
    private static String scope(Bundle input){String project=input.getString("project");if(project!=null)return "project:"+project;String pkg=input.getString("package");return pkg==null?"":"package:"+pkg;}
    private JSONObject record(String id){try{String raw=state.getString(RECORD+id,null);return raw==null?null:new JSONObject(raw);}catch(org.json.JSONException corrupt){return new JSONObject();}}
    void inform(String text){message=text;changed();}
    void submit(Bundle input,String label,Closeable resource)throws Exception{
        submit(input,label,resource,null);
    }
    /** {@code accepted} also hears a rejected or uncertain submission (value null), so callers can offer a next step; the record is still reconciled by query. */
    void submit(Bundle input,String label,Closeable resource,EngineConnection.Result accepted)throws Exception{
        if(!engine.ready())throw new IllegalStateException("引擎尚未连接，本次操作未提交");
        String id=input.getString("operation");if(id==null)throw new IllegalArgumentException("操作 ID 缺失");
        String scope=scope(input);if(submitting.contains(scope))throw new IllegalStateException("同一目标的上一项操作仍在提交");
        String record=new JSONObject().put("label",label).put("scope",scope).put("command",input.getString("command")).toString();
        if(!state.edit().putString(RECORD+id,record).commit())throw new IllegalStateException("操作记录保存失败，尚未提交");
        submitting.add(scope);inform(label+" · 处理中");
        engine.execute(input,(value,error)->{
            submitting.remove(scope);
            if(resource!=null)try{resource.close();}catch(Exception close){inform("临时文件清理失败："+UiText.error(close));}
            if(!state.contains(RECORD+id)){changed();return;}
            if(error!=null){inform(label+" · 结果待确认："+UiText.error(error));resolve("query",id);if(accepted!=null)accepted.complete(null,error);return;}
            if(accepted!=null)accepted.complete(value,null);
            // A response is not a substitute for the authority's durable operation record.
            resolve("query",id);
        });
    }
    JSONObject terminalReceipt(String operation){try{String value=state.getString("receipt-"+operation,null);return value==null?null:new JSONObject(value);}catch(Exception invalid){return null;}}
    void query(){resolveAll("query");}
    void cancelIfNotAccepted(){resolveAll("cancelNotAccepted");}
    private void resolveAll(String command){
        if(state.contains("resolution"))resolveEntry(command);
        // The launch an entry closure targets is settled by that closure's receipt.
        String closing=state.contains("resolution")?state.getString("resolutionTarget",""):"";
        for(String id:pendingIds())if(!id.equals(closing))resolve(command,id);
    }
    void closeEntry(Bundle input)throws Exception{
        if(!engine.ready()||state.contains("resolution"))throw new IllegalStateException("请先查询当前入口关闭操作");
        String target=input.getString("launchOperation"),operation=input.getString("operation");
        if(target==null||operation==null)throw new IllegalArgumentException("关闭操作信息缺失");
        if(!state.edit().putString("resolution",operation).putString("resolutionTarget",target).commit())throw new IllegalStateException("关闭操作记录保存失败，尚未提交");
        inform("正在关闭旧的启动入口");
        engine.execute(input,(value,error)->{if(error!=null)inform("关闭旧启动入口的结果待确认："+UiText.error(error));resolveEntry("query");});
    }
    /** Ends local waiting for launches the engine proves inert; with a project, only that project's launches. */
    void acknowledgeUnknown(String project){
        if(!engine.ready()){inform("请先连接引擎");return;}
        List<String> launches=new ArrayList<>();
        for(String id:pendingIds()){
            JSONObject record=record(id);String command=record==null?"":record.optString("command");boolean legacy=command.isEmpty();
            if(!legacy&&!command.equals("prepareLaunch")&&!command.equals("prepareApplicationLaunch"))continue;
            if(project!=null&&!legacy&&!record.optString("scope").equals("project:"+project))continue;
            launches.add(id);
        }
        if(launches.isEmpty()){inform(project==null?"没有待确认的启动":"这个项目没有待确认的启动");return;}
        for(String id:launches)acknowledge(project,id);
    }
    private void acknowledge(String project,String pending){
        if(querying.contains(pending)||pending.equals(state.getString("resolutionTarget",null))){inform("请先查询当前操作");return;}
        Bundle request=project==null?ControlWire.request("launchRecovery","operation",pending):ControlWire.request("launchRecovery","project",project,"operation",pending);
        querying.add(pending);engine.execute(request,(proof,error)->{
            querying.remove(pending);if(!state.contains(RECORD+pending))return;
            if(error!=null){inform("不能结束等待："+UiText.error(error));return;}
            if(!proof.getBoolean("canAcknowledge")){inform("应用仍在运行或旧入口仍然开放（入口 "+proof.getInt("openEntries")+" 个，进程 "+proof.getInt("liveProcesses")+" 个），暂时不能结束等待");return;}
            try{
                JSONObject receipt=new JSONObject().put("operation",pending).put("state","UNKNOWN").put("detail",proof.getString("detail")).put("entryInert",true).put("ticketState",proof.getString("ticketState")).put("acknowledgedLocally",true).put("resourceRetirementInferred",false);
                if(project==null)receipt.put("entryIssued",proof.getBoolean("entryIssued"));
                if(!state.edit().putString("receipt-"+pending,receipt.toString()).remove(RECORD+pending).commit())throw new IllegalStateException("未知回执保存失败");
                settled++;inform("已结束等待，可以重新启动应用");
            }catch(Exception failure){inform(UiText.error(failure));}
        });
    }
    private void resolve(String command,String id){
        if(querying.contains(id)||!engine.ready())return;querying.add(id);
        engine.execute(ControlWire.request(command,"operation",id),(value,error)->{
            querying.remove(id);JSONObject record=record(id);if(record==null)return;String label=record.optString("label","操作");
            if(error!=null){inform(label+" · 结果待确认："+UiText.error(error));return;}
            String status=value.getString("state");
            if("SUCCEEDED".equals(status)||"FAILED".equals(status)||"UNKNOWN".equals(status)){
                try{
                    // The engine removed these projects together with the application; their source goes before the receipt, so a retry after a failure repeats an idempotent step.
                    if("SUCCEEDED".equals(status))dropDeletedProjects(value.getString("result"));
                    if("SUCCEEDED".equals(status)&&"deleteSpace".equals(record.optString("command")))WorkbenchSpaces.forget(host,new JSONObject(value.getString("result")).getString("space"));
                    String receipt=new JSONObject().put("operation",id).put("state",status).put("result",value.getString("result")).put("detail",value.getString("detail")).toString();
                    if(!state.edit().putString("receipt-"+id,receipt).remove(RECORD+id).commit())throw new IllegalStateException("无法保存回执，请继续查询原操作");
                    settled++;
                    if("SUCCEEDED".equals(status))inform(label+" · 已完成");
                    else if("FAILED".equals(status))inform(label+" · 未完成："+UiText.detail(value.getString("detail")));
                    else inform(label+" · 结果未知："+UiText.detail(value.getString("detail"))+"。可以重新操作");
                }catch(Exception failure){inform(UiText.error(failure));}
            }else inform(label+" · 仍在处理");
        });
    }
    private void dropDeletedProjects(String result)throws Exception{
        if(result==null||!result.startsWith("{"))return;
        org.json.JSONArray ids=new JSONObject(result).optJSONArray("deletedProjects");
        if(ids!=null)for(int i=0;i<ids.length();i++)projects.delete(ids.getString(i));
    }
    private void resolveEntry(String command){
        String id=state.getString("resolution",""),target=state.getString("resolutionTarget","");
        if(id.isEmpty()||querying.contains(id)||!engine.ready())return;querying.add(id);
        engine.execute(ControlWire.request(command,"operation",id),(value,error)->{
            querying.remove(id);if(!id.equals(state.getString("resolution","")))return;
            if(error!=null){inform("关闭旧启动入口的结果待确认："+UiText.error(error));return;}
            String status=value.getString("state");
            if("FAILED".equals(status)){
                if(!state.edit().remove("resolution").remove("resolutionTarget").commit()){inform("无法保存关闭失败回执，请继续查询");return;}
                inform("旧启动入口没有关闭："+UiText.detail(value.getString("detail")));return;
            }
            if(!"SUCCEEDED".equals(status)||!String.valueOf(value.getString("result")).startsWith("ENTRY_FENCED_RESOURCES_RETAINED:")){inform("关闭旧启动入口仍在处理");return;}
            querying.add(id);engine.execute(ControlWire.request("entryClosureReceipt","operation",id),(original,failure)->{
                querying.remove(id);if(!id.equals(state.getString("resolution","")))return;
                if(failure!=null||!"UNKNOWN".equals(original.getString("state"))||!target.equals(original.getString("launchOperation"))||!original.getBoolean("entryClosed")){inform("旧启动的记录还没有核对完成，请稍后刷新");return;}
                try{
                    String receipt=new JSONObject().put("operation",target).put("state","UNKNOWN").put("detail",original.getString("detail")).put("entryClosedBy",id).put("resourcesRetired",false).toString();
                    var edit=state.edit().putString("receipt-"+target,receipt).putString("receipt-"+id,new JSONObject().put("operation",id).put("state","SUCCEEDED").put("result",value.getString("result")).toString()).remove("resolution").remove("resolutionTarget").remove(RECORD+target);
                    if(!edit.commit())throw new IllegalStateException("无法保存入口关闭回执");
                    settled++;inform("旧启动入口已关闭，可以重新启动应用");
                }catch(Exception invalid){inform(UiText.error(invalid));}
            });
        });
    }
}
