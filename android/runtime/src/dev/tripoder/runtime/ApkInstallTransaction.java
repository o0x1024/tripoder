package dev.tripoder.runtime;

import android.content.ContentValues;
import android.database.Cursor;
import android.system.Os;
import java.io.*;
import java.util.*;

/** Prepare bytes without authority/database locks; publish metadata only after a fresh catalog comparison. */
final class ApkInstallTransaction {
    private final ApkStore apks;
    ApkInstallTransaction(ApkStore apks){this.apks=apks;}
    ApkStore.BundleInstall run(String operation,String caller,String space,List<ApkStore.Source> sources,
            String expected,ApkStore.FaultPoint fault)throws Exception{
        if(sources.isEmpty()||sources.size()>64)throw new IllegalArgumentException("APK_SET_SIZE");
        List<ApkStore.Source> ordered=new ArrayList<>(sources);ordered.sort(Comparator.comparing(source->source.name));
        StringBuilder request=new StringBuilder("install-v2|").append(space).append('|').append(expected==null?"new":"upgrade:"+expected);
        Set<String> names=new HashSet<>();for(var source:ordered){if(!names.add(source.name))throw new IllegalArgumentException("DUPLICATE_APK_NAME");request.append('|').append(source.name).append(':').append(source.sha256);}
        ExecutionContract.Acceptance admission;
        synchronized(apks.authority){
            apks.catalog.requireSpace(space);admission=apks.contract.accept(operation,caller,ApkStore.textDigest(request.toString()));
            if(admission.created)apks.contract.start(operation);
        }
        if(!admission.created){
            if(admission.operation.state!=ExecutionContract.OperationState.SUCCEEDED)throw new IllegalStateException("OPERATION_QUERY_REQUIRED:"+admission.operation.state);
            return apks.byGeneration(space,admission.operation.result);
        }
        String generation=UUID.randomUUID().toString();
        File stage=new File(apks.root,"staging/install-"+generation),destination=new File(apks.root,"artifacts/"+generation);
        apks.retainPaths(stage,destination);boolean metadataStarted=false;
        try{
            fault.at("AFTER_ACCEPT");RuntimeFiles.mkdir(stage);
            long copied=0;
            for(var source:ordered){
                File target=new File(stage,source.name);
                try(InputStream in=source.input.open()){RuntimeFiles.write(in,target,Math.min(ArtifactSet.MAX_APK_BYTES,ArtifactSet.MAX_SET_BYTES-copied));}
                copied+=target.length();if(!ApkStore.digest(target).equals(source.sha256))throw new SecurityException("APK_DIGEST_MISMATCH");
            }
            RuntimeFiles.sync(stage);fault.at("AFTER_COPY");
            ArtifactSet prepared=ArtifactSet.materialize(stage);fault.at("AFTER_VERIFY");
            String pkg=prepared.packageName;ApkStore.Row old;Map<String,String> inventory;
            synchronized(apks.authority){
                apks.catalog.requireSpace(space);apks.catalog.requireUnused(space,pkg);old=apks.row(space,pkg);
                requireGeneration(old,expected);inventory=inventory(space);
            }
            // These can perform full validation of cold historical installations. No Broker lock.
            if(old!=null){
                if(!apks.get(space,pkg).generation.equals(old.generation))throw new IllegalStateException("INSTALL_GENERATION_CONFLICT");
                if(!old.signer.equals(prepared.signerSha))throw new SecurityException("UPGRADE_SIGNER_MISMATCH");
                if(prepared.versionCode<=old.version)throw new IllegalArgumentException("UPGRADE_VERSION_NOT_NEWER");
            }
            apks.requireProviderAuthorities(space,prepared);
            RuntimeFiles.mkdir(destination.getParentFile());
            Os.chmod(stage.toString(),0700);Os.rename(stage.toString(),destination.toString());Os.chmod(destination.toString(),0555);
            RuntimeFiles.sync(destination);RuntimeFiles.sync(stage.getParentFile());RuntimeFiles.sync(destination.getParentFile());fault.at("AFTER_PUBLISH");
            ArtifactSet published=apks.integrity.get(destination,prepared.descriptorSha);
            String dataId=old==null?UUID.randomUUID().toString():old.dataId;
            ApkStore.BundleInstall result=apks.cacheVerified(space,generation,dataId,published);
            synchronized(apks.authority){
                apks.catalog.requireSpace(space);apks.catalog.requireUnused(space,pkg);
                requireGeneration(apks.row(space,pkg),expected);
                if(!inventory.equals(inventory(space)))throw new IllegalStateException("INSTALL_CATALOG_CHANGED");
                var db=apks.store.getWritableDatabase();metadataStarted=true;db.beginTransaction();
                try{
                    ContentValues values=new ContentValues();values.put("space",space);values.put("package",pkg);values.put("generation",generation);
                    values.put("apk",published.base.toString());values.put("sha256",published.parts.get(0).sha256);values.put("descriptorSha",published.descriptorSha);
                    values.put("state","COMMITTED");values.put("reason","");values.put("version",published.versionCode);values.put("signer",published.signerSha);values.put("dataId",dataId);values.put("operation",operation);
                    if(old!=null){apks.catalog.invalidateProjects(space,pkg);db.delete("installs","space=? AND package=?",new String[]{space,pkg});}
                    db.insertOrThrow("installs",null,values);
                    InstallProviderIndex.publish(db,space,generation,InstallProviderIndex.authorities(published));fault.at("AFTER_METADATA");
                    apks.contract.succeed(operation,generation);InstallHistory.committed(apks.store,apks.contract,generation,old==null?null:old.generation,operation);fault.at("BEFORE_COMMIT");db.setTransactionSuccessful();
                }finally{db.endTransaction();}
            }
            fault.at("AFTER_COMMIT");apks.invalidate(space,pkg);synchronized(apks.authority){InstallHistory.resolve(apks.store,apks.contract,result);}return result;
        }catch(Exception error){
            try{synchronized(apks.authority){if(apks.contract.query(operation).state==ExecutionContract.OperationState.RUNNING){
                if(metadataStarted)apks.contract.markUnknown(operation,"INSTALL_PERSISTENCE_UNCONFIRMED");else apks.contract.fail(operation,"INSTALL_REJECTED");
            }}}catch(Exception journal){error.addSuppressed(journal);}throw error;
        }finally{
            try{if(stage.exists())RuntimeFiles.deleteTree(stage);}finally{apks.releasePaths(stage,destination);}
        }
    }
    /** A concurrent publication, migration or corruption invalidates the complete Provider collision check. */
    private Map<String,String> inventory(String space){
        Map<String,String> result=new TreeMap<>();
        try(Cursor c=apks.store.getReadableDatabase().rawQuery("SELECT package,generation,dataId,state,descriptorSha FROM installs WHERE space=? ORDER BY package",new String[]{space})){
            while(c.moveToNext())result.put(c.getString(0),c.getString(1)+"|"+c.getString(2)+"|"+c.getString(3)+"|"+c.getString(4));
        }return result;
    }
    private static void requireGeneration(ApkStore.Row old,String expected){
        if(expected==null&&old!=null)throw new IllegalStateException("INSTALL_ALREADY_EXISTS");
        if(expected!=null&&(old==null||!expected.equals(old.generation)))throw new IllegalStateException("INSTALL_GENERATION_CONFLICT");
    }
}
