package dev.tripoder.runtime;

import android.os.Bundle;
import dev.tripoder.control.ControlWire;
import org.json.*;

/** The user's deletion of one project: its target binding goes, the installation and its data stay. */
final class RuntimeControllerProjects {
    /** Refused while a Hook context or an external Agent session still holds the project, so no active Hook loses its only controller. */
    static Bundle delete(RuntimeBroker broker,Bundle input,String lease)throws Exception{
        RuntimeControllerCommands.fields(input,"command","operation","project");
        String operation=ControlWire.required(input,"operation"),project=ControlWire.required(input,"project");RuntimeFiles.id(project);
        RuntimeControllerService.agents().requireUiControl(project,"deleteProject");
        String digest=ApkStore.textDigest(new JSONObject().put("kind","controller-delete-project-v1").put("project",project).toString());
        synchronized(broker){
            if(!broker.contract.accept(operation,ControllerTrust.OPERATIONS,digest).created)throw new IllegalStateException("DELETE_PROJECT_ALREADY_ACCEPTED_QUERY_REQUIRED");broker.contract.start(operation);
            try{
                RuntimeControllerService.requireLease(lease);broker.hookClaims.requireUnclaimedProject(project);
                var db=broker.store.getWritableDatabase();db.beginTransaction();
                try{
                    db.delete("project_refs","id=? AND kind='DIRECT'",new String[]{project});
                    broker.contract.succeed(operation,new JSONObject().put("deletedProjects",new JSONArray().put(project)).toString());
                    db.setTransactionSuccessful();
                }finally{db.endTransaction();}
            }catch(Exception error){if(broker.contract.query(operation).state==ExecutionContract.OperationState.RUNNING)broker.contract.fail(operation,"DELETE_PROJECT_REJECTED:"+error.getMessage());throw error;}
        }
        Bundle out=new Bundle();out.putString("project",project);return out;
    }
    private RuntimeControllerProjects(){}
}
