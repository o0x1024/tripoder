package dev.tripoder.hooklab;

import android.content.Context;
import android.content.SharedPreferences;
import dev.tripoder.control.ControlWire;
import java.util.UUID;

/** Display names for spaces. The engine knows only identifiers; people see names, and a first space is made for them. */
final class WorkbenchSpaces {
    static final String DEFAULT="default";
    private final SharedPreferences names;private boolean defaultRequested;
    WorkbenchSpaces(Context host){names=host.getSharedPreferences("space-names",Context.MODE_PRIVATE);}
    String name(String id){if(id==null||id.isEmpty())return "";return names.getString(id,DEFAULT.equals(id)?"默认空间":id);}
    void rename(String id,String name){if(!names.edit().putString(id,name.trim()).commit())throw new IllegalStateException("空间名称保存失败");}
    /** After the engine deleted a space: its name and any application's remembered default pointing at it go. */
    static void forget(Context host,String id){
        host.getSharedPreferences("space-names",Context.MODE_PRIVATE).edit().remove(id).apply();
        SharedPreferences launches=host.getSharedPreferences("application-launch",Context.MODE_PRIVATE);var edit=launches.edit();
        for(var entry:launches.getAll().entrySet())if(id.equals(entry.getValue()))edit.remove(entry.getKey());edit.apply();
    }
    /** Identifier for a new named space; names may be any text, identifiers stay within the engine's alphabet. */
    String create(WorkbenchOperations operations,String name)throws Exception{
        String id="space-"+UUID.randomUUID().toString().substring(0,8);
        if(!names.edit().putString(id,name.trim()).commit())throw new IllegalStateException("空间名称保存失败，请重试");
        operations.submit(ControlWire.request("createSpace","space",id,"operation",UUID.randomUUID().toString()),"创建空间「"+name.trim()+"」",null);return id;
    }
    /** Without any space nothing can be added, so the first one is created quietly instead of asking the user to understand spaces first. */
    void ensureDefault(WorkbenchCatalog catalog,WorkbenchOperations operations,EngineConnection engine){
        if(defaultRequested||catalog==null||!catalog.spaces.isEmpty()||!engine.ready()||operations.busyUnscoped())return;
        defaultRequested=true;
        try{operations.submit(ControlWire.request("createSpace","space",DEFAULT,"operation",UUID.randomUUID().toString()),"准备默认空间",null);}
        catch(Exception error){defaultRequested=false;operations.inform("默认空间没有创建："+UiText.error(error));}
    }
}
