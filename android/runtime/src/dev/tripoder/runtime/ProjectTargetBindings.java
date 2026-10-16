package dev.tripoder.runtime;

import android.content.ContentValues;
import android.database.Cursor;
import android.os.Bundle;
import org.json.JSONObject;
import java.util.Objects;

/** Explicit project-to-install references; source files and credentials remain in the workbench UID. */
public final class ProjectTargetBindings {
    private final RuntimeBroker broker;
    public ProjectTargetBindings(RuntimeBroker broker){this.broker=broker;}
    public Bundle bind(String operation,String caller,String project,String space,String pkg,String generation,String expectedBinding)throws Exception{
        project(project);RuntimeFiles.id(space);RuntimeFiles.id(pkg);RuntimeFiles.id(generation);if(expectedBinding!=null)RuntimeFiles.id(expectedBinding);
        String digest=ApkStore.textDigest(new JSONObject().put("kind","direct-binding-v1").put("project",project).put("space",space).put("package",pkg).put("generation",generation).put("expectedBinding",expectedBinding==null?JSONObject.NULL:expectedBinding).toString());
        synchronized(broker){
            var accepted=broker.contract.accept(operation,caller,digest);
            if(!accepted.created){if(accepted.operation.state!=ExecutionContract.OperationState.SUCCEEDED)throw new IllegalStateException("OPERATION_QUERY_REQUIRED:"+accepted.operation.state);return bundle(new JSONObject(accepted.operation.result));}
            broker.contract.start(operation);
        }
        boolean transactionStarted=false;
        try{
            var install=broker.apks.get(space,pkg);if(!generation.equals(install.generation))throw new IllegalStateException("PROJECT_INSTALL_GENERATION_CONFLICT");
            synchronized(broker){
                broker.hookClaims.requireUnclaimedProject(project);
                var current=broker.apks.row(space,pkg);
                if(current==null||!"COMMITTED".equals(current.state)||!generation.equals(current.generation)||!install.data.getName().equals(current.dataId))throw new IllegalStateException("PROJECT_INSTALL_GENERATION_CONFLICT");
                Row prior=row(project);
                if(expectedBinding==null&&prior!=null)throw new IllegalStateException("PROJECT_BINDING_ALREADY_EXISTS");
                if(expectedBinding!=null&&(prior==null||!"DIRECT".equals(prior.kind)||!expectedBinding.equals(prior.operation)))throw new IllegalStateException("PROJECT_BINDING_CONFLICT");
                JSONObject result=new JSONObject().put("project",project).put("space",space).put("package",pkg).put("generation",generation).put("dataId",current.dataId).put("bindingOperation",operation);
                ContentValues values=new ContentValues();values.put("id",project);values.put("space",space);values.put("package",pkg);values.put("generation",generation);values.put("sourceSha","");values.put("snapshot","");values.put("state","COMMITTED");values.put("operation",operation);values.put("kind","DIRECT");
                var db=broker.store.getWritableDatabase();transactionStarted=true;db.beginTransaction();
                try{
                    if(prior!=null&&db.delete("project_refs","id=? AND operation=? AND kind='DIRECT'",new String[]{project,expectedBinding})!=1)throw new IllegalStateException("PROJECT_BINDING_CONFLICT");
                    db.insertOrThrow("project_refs",null,values);broker.contract.succeed(operation,result.toString());db.setTransactionSuccessful();
                }finally{db.endTransaction();}
                return bundle(result);
            }
        }catch(Exception error){
            try{synchronized(broker){if(broker.contract.query(operation).state==ExecutionContract.OperationState.RUNNING){if(transactionStarted)broker.contract.markUnknown(operation,"PROJECT_BINDING_PERSISTENCE_UNCONFIRMED");else broker.contract.fail(operation,"PROJECT_BINDING_REJECTED");}}}catch(Exception journal){error.addSuppressed(journal);}throw error;
        }
    }
    public Bundle resolve(String project)throws Exception{
        project(project);Row row;JSONObject receipt;
        synchronized(broker){
            row=row(project);if(row==null)throw new IllegalStateException("PROJECT_NOT_BOUND");
            if(!"DIRECT".equals(row.kind))throw new IllegalStateException("PROJECT_BINDING_KIND_MISMATCH");
            if(!"COMMITTED".equals(row.state))throw new IllegalStateException("PROJECT_BINDING_STALE");
            var operation=broker.contract.query(row.operation);
            if(operation==null||operation.state!=ExecutionContract.OperationState.SUCCEEDED)throw new SecurityException("PROJECT_BINDING_RECEIPT_INVALID");
            receipt=new JSONObject(operation.result);
            if(!project.equals(receipt.getString("project"))||!row.space.equals(receipt.getString("space"))||!row.pkg.equals(receipt.getString("package"))||!row.generation.equals(receipt.getString("generation"))||!row.operation.equals(receipt.getString("bindingOperation")))throw new SecurityException("PROJECT_BINDING_RECEIPT_INVALID");
        }
        var install=broker.apks.get(row.space,row.pkg);
        if(!row.generation.equals(install.generation)||!receipt.getString("dataId").equals(install.data.getName()))throw new IllegalStateException("PROJECT_BINDING_STALE");
        synchronized(broker){Row latest=row(project);if(latest==null||!Objects.equals(latest.operation,row.operation)||!"COMMITTED".equals(latest.state))throw new IllegalStateException("PROJECT_BINDING_STALE");}
        return bundle(receipt);
    }
    private Row row(String project){try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT space,package,generation,operation,state,kind FROM project_refs WHERE id=?",new String[]{project})){return c.moveToFirst()?new Row(c):null;}}
    private static final class Row {final String space,pkg,generation,operation,state,kind;Row(Cursor c){space=c.getString(0);pkg=c.getString(1);generation=c.getString(2);operation=c.getString(3);state=c.getString(4);kind=c.getString(5);}}
    private static void project(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("PROJECT_ID_INVALID");}
    private static Bundle bundle(JSONObject input)throws Exception{Bundle out=new Bundle();for(String key:new String[]{"project","space","package","generation","dataId","bindingOperation"})out.putString(key,input.getString(key));return out;}
}
