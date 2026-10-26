package dev.tripoder.runtime;

import android.content.Intent;
import android.os.Bundle;
import java.util.Set;

/** One read-only view of the existing admitted Activity identity. */
final class RuntimeActivityConstruction {
    private static final Set<String> FIELDS=Set.of("generation","ticket","component","delivery");
    static Bundle request(String generation,Intent physical,ClassLoader loader){
        if(physical==null||physical.getClass()!=Intent.class)throw new SecurityException("ACTIVITY_CONSTRUCTION_EXACT_INTENT_REQUIRED");
        // A private copy owns its lazy Bundle loader. Looking up the envelope
        // must neither mutate the platform Intent nor decode its guest payload.
        Intent readable=new Intent(physical);readable.setExtrasClassLoader(loader);
        Bundle request=new Bundle();request.putString("generation",generation);
        for(String key:new String[]{"ticket","component","delivery"}){
            String value=readable.getStringExtra("tripoder."+key);
            if(value==null||value.isEmpty())throw new SecurityException("ACTIVITY_CONSTRUCTION_ENVELOPE_REQUIRED:"+key);
            request.putString(key,value);
        }
        return request;
    }
    static Bundle record(RuntimeBroker broker,Bundle input,int pid,int uid)throws Exception{
        if(uid!=android.os.Process.myUid())throw new SecurityException("CALLER_UID_MISMATCH");
        if(input==null||!input.keySet().equals(FIELDS))throw new SecurityException("ACTIVITY_CONSTRUCTION_REQUEST_FIELDS");
        String generation=RuntimeWire.required(input,"generation"),id=RuntimeWire.required(input,"ticket"),component=RuntimeWire.required(input,"component"),delivery=RuntimeWire.required(input,"delivery");
        synchronized(broker){
            broker.contract.authenticate(generation,pid,uid);var slot=broker.requiredSlot(generation);var install=slot.install;
            var current=broker.apks.row(install.space,install.info.packageName);
            if(current==null||!"COMMITTED".equals(current.state)||!install.generation.equals(current.generation)||!install.data.getName().equals(current.dataId))throw new SecurityException("ACTIVITY_CONSTRUCTION_INSTALL_STALE");
            var ticket=broker.activities.constructionView(id,component,delivery,slot);Bundle result=new Bundle();
            result.putString("stub",broker.activities.get(id).stub);
            if(ticket==null){result.putBoolean("closed",true);return result;}
            var info=RuntimeComponents.activity(install,ticket.component);
            result.putString("component",ticket.component);result.putString("constructionTarget",info.targetActivity==null?info.name:info.targetActivity);
            return result;
        }
    }
    private RuntimeActivityConstruction(){}
}
