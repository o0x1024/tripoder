package dev.tripoder.runtime;

import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.system.Os;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Complete immutable APK sets; publication and metadata are separate explicit durability steps. */
public final class ApkStore {
    public interface Input { InputStream open() throws Exception; }
    public interface FaultPoint { void at(String point) throws Exception; }
    public static final FaultPoint NO_FAULT = point -> {};
    public static final class Source {
        public final String name, sha256; public final Input input;
        public Source(String name,String sha256,Input input){
            RuntimeFiles.id(name);if(!name.endsWith(".apk")||sha256==null||!sha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("INVALID_APK_SOURCE");
            this.name=name;this.sha256=sha256;this.input=Objects.requireNonNull(input);
        }
    }
    public final Context host;
    final RuntimeStore store;
    final RuntimeCatalog catalog;
    final ExecutionContract contract;
    final File root;
    final Object authority;
    final ArtifactIntegrityCache integrity=new ArtifactIntegrityCache();
    private final Set<File> activePaths=new HashSet<>();
    public android.os.Bundle integrityStats(){return integrity.stats();}
    ApkStore(Context host,RuntimeStore store,RuntimeCatalog catalog,ExecutionContract contract,Object authority){this.host=host;this.store=store;this.catalog=catalog;this.contract=contract;this.authority=authority;root=new File(host.getFilesDir(),"runtime");}
    public static String digest(File file)throws Exception {MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}return HexFormat.of().formatHex(md.digest());}
    public static String textDigest(String text)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    public Source asset(String asset,String sha){return new Source(new File(asset).getName(),sha,()->host.getAssets().open(asset));}
    public BundleInstall install(String space,String asset,String expected)throws Exception {
        return install(UUID.randomUUID().toString(),space,List.of(asset(asset,expected)),null,NO_FAULT);
    }
    /** expectedGeneration=null means new install, otherwise compare-and-swap upgrade. */
    public BundleInstall install(String operation,String space,List<Source> sources,String expectedGeneration,FaultPoint fault)throws Exception {
        return installForCaller(operation,"runtime-controller",space,sources,expectedGeneration,fault);
    }
    public BundleInstall installForCaller(String operation,String caller,String space,List<Source> sources,String expectedGeneration,FaultPoint fault)throws Exception {
        return new ApkInstallTransaction(this).run(operation,caller,space,sources,expectedGeneration,fault);
    }
    BundleInstall byGeneration(String space,String generation)throws Exception {
        String pkg;synchronized(authority){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT package FROM installs WHERE space=? AND generation=?",new String[]{space,generation})){if(!c.moveToFirst())throw new IllegalStateException("OPERATION_INSTALL_NO_LONGER_CURRENT");pkg=c.getString(0);}}
        BundleInstall install=get(space,pkg);if(!generation.equals(install.generation))throw new IllegalStateException("OPERATION_INSTALL_NO_LONGER_CURRENT");return install;
    }
    void requireProviderAuthorities(String space,ArtifactSet set)throws Exception{
        Set<String> authorities=new HashSet<>();
        for(var c:set.components)if(c.type.equals("provider"))for(String alias:RuntimeProviderBroker.aliases(c.authorities))if(!authorities.add(alias))throw new IllegalStateException("PROVIDER_AUTHORITY_CONFLICT");
        if(authorities.isEmpty())return;
        try(Cursor cursor=store.getReadableDatabase().rawQuery("SELECT package FROM installs WHERE space=? AND package!=?",new String[]{space,set.packageName})){
            while(cursor.moveToNext())for(var c:get(space,cursor.getString(0)).artifacts.components)if(c.type.equals("provider"))for(String alias:RuntimeProviderBroker.aliases(c.authorities))if(authorities.contains(alias))throw new IllegalStateException("PROVIDER_AUTHORITY_CONFLICT");
        }
    }
    public BundleInstall get(String space,String pkg)throws Exception {
        Row row;synchronized(authority){catalog.requireSpace(space);RuntimeFiles.id(pkg);row=row(space,pkg);}
        if(row==null)throw new ActivityNotFoundException("PACKAGE_NOT_IN_SPACE:"+pkg);
        if(!"COMMITTED".equals(row.state))throw new SecurityException("INSTALL_CORRUPT:"+row.reason);
        try{
            File directory=new File(root,"artifacts/"+RuntimeFiles.id(row.generation));ArtifactSet set=integrity.get(directory,row.descriptorSha);
            if(!set.packageName.equals(pkg)||!set.signerSha.equals(row.signer)||set.versionCode!=row.version||!set.base.equals(new File(row.apk))||!set.parts.get(0).sha256.equals(row.sha))throw new SecurityException("INSTALL_METADATA_MISMATCH");
            BundleInstall install=verified(space,row.generation,row.dataId,set);
            synchronized(authority){Row current=row(space,pkg);if(current==null||!row.generation.equals(current.generation)||!row.dataId.equals(current.dataId)||!"COMMITTED".equals(current.state))throw new IllegalStateException("STALE_INSTALL");InstallHistory.resolve(store,contract,install);}
            return install;
        }catch(Exception error){synchronized(authority){ContentValues v=new ContentValues();v.put("state","CORRUPT");v.put("reason",error.getClass().getSimpleName()+":"+String.valueOf(error.getMessage()));store.getWritableDatabase().update("installs",v,"space=? AND package=? AND generation=? AND dataId=? AND state='COMMITTED'",new String[]{space,pkg,row.generation,row.dataId});}throw error;}
    }
    BundleInstall verified(String space,String generation,String dataId,ArtifactSet set){
        PackageInfo info=host.getPackageManager().getPackageArchiveInfo(set.base.toString(),PackageManager.GET_ACTIVITIES|PackageManager.GET_SERVICES|PackageManager.GET_PROVIDERS|PackageManager.GET_RECEIVERS|PackageManager.GET_SIGNING_CERTIFICATES|PackageManager.GET_PERMISSIONS|PackageManager.MATCH_DISABLED_COMPONENTS);
        if(info==null||!set.packageName.equals(info.packageName))throw new SecurityException("BASE_PLATFORM_PARSE_FAILED");
        return new BundleInstall(space,generation,set.base,set.parts.get(0).sha256,info,new File(root,"data/"+space+"/"+set.packageName+"/"+RuntimeFiles.id(dataId)),set);
    }
    synchronized void retainPaths(File stage,File destination){activePaths.add(stage);activePaths.add(destination);}
    synchronized void releasePaths(File stage,File destination){activePaths.remove(stage);activePaths.remove(destination);}
    public synchronized void uninstall(String operation,String space,String pkg,String expectedGeneration,FaultPoint fault)throws Exception{
        catalog.requireSpace(space);RuntimeFiles.id(pkg);RuntimeFiles.id(expectedGeneration);catalog.requireUnused(space,pkg);
        var admission=contract.accept(operation,"runtime-controller",textDigest("uninstall|"+space+"|"+pkg+"|"+expectedGeneration));
        if(!admission.created){if(admission.operation.state!=ExecutionContract.OperationState.SUCCEEDED)throw new IllegalStateException("OPERATION_QUERY_REQUIRED:"+admission.operation.state);return;}
        contract.start(operation);uninstallAccepted(operation,space,pkg,expectedGeneration,fault);
    }
    /** Caller owns admission and termination. Removal still requires every durable reference to be released. */
    synchronized void uninstallAccepted(String operation,String space,String pkg,String expectedGeneration,FaultPoint fault)throws Exception{
        catalog.requireSpace(space);RuntimeFiles.id(pkg);RuntimeFiles.id(expectedGeneration);catalog.requireUnused(space,pkg);
        if(contract.query(operation).state!=ExecutionContract.OperationState.RUNNING)throw new IllegalStateException("UNINSTALL_OPERATION_NOT_RUNNING");
        Row old=row(space,pkg);if(old==null||!old.generation.equals(expectedGeneration)){contract.fail(operation,"INSTALL_GENERATION_CONFLICT");throw new IllegalStateException("INSTALL_GENERATION_CONFLICT");}
        var db=store.getWritableDatabase();db.beginTransaction();
        try{catalog.invalidateProjects(space,pkg);db.delete("installs","space=? AND package=?",new String[]{space,pkg});fault.at("UNINSTALL_BEFORE_COMMIT");contract.succeed(operation,expectedGeneration);db.setTransactionSuccessful();}finally{db.endTransaction();}
        fault.at("UNINSTALL_AFTER_COMMIT");recover();
    }
    synchronized void removeRecord(String space,String pkg){try{Row row=row(space,pkg);if(row==null)throw new ActivityNotFoundException("PACKAGE_NOT_IN_SPACE:"+pkg);uninstall(UUID.randomUUID().toString(),space,pkg,row.generation,NO_FAULT);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}}
    /** Called only with exclusive authority and no surviving sessions from an old controller. */
    public synchronized void recover()throws Exception{
        for(File stage:RuntimeFiles.children(new File(root,"staging")))if(!activePaths.contains(stage))RuntimeFiles.deleteTree(stage);
        Set<String> referenced=new HashSet<>();
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT generation FROM installs",null)){while(c.moveToNext())referenced.add(c.getString(0));}
        for(File artifact:RuntimeFiles.children(new File(root,"artifacts")))if(!referenced.contains(artifact.getName())&&!activePaths.contains(artifact))RuntimeFiles.deleteTree(artifact);
        // COMMITTED describes the durable publication, not trust in bytes after a restart.
        // Every artifact consumer enters get(), whose fresh process-local integrity cache
        // requires a full verification before first use. Unused historical APKs need no I/O.
        // Data from uninstalled generations is removed only after its metadata reference is gone.
        Set<String> data=new HashSet<>();try(Cursor c=store.getReadableDatabase().rawQuery("SELECT space,package,dataId FROM installs",null)){while(c.moveToNext())data.add(c.getString(0)+"/"+c.getString(1)+"/"+c.getString(2));}
        File dataRoot=new File(root,"data");for(File space:RuntimeFiles.children(dataRoot))for(File pkg:RuntimeFiles.children(space))for(File incarnation:RuntimeFiles.children(pkg))if(!data.contains(space.getName()+"/"+pkg.getName()+"/"+incarnation.getName()))RuntimeFiles.deleteTree(incarnation);
        Set<String> incarnations=new HashSet<>();for(String owner:data)incarnations.add(owner.substring(owner.lastIndexOf('/')+1));RuntimeNotificationNamespace.recover(host,incarnations);
    }
    Row row(String space,String pkg){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT generation,apk,sha256,descriptorSha,state,reason,version,signer,dataId FROM installs WHERE space=? AND package=?",new String[]{space,pkg})){return c.moveToFirst()?new Row(c):null;}}
    static final class Row {
        final String generation,apk,sha,descriptorSha,state,reason,signer,dataId;final long version;
        Row(Cursor c){generation=c.getString(0);apk=c.getString(1);sha=c.getString(2);descriptorSha=c.getString(3);state=c.getString(4);reason=c.getString(5);version=c.getLong(6);signer=c.getString(7);dataId=c.getString(8);}
    }
    public static final class BundleInstall {
        public final String space,generation,sha256;public final File apk,data;public final PackageInfo info;public final ArtifactSet artifacts;
        private java.util.Map<String,android.content.pm.ActivityInfo> activities;
        private List<ApkManifest.Component> effectiveComponents;
        public synchronized List<ApkManifest.Component> components(){if(effectiveComponents==null)effectiveComponents=RuntimeManifestIndex.resolve(artifacts,info);return effectiveComponents;}
        BundleInstall(String space,String generation,File apk,String sha,PackageInfo info,File data,ArtifactSet artifacts){this.space=space;this.generation=generation;this.apk=apk;sha256=sha;this.info=info;this.data=data;this.artifacts=artifacts;}
        synchronized java.util.Map<String,android.content.pm.ActivityInfo> activityIndex(){RuntimeComponents.requireExecutable(this);if(activities==null)activities=RuntimeActivityIndex.create(artifacts,info);return activities;}
        public android.os.Bundle wire(){
            RuntimeComponents.requireExecutable(this);
            android.os.Bundle b=new android.os.Bundle();b.putString("space",space);b.putString("package",info.packageName);b.putString("installGeneration",generation);b.putString("apk",apk.toString());b.putString("sha256",sha256);b.putString("descriptorSha",artifacts.descriptorSha);b.putString("data",data.toString());b.putString("dataId",data.getName());b.putString("application",artifacts.baseManifest.applicationName.isEmpty()?"android.app.Application":artifacts.baseManifest.applicationName);b.putLong("firstInstallTime",info.firstInstallTime);b.putLong("lastUpdateTime",info.lastUpdateTime);return b;
        }
    }
}
