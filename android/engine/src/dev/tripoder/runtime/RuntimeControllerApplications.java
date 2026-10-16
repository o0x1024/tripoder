package dev.tripoder.runtime;

import android.database.Cursor;
import android.os.Bundle;
import dev.tripoder.control.ControlWire;
import java.util.*;
import org.json.JSONObject;

/** Application-level lifecycle does not manufacture, select, or delete a project. */
final class RuntimeControllerApplications {
    static Bundle uninstall(RuntimeBroker broker,Bundle input,String lease,int uid)throws Exception{
        RuntimeControllerCommands.fields(input,"command","operation","space","package","generation");
        String operation=ControlWire.required(input,"operation"),space=ControlWire.required(input,"space"),pkg=ControlWire.required(input,"package"),generation=ControlWire.required(input,"generation");
        String digest=ApkStore.textDigest(new JSONObject().put("kind","controller-uninstall-v1").put("space",space).put("package",pkg).put("generation",generation).toString());
        synchronized(broker){if(!broker.contract.accept(operation,"trusted-workbench",digest).created)throw new IllegalStateException("UNINSTALL_ALREADY_ACCEPTED_QUERY_REQUIRED");broker.contract.start(operation);}
        boolean stopping=false,committing=false;
        try{
            RuntimeControllerService.agents().requireApplicationControl(space,pkg);
            List<String> processes=new ArrayList<>();
            synchronized(broker){
                requireInstall(broker,space,pkg,generation);RuntimeControllerService.requireLease(lease,uid);
                try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT generation FROM sessions WHERE space=? AND package=? AND installGeneration=? AND state NOT IN ('DEAD','FAILED')",new String[]{space,pkg,generation})){while(c.moveToNext())processes.add(c.getString(0));}
            }
            // The explicit deletion confirmation authorizes stopping this installation only.
            for(String process:processes){stopping=true;broker.processes.close(process);stopping=false;}
            RuntimeEmptyTasks.removeApplicationTasks(broker,space,pkg,generation);
            RuntimeControllerService.agents().requireApplicationControl(space,pkg);
            synchronized(broker){
                RuntimeControllerService.requireLease(lease,uid);requireInstall(broker,space,pkg,generation);
                broker.catalog.requireUnused(space,pkg);committing=true;
                broker.apks.uninstallAccepted(operation,space,pkg,generation,ApkStore.NO_FAULT);
            }
            Bundle out=new Bundle();out.putString("generation",generation);return out;
        }catch(Exception error){
            synchronized(broker){if(broker.contract.query(operation).state==ExecutionContract.OperationState.RUNNING){
                if(stopping||committing)broker.contract.markUnknown(operation,"UNINSTALL_UNCONFIRMED:"+error.getMessage());
                else broker.contract.fail(operation,"UNINSTALL_REJECTED");
            }}throw error;
        }
    }
    private static void requireInstall(RuntimeBroker broker,String space,String pkg,String generation){
        broker.catalog.requireSpace(space);RuntimeFiles.id(pkg);RuntimeFiles.id(generation);
        var row=broker.apks.row(space,pkg);if(row==null||!generation.equals(row.generation))throw new IllegalStateException("INSTALL_GENERATION_CONFLICT");
    }
    private RuntimeControllerApplications(){}
}
