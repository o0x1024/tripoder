package dev.tripoder.runtime;

import android.content.pm.PackageManager;
import android.os.Bundle;

/** Synchronous same-install state writes and their receipt share one SQLite commit. */
final class RuntimeComponentSettings {
    static Bundle route(RuntimeBroker broker,Bundle input,int pid,int uid)throws Exception{
        synchronized(broker){
            String generation=RuntimeWire.required(input,"generation"),kind=RuntimeWire.required(input,"kind");broker.contract.authenticate(generation,pid,uid);
            var slot=kind.equals("set")?broker.requiredSlot(generation):broker.slotForReceipt(generation);var install=slot.install;
            var current=broker.apks.row(install.space,install.info.packageName);
            if(current==null||!"COMMITTED".equals(current.state)||!current.generation.equals(install.generation)||!current.dataId.equals(install.data.getName()))throw new SecurityException("COMPONENT_INSTALL_STALE");
            var db=broker.store.getWritableDatabase();Bundle out=new Bundle();
            if(kind.equals("snapshot")){if(!input.keySet().equals(java.util.Set.of("generation","kind")))throw new IllegalArgumentException("COMPONENT_SETTINGS_FIELDS_INVALID");out.putBundle("states",ComponentSettingsLedger.snapshot(db,install.space,install.info.packageName,current.dataId));return out;}
            String component=RuntimeWire.required(input,"component");
            // The SDK reads this package's persisted override even when the
            // class has no declaration. Declaration checks belong to writes.
            if(kind.equals("get")){if(!input.keySet().equals(java.util.Set.of("generation","kind","component")))throw new IllegalArgumentException("COMPONENT_SETTINGS_FIELDS_INVALID");out.putInt("state",ComponentSettingsLedger.snapshot(db,install.space,install.info.packageName,current.dataId).getInt(component,0));return out;}
            if(!kind.equals("set"))throw new UnsupportedOperationException("COMPONENT_SETTINGS_OPERATION_UNSUPPORTED");
            boolean declared=false;for(var item:install.artifacts.components)if(item.name.equals(component)){declared=true;break;}
            if(!declared)throw new IllegalArgumentException("COMPONENT_NOT_DECLARED:"+component);
            if(!input.keySet().equals(java.util.Set.of("generation","kind","component","flags","state","operation"))||!(input.get("flags") instanceof Integer)||!(input.get("state") instanceof Integer))throw new IllegalArgumentException("COMPONENT_SETTINGS_FIELDS_INVALID");
            int flags=input.getInt("flags"),state=input.getInt("state");
            if(flags!=0&&flags!=PackageManager.DONT_KILL_APP)throw new UnsupportedOperationException("COMPONENT_STATE_FLAGS_UNSUPPORTED:"+flags);
            if(state<0||state>2)throw new IllegalArgumentException("COMPONENT_STATE_INVALID:"+state);
            String operation=RuntimeWire.required(input,"operation"),digest=ApkStore.textDigest("component-setting-v1\n"+current.dataId+"\n"+component+"\n"+state+"\n"+flags);
            int prior=ComponentSettingsLedger.snapshot(db,install.space,install.info.packageName,current.dataId).getInt(component,0);
            java.util.List<String> restart=java.util.List.of();
            db.beginTransaction();try{
                if(!broker.contract.accept(operation,"pending-install:"+install.generation,digest).created)throw new IllegalStateException("COMPONENT_SETTING_QUERY_REQUIRED");broker.contract.start(operation);
                if(prior!=state){
                    ComponentSettingsLedger.set(db,install.space,install.info.packageName,current.dataId,component,state);
                    if(flags==0)restart=RuntimeComponentRestart.mark(broker,operation,install);
                }
                if(restart.isEmpty())broker.contract.succeed(operation,prior==state?"COMPONENT_SETTING_UNCHANGED":"COMPONENT_SETTING_COMMITTED");db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            Bundle settings=ComponentSettingsLedger.snapshot(db,install.space,install.info.packageName,current.dataId);
            broker.apks.refreshComponentSettings(install.space,install.info.packageName,current.dataId,settings);
            for(var live:broker.processSlots())if(live.install.space.equals(install.space)&&live.install.info.packageName.equals(install.info.packageName)&&live.install.data.getName().equals(current.dataId))live.install.applyComponentSettings(settings);
            for(var live:broker.processSlots())if(restart.contains(live.allocation.session.processGeneration))live.closing=true;
            if(!restart.isEmpty())broker.processes.restartComponents(restart,operation);
            android.util.Log.i("TripoderComponents","committed component="+component+";state="+state+";flags="+flags);return out;
        }
    }
    private RuntimeComponentSettings(){}
}
