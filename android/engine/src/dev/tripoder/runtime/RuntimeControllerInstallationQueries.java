package dev.tripoder.runtime;

import android.os.Bundle;
import dev.tripoder.control.ControlWire;
import org.json.JSONObject;

/** Authority owns installation lookup; no project or live process is selected. */
final class RuntimeControllerInstallationQueries {
    static Bundle processHistory(RuntimeBroker broker,Bundle input)throws Exception{
        RuntimeControllerCommands.fields(input,"command","space","package","generation","cursor","limit");
        String space=ControlWire.required(input,"space"),pkg=ControlWire.required(input,"package"),generation=ControlWire.required(input,"generation");
        RuntimeFiles.id(space);RuntimeFiles.id(pkg);RuntimeFiles.id(generation);int cursor=Integer.parseInt(input.getString("cursor")),limit=Integer.parseInt(input.getString("limit"));
        if(cursor<0||limit<1||limit>64)throw new IllegalArgumentException("PROCESS_HISTORY_PAGE_RANGE");
        org.json.JSONArray rows=new org.json.JSONArray();boolean more=false;
        synchronized(broker){try(var c=broker.store.getReadableDatabase().rawQuery("SELECT generation,state,processName,pid,authorityGeneration FROM sessions WHERE space=? AND package=? AND installGeneration=? ORDER BY generation LIMIT ? OFFSET ?",new String[]{space,pkg,generation,String.valueOf(limit+1),String.valueOf(cursor)})){
            while(c.moveToNext()){if(rows.length()==limit){more=true;break;}rows.put(new JSONObject().put("processGeneration",c.getString(0)).put("state",c.getString(1)).put("processName",c.getString(2)).put("lastKnownPid",c.getInt(3)).put("currentAuthority",broker.store.authorityGeneration.equals(c.getString(4))));}
        }}
        JSONObject result=new JSONObject().put("target",new JSONObject().put("space",space).put("package",pkg).put("installGeneration",generation)).put("processes",rows).put("nextCursor",more?cursor+rows.length():-1)
            .put("source","PERSISTED_PROCESS_SESSION_HISTORY").put("semantics","Historical identity only. A recorded PID/state grants no execution authority; use exact generation fences for live operations.");
        Bundle out=new Bundle();out.putString("result",result.toString());return out;
    }
    static Bundle storage(RuntimeBroker broker,Bundle input)throws Exception{
        RuntimeControllerCommands.fields(input,"command","space","package","generation","category","limit");
        String space=ControlWire.required(input,"space"),pkg=ControlWire.required(input,"package"),generation=ControlWire.required(input,"generation");
        ApkStore.BundleInstall install=broker.apks.get(space,pkg);if(!generation.equals(install.generation))throw new IllegalStateException("INSTALL_GENERATION_CONFLICT");
        JSONObject result=new JSONObject(RuntimeSecurityAudit.inspectStorage(install.data,input.getString("category"),Integer.parseInt(input.getString("limit"))));
        synchronized(broker){var current=broker.apks.row(space,pkg);if(current==null||!generation.equals(current.generation)||!"COMMITTED".equals(current.state))throw new IllegalStateException("INSTALL_GENERATION_CONFLICT");}
        result.put("target",new JSONObject().put("space",space).put("package",pkg).put("installGeneration",generation)).put("consistency","LIVE_FILES_NOT_ATOMIC_SNAPSHOT");Bundle out=new Bundle();out.putString("result",result.toString());return out;
    }
    static Bundle inspect(RuntimeBroker broker,Bundle input)throws Exception{
        RuntimeControllerCommands.fields(input,"command","space","package","generation","cursor","limit");
        String space=ControlWire.required(input,"space"),pkg=ControlWire.required(input,"package"),generation=ControlWire.required(input,"generation");
        ApkStore.BundleInstall install=broker.apks.get(space,pkg);
        if(!generation.equals(install.generation))throw new IllegalStateException("INSTALL_GENERATION_CONFLICT");
        JSONObject identity=new JSONObject().put("space",space).put("package",pkg).put("installGeneration",generation);
        JSONObject result=InstalledManifestAudit.read(install.artifacts.descriptorJson,install.artifacts.descriptorSha,identity,Integer.parseInt(input.getString("cursor")),Integer.parseInt(input.getString("limit")));
        synchronized(broker){var current=broker.apks.row(space,pkg);if(current==null||!generation.equals(current.generation)||!"COMMITTED".equals(current.state))throw new IllegalStateException("INSTALL_GENERATION_CONFLICT");}
        Bundle out=new Bundle();out.putString("result",result.toString());return out;
    }
}
