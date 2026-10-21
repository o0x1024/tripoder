package dev.tripoder.hooklab;

import android.content.*;
import android.os.*;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.util.*;

/** Main-thread batch coordinator. Each installation has one durable operation ID; uncertain items are never replayed. */
final class WorkbenchImportBatch implements EngineConnection.Listener {
    private final WorkbenchImports imports;private final WorkbenchOperations operations;private final EngineConnection engine;private final SharedPreferences store;
    private final Handler main=new Handler(Looper.getMainLooper());private final List<Runnable> observers=new ArrayList<>();
    private JSONObject batch;private boolean running,pumping,recovering;private String problem="";
    WorkbenchImportBatch(Context host,WorkbenchImports imports,WorkbenchOperations operations,EngineConnection engine){
        this.imports=imports;this.operations=operations;this.engine=engine;store=host.getSharedPreferences("import-batch",Context.MODE_PRIVATE);
        try{String value=store.getString("current",null);if(value!=null){batch=new JSONObject(value);if(batch.optJSONArray("items")==null||!batch.has("space"))throw new IllegalArgumentException("批量记录无效");}}catch(Exception invalid){batch=null;problem="上次批量添加记录不可读，请核对应用列表。";}
        operations.observe(this::pump);engine.observe(this);
    }
    void observe(Runnable observer){observers.add(observer);}
    void remove(Runnable observer){observers.remove(observer);}
    private void notifyChanged(){for(Runnable observer:new ArrayList<>(observers))observer.run();}
    String id(){return batch==null?"":batch.optString("id");}
    private JSONArray items(){return batch==null?new JSONArray():batch.optJSONArray("items");}
    private static boolean pending(JSONObject item){return Set.of("QUEUED","PREPARING","SUBMITTED").contains(item.optString("state"));}
    boolean active(){for(int i=0;i<items().length();i++)if(pending(items().optJSONObject(i)))return true;return false;}
    boolean running(){return running||recovering;}
    boolean hasResults(){return batch!=null;}
    boolean finished(){return batch!=null&&!active();}
    Set<String> phonePackages(){Set<String> packages=new HashSet<>();for(int i=0;i<items().length();i++){JSONObject item=items().optJSONObject(i);if("phone".equals(item.optString("kind")))packages.add(item.optString("source"));}return packages;}
    String summary(){
        if(!problem.isEmpty())return problem;if(batch==null)return "";int success=0,failed=0,unknown=0,waiting=0;
        for(int i=0;i<items().length();i++){JSONObject item=items().optJSONObject(i);switch(item.optString("state")){case "SUCCEEDED":success++;break;case "FAILED":failed++;break;case "UNKNOWN":unknown++;break;default:waiting++;}}
        if(active()&&!running())return "批量添加已暂停 · 剩余 "+waiting+" 个"+(unknown>0?" · "+unknown+" 个结果待确认":"");
        if(active()){for(int i=0;i<items().length();i++){JSONObject item=items().optJSONObject(i);if(pending(item))return "正在添加 "+(i+1)+" / "+items().length()+" · "+item.optString("label");}}
        return "添加完成 · 成功 "+success+" 个"+(failed>0?" · 失败 "+failed+" 个":"")+(unknown>0?" · "+unknown+" 个结果待确认":"");
    }
    String details(){StringBuilder out=new StringBuilder();for(int i=0;i<items().length();i++){JSONObject item=items().optJSONObject(i);String status=switch(item.optString("state")){case "SUCCEEDED"->"已添加";case "FAILED"->"失败";case "UNKNOWN"->"结果待确认";case "PREPARING","SUBMITTED"->"处理中";default->"等待添加";};out.append(item.optString("label")).append(" · ").append(status);if(!item.optString("error").isEmpty())out.append("\n").append(item.optString("error"));out.append("\n\n");}return out.toString();}
    void start(String space,List<String> sources,List<String> labels,String kind)throws Exception{
        if(active()||imports.preparing||operations.busyUnscoped())throw new IllegalStateException("请先完成当前添加任务");if(!engine.ready())throw new IllegalStateException("运行引擎未连接");
        if(sources.isEmpty()||sources.size()>64||labels.size()!=sources.size())throw new IllegalArgumentException("一次可添加 1–64 个应用");
        JSONArray items=new JSONArray();Set<String> seen=new HashSet<>();for(int i=0;i<sources.size();i++)if(seen.add(sources.get(i)))items.put(new JSONObject().put("kind",kind).put("source",sources.get(i)).put("label",labels.get(i)).put("operation",UUID.randomUUID().toString()).put("state","QUEUED"));
        batch=new JSONObject().put("id",UUID.randomUUID().toString()).put("space",space).put("items",items);problem="";save();running=true;notifyChanged();main.post(this::pump);
    }
    private void save()throws Exception{if(!store.edit().putString("current",batch.toString()).commit())throw new IllegalStateException("批量添加进度保存失败，请刷新核对结果");}
    void resume(){if(!active()||running()||!engine.ready())return;problem="";running=true;recoverNext();}
    private void recoverNext(){
        for(int i=0;i<items().length();i++){JSONObject item=items().optJSONObject(i);if(Set.of("PREPARING","SUBMITTED").contains(item.optString("state"))){recovering=true;engine.execute(ControlWire.request("query","operation",item.optString("operation")),(value,error)->{recovering=false;try{
            if(error!=null){if(String.valueOf(error.getMessage()).contains("OPERATION_NOT_FOUND"))item.put("state","QUEUED");else{running=false;problem="无法确认上一项结果，请连接引擎后继续。";notifyChanged();return;}}
            else if(Set.of("SUCCEEDED","FAILED","UNKNOWN").contains(value.getString("state")))finishItem(item,value.getString("state"),value.getString("detail",""));
            else{running=false;problem="上一项仍在处理，请稍后继续查询。";notifyChanged();return;}
            save();if(running)recoverNext();else notifyChanged();
        }catch(Exception failure){stop(failure);}});return;}}
        notifyChanged();main.post(this::pump);
    }
    private void pump(){
        if(!running||pumping||recovering)return;pumping=true;
        try{
            if(!engine.ready()){running=false;notifyChanged();return;}
            for(int i=0;i<items().length();i++){JSONObject item=items().optJSONObject(i);String state=item.optString("state");
                if(state.equals("PREPARING"))return;
                if(state.equals("SUBMITTED")){JSONObject receipt=operations.terminalReceipt(item.getString("operation"));if(receipt==null)return;finishItem(item,receipt.getString("state"),receipt.optString("detail"));save();notifyChanged();if(!running)return;}
            }
            if(imports.preparing||operations.busyUnscoped())return;
            for(int i=0;i<items().length();i++){JSONObject item=items().optJSONObject(i);if(!item.optString("state").equals("QUEUED"))continue;
                item.put("state","PREPARING");save();notifyChanged();
                imports.importBatchItem(item.getString("kind"),item.getString("source"),batch.getString("space"),item.getString("operation"),(submitted,error)->{try{if(submitted)item.put("state","SUBMITTED");else finishItem(item,"FAILED",error==null?"没有提交":UiText.error(error));save();notifyChanged();main.post(this::pump);}catch(Exception failure){stop(failure);}});return;
            }
            running=false;notifyChanged();
        }catch(Exception failure){stop(failure);}finally{pumping=false;}
    }
    private void finishItem(JSONObject item,String state,String detail)throws Exception{item.put("state",state).put("error",state.equals("SUCCEEDED")?"":state.equals("UNKNOWN")?"结果尚未确认，请核对应用列表；此项不会自动重试。":UiText.detail(detail));if(state.equals("UNKNOWN"))running=false;}
    private void stop(Exception failure){running=false;problem=UiText.error(failure);notifyChanged();}
    @Override public void changed(){if(running)main.post(this::pump);}
}
