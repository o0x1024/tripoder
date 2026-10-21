package dev.tripoder.runtime;

import android.database.Cursor;
import android.os.Bundle;
import dev.tripoder.control.ControlWire;
import java.io.File;
import java.util.*;
import org.json.JSONObject;

/** The user's deletion of one space: every application in it is stopped and removed with its data, the projects bound to it go, then the space itself.
 *  The space row goes last, so a retry after a partial deletion finds the space and finishes the remaining steps. */
final class RuntimeControllerSpaces {
    static Bundle delete(RuntimeBroker broker,Bundle input,String lease)throws Exception{
        RuntimeControllerCommands.fields(input,"command","operation","space");
        String operation=ControlWire.required(input,"operation"),space=ControlWire.required(input,"space");RuntimeFiles.id(space);
        String digest=ApkStore.textDigest(new JSONObject().put("kind","controller-delete-space-v1").put("space",space).toString());
        synchronized(broker){if(!broker.contract.accept(operation,ControllerTrust.OPERATIONS,digest).created)throw new IllegalStateException("DELETE_SPACE_ALREADY_ACCEPTED_QUERY_REQUIRED");broker.contract.start(operation);}
        int removed=0;
        try{
            List<String[]> installs=new ArrayList<>();List<String> bound=new ArrayList<>();
            synchronized(broker){
                RuntimeControllerService.requireLease(lease);broker.catalog.requireSpace(space);
                try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT package,generation FROM installs WHERE space=? ORDER BY package",new String[]{space})){while(c.moveToNext())installs.add(new String[]{c.getString(0),c.getString(1)});}
                try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT id FROM project_refs WHERE space=?",new String[]{space})){while(c.moveToNext())bound.add(c.getString(0));}
            }
            for(String project:bound)RuntimeControllerService.agents().requireUiControl(project,"deleteSpace");
            for(String[] install:installs){RuntimeControllerApplications.uninstallOne(broker,operation+"-"+removed,space,install[0],install[1],lease);removed++;}
            org.json.JSONArray projects;
            synchronized(broker){
                var db=broker.store.getWritableDatabase();db.beginTransaction();
                try{
                    RuntimeControllerService.requireLease(lease);
                    // An application added while the others were being removed keeps the space.
                    try(Cursor c=db.rawQuery("SELECT 1 FROM installs WHERE space=? LIMIT 1",new String[]{space})){if(c.moveToFirst())throw new IllegalStateException("SPACE_NOT_EMPTY");}
                    RuntimeControllerApplications.removeBindings(db,"space=?",space);projects=new org.json.JSONArray();
                    if(db.delete("spaces","id=?",new String[]{space})!=1)throw new IllegalStateException("SPACE_NOT_FOUND:"+space);
                    broker.contract.succeed(operation,new JSONObject().put("space",space).put("installs",removed).put("deletedProjects",projects).toString());
                    db.setTransactionSuccessful();
                }finally{db.endTransaction();}
            }
            // Each installation's data went with its uninstall; only the emptied package directories remain, and leaving them is harmless.
            try{File data=new File(broker.apks.root,"data/"+space);if(data.exists())RuntimeFiles.deleteTree(data);}catch(Exception leftover){android.util.Log.w("Tripoder","space directory kept: "+leftover);}
            Bundle out=new Bundle();out.putString("space",space);out.putInt("installs",removed);out.putString("deletedProjects",projects.toString());return out;
        }catch(Exception error){
            synchronized(broker){if(broker.contract.query(operation).state==ExecutionContract.OperationState.RUNNING){
                if(removed>0)broker.contract.markUnknown(operation,"DELETE_SPACE_PARTIAL:"+error.getMessage());
                else broker.contract.fail(operation,"DELETE_SPACE_REJECTED");
            }}throw error;
        }
    }
    private RuntimeControllerSpaces(){}
}
