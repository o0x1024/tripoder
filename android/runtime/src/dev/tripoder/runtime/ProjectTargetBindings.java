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
            if(!accepted.created){if(accepted.operation.state!=ExecutionContract.OperationState.SUCCEEDED)throw new IllegalStateException("OPERATION_QUERY_REQUIRED:"+accepted.operation.state);JSONObject res=new JSONObject(accepted.operation.result);return bundle(res,res.optString("mode",RuntimeMode.DEFAULT.wire));}
            broker.contract.start(operation);
        }
        boolean transactionStarted=false;
        try{
            // A project's scripts and selectors are written for one application; a new space or version is a rebinding, another application is a new project.
            synchronized(broker){Row prior=rowIn(project,space);if(prior!=null&&!pkg.equals(prior.pkg))throw new IllegalStateException("PROJECT_PACKAGE_CHANGE_UNSUPPORTED");}
            var install=broker.apks.get(space,pkg);if(!generation.equals(install.generation))throw new IllegalStateException("PROJECT_INSTALL_GENERATION_CONFLICT");
            synchronized(broker){

                var current=broker.apks.row(space,pkg);
                if(current==null||!"COMMITTED".equals(current.state)||!generation.equals(current.generation)||!install.data.getName().equals(current.dataId))throw new IllegalStateException("PROJECT_INSTALL_GENERATION_CONFLICT");
                Row prior=rowIn(project,space);
                if(expectedBinding==null&&prior!=null)throw new IllegalStateException("PROJECT_BINDING_ALREADY_EXISTS");
                if(expectedBinding!=null&&(prior==null||!"DIRECT".equals(prior.kind)||!expectedBinding.equals(prior.operation)))throw new IllegalStateException("PROJECT_BINDING_CONFLICT");
                if(prior!=null&&!pkg.equals(prior.pkg))throw new IllegalStateException("PROJECT_PACKAGE_CHANGE_UNSUPPORTED");
                java.util.List<String> existingModes=new java.util.ArrayList<>();
                for(Row sibling:directCommittedRows(space,pkg))if(!project.equals(sibling.id))existingModes.add(sibling.mode);
                RuntimeMode siblingMode=existingModes.isEmpty()?null:RuntimeMode.resolve(existingModes);
                RuntimeMode chosenMode=prior!=null?RuntimeMode.parse(prior.mode):(siblingMode!=null?siblingMode:RuntimeMode.DEFAULT);
                if(siblingMode!=null&&siblingMode!=chosenMode)throw new IllegalStateException("RUNTIME_MODE_CONFLICT:"+siblingMode.wire+":"+chosenMode.wire);
                String mode=chosenMode.wire;
                if(!new ApplicationProjects(broker).ensure(pkg).equals(project))throw new IllegalStateException("APPLICATION_PROJECT_ALREADY_EXISTS");
                if(prior!=null)broker.hookClaims.requireUnclaimedBinding(project,prior.operation);
                JSONObject result=new JSONObject().put("project",project).put("space",space).put("package",pkg).put("generation",generation).put("dataId",current.dataId).put("bindingOperation",operation).put("mode",mode);
                ContentValues values=new ContentValues();values.put("id",project);values.put("space",space);values.put("package",pkg);values.put("generation",generation);values.put("sourceSha","");values.put("snapshot","");values.put("state","COMMITTED");values.put("operation",operation);values.put("kind","DIRECT");values.put("mode",mode);
                var db=broker.store.getWritableDatabase();transactionStarted=true;db.beginTransaction();
                try{
                    if(prior!=null&&db.delete("project_refs","id=? AND space=? AND operation=? AND kind='DIRECT'",new String[]{project,space,expectedBinding})!=1)throw new IllegalStateException("PROJECT_BINDING_CONFLICT");
                    db.insertOrThrow("project_refs",null,values);broker.contract.succeed(operation,result.toString());db.setTransactionSuccessful();
                }finally{db.endTransaction();}
                return bundle(result,mode);
            }
        }catch(Exception error){
            try{synchronized(broker){if(broker.contract.query(operation).state==ExecutionContract.OperationState.RUNNING){if(transactionStarted)broker.contract.markUnknown(operation,"PROJECT_BINDING_PERSISTENCE_UNCONFIRMED");else broker.contract.fail(operation,"PROJECT_BINDING_REJECTED");}}}catch(Exception journal){error.addSuppressed(journal);}throw error;
        }
    }
    public Bundle setMode(String operation,String caller,String project,String rawMode)throws Exception{return setModeIn(operation,caller,project,null,rawMode);}
    public Bundle setModeIn(String operation,String caller,String project,String space,String rawMode)throws Exception{return setModeIn(operation,caller,project,space,null,rawMode);}
    public Bundle setModeIn(String operation,String caller,String project,String space,String expectedGeneration,String rawMode)throws Exception{
        project(project);RuntimeMode mode=RuntimeMode.parse(rawMode==null?"":rawMode);
        String digest=ApkStore.textDigest(new JSONObject().put("kind","project-mode-v1").put("project",project).put("mode",mode.wire).put("space",space==null?"":space).put("generation",expectedGeneration==null?"":expectedGeneration).toString());
        synchronized(broker){
            var accepted=broker.contract.accept(operation,caller,digest);
            if(!accepted.created){if(accepted.operation.state!=ExecutionContract.OperationState.SUCCEEDED)throw new IllegalStateException("OPERATION_QUERY_REQUIRED:"+accepted.operation.state);JSONObject res=new JSONObject(accepted.operation.result);return bundle(res,res.optString("mode",mode.wire));}
            broker.contract.start(operation);
        }
        boolean transactionStarted=false;
        try{
            synchronized(broker){

                Row prior=space==null?row(project):rowIn(project,space);if(prior==null)throw new IllegalStateException("PROJECT_NOT_BOUND");
                if(expectedGeneration!=null&&!expectedGeneration.equals(prior.generation))throw new IllegalStateException("INSTALL_GENERATION_CONFLICT");
                broker.hookClaims.requireUnclaimedBinding(project,prior.operation);
                if(!"DIRECT".equals(prior.kind))throw new IllegalStateException("PROJECT_BINDING_KIND_MISMATCH");
                if(!"COMMITTED".equals(prior.state))throw new IllegalStateException("PROJECT_BINDING_STALE");
                var current=broker.apks.row(prior.space,prior.pkg);
                if(current==null||!"COMMITTED".equals(current.state)||!prior.generation.equals(current.generation))throw new IllegalStateException("PROJECT_BINDING_STALE");
                for(var slot:broker.processSlots())if(slot.install.space.equals(prior.space)&&slot.install.info.packageName.equals(prior.pkg))throw new IllegalStateException("PROJECT_PROCESS_RUNNING_STOP_FIRST");
                for(Row sibling:directCommittedRows(prior.space,prior.pkg))if(!project.equals(sibling.id)&&RuntimeMode.parse(sibling.mode)!=mode)throw new IllegalStateException("RUNTIME_MODE_CONFLICT:"+RuntimeMode.parse(sibling.mode).wire+":"+mode.wire);
                JSONObject result=new JSONObject().put("project",project).put("space",prior.space).put("package",prior.pkg).put("generation",prior.generation).put("dataId",current.dataId).put("bindingOperation",prior.operation).put("mode",mode.wire);
                ContentValues values=new ContentValues();values.put("mode",mode.wire);
                var db=broker.store.getWritableDatabase();transactionStarted=true;db.beginTransaction();
                try{
                    if(db.update("project_refs",values,"id=? AND operation=? AND kind='DIRECT' AND state='COMMITTED'",new String[]{project,prior.operation})!=1)throw new IllegalStateException("PROJECT_BINDING_CONFLICT");
                    broker.contract.succeed(operation,result.toString());db.setTransactionSuccessful();
                }finally{db.endTransaction();}
                broker.apks.evict(prior.space,prior.pkg);
                return bundle(result,mode.wire);
            }
        }catch(Exception error){
            try{synchronized(broker){if(broker.contract.query(operation).state==ExecutionContract.OperationState.RUNNING){if(transactionStarted)broker.contract.markUnknown(operation,"PROJECT_MODE_PERSISTENCE_UNCONFIRMED");else broker.contract.fail(operation,"PROJECT_MODE_REJECTED");}}}catch(Exception journal){error.addSuppressed(journal);}throw error;
        }
    }
    public Bundle resolve(String project)throws Exception{return resolveRow(project,row(project));}
    public Bundle resolveIn(String project,String space)throws Exception{return resolveRow(project,rowIn(project,space));}
    public Bundle resolveBinding(String project,String operation)throws Exception{
        Row row;synchronized(broker){try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT id,space,package,generation,operation,state,kind,mode FROM project_refs WHERE id=? AND operation=?",new String[]{project,operation})){row=c.moveToFirst()?new Row(c):null;}}
        return resolveRow(project,row);
    }
    private Bundle resolveRow(String project,Row initial)throws Exception{
        project(project);Row row;JSONObject receipt;
        synchronized(broker){
            row=initial;if(row==null)throw new IllegalStateException("PROJECT_NOT_BOUND");
            if(!"DIRECT".equals(row.kind))throw new IllegalStateException("PROJECT_BINDING_KIND_MISMATCH");
            if(!"COMMITTED".equals(row.state))throw new IllegalStateException("PROJECT_BINDING_STALE");
            var operation=broker.contract.query(row.operation);
            if(operation==null||operation.state!=ExecutionContract.OperationState.SUCCEEDED)throw new SecurityException("PROJECT_BINDING_RECEIPT_INVALID");
            receipt=new JSONObject(operation.result);
            if(!project.equals(receipt.getString("project"))||!row.space.equals(receipt.getString("space"))||!row.pkg.equals(receipt.getString("package"))||!row.generation.equals(receipt.getString("generation"))||!row.operation.equals(receipt.getString("bindingOperation")))throw new SecurityException("PROJECT_BINDING_RECEIPT_INVALID");
        }
        var install=broker.apks.get(row.space,row.pkg);
        if(!row.generation.equals(install.generation)||!receipt.getString("dataId").equals(install.data.getName()))throw new IllegalStateException("PROJECT_BINDING_STALE");
        Row latest;synchronized(broker){latest=rowIn(project,row.space);if(latest==null||!Objects.equals(latest.operation,row.operation)||!"COMMITTED".equals(latest.state))throw new IllegalStateException("PROJECT_BINDING_STALE");}
        Bundle out=bundle(receipt,RuntimeMode.parse(latest.mode).wire);
        out.putBundle("integrity",broker.apks.integrityStats());
        return out;
    }
    private Row row(String project){synchronized(broker){
        String selected=new ApplicationProjects(broker).selected(project);Row chosen=rowIn(project,selected);if(chosen!=null)return chosen;
        try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT id,space,package,generation,operation,state,kind,mode FROM project_refs WHERE id=? ORDER BY space LIMIT 1",new String[]{project})){return c.moveToFirst()?new Row(c):null;}
    }}
    private Row rowIn(String project,String space){synchronized(broker){try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT id,space,package,generation,operation,state,kind,mode FROM project_refs WHERE id=? AND space=?",new String[]{project,space})){return c.moveToFirst()?new Row(c):null;}}}
    private java.util.List<Row> directCommittedRows(String space,String pkg){java.util.List<Row> out=new java.util.ArrayList<>();try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT id,space,package,generation,operation,state,kind,mode FROM project_refs WHERE space=? AND package=? AND kind='DIRECT' AND state='COMMITTED' ORDER BY id",new String[]{space,pkg})){while(c.moveToNext())out.add(new Row(c));}return out;}
    private static final class Row {final String id,space,pkg,generation,operation,state,kind,mode;Row(Cursor c){id=c.getString(0);space=c.getString(1);pkg=c.getString(2);generation=c.getString(3);operation=c.getString(4);state=c.getString(5);kind=c.getString(6);mode=c.getString(7);}}
    private static void project(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("PROJECT_ID_INVALID");}
    private static Bundle bundle(JSONObject input,String mode)throws Exception{Bundle out=new Bundle();for(String key:new String[]{"project","space","package","generation","dataId","bindingOperation"})out.putString(key,input.getString(key));out.putString("mode",mode);return out;}
}
