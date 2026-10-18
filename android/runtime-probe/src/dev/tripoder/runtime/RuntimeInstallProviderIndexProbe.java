package dev.tripoder.runtime;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/** Real SQLite and descriptor checks, packaged only in the owned acceptance APK. */
public final class RuntimeInstallProviderIndexProbe {
    public static JSONObject run(Context context)throws Exception{
        JSONArray checks=new JSONArray();File directory=new File(context.getCacheDir(),"provider-index-"+UUID.randomUUID());
        if(!directory.mkdir())throw new IllegalStateException("PROBE_DIRECTORY_FAILED");
        try(SQLiteDatabase db=SQLiteDatabase.create(null)){
            db.setForeignKeyConstraintsEnabled(true);
            db.execSQL("CREATE TABLE installs(space TEXT NOT NULL,package TEXT NOT NULL,generation TEXT UNIQUE NOT NULL,apk TEXT,descriptorSha TEXT,PRIMARY KEY(space,package))");
            String descriptor="{\"schema\":1,\"packageName\":\"owned.one\",\"components\":[{\"type\":\"provider\",\"authorities\":\"owned.first;owned.alias\",\"enabled\":false},{\"type\":\"provider\",\"authorities\":\"owned.first\"}],\"parts\":[{\"entries\":[{\"irrelevant\":true}]}]}";
            File file=new File(directory,ArtifactSet.DESCRIPTOR);Files.writeString(file.toPath(),descriptor);String digest=ApkStore.digest(file);
            insert(db,"space-a","owned.one","generation-one",new File(directory,"missing.apk"),digest);
            db.beginTransaction();try{InstallProviderIndex.migrate(db);db.setTransactionSuccessful();}finally{db.endTransaction();}
            check(checks,"migration-uses-authenticated-descriptor-without-opening-apk",!new File(directory,"missing.apk").exists()&&owner(db,"space-a","owned.alias","owned.one","generation-one"));
            check(checks,"duplicate-declarations-collapse-and-disabled-authorities-reserved",count(db)==2);
            check(checks,"lookup-is-space-bound-and-absent-is-null",InstallProviderIndex.find(db,"space-b","owned.first")==null&&InstallProviderIndex.find(db,"space-a","owned.absent")==null);
            InstallProviderIndex.requireAvailable(db,"space-a","owned.one",Set.of("owned.first"));
            check(checks,"same-package-upgrade-can-retain-authority",true);
            boolean conflict=false;try{InstallProviderIndex.requireAvailable(db,"space-a","owned.two",Set.of("owned.first"));}catch(IllegalStateException e){conflict="PROVIDER_AUTHORITY_CONFLICT".equals(e.getMessage());}
            check(checks,"cross-package-authority-conflict-rejected",conflict);
            insert(db,"space-b","owned.two","generation-two",null,null);InstallProviderIndex.publish(db,"space-b","generation-two",Set.of("owned.first"));
            check(checks,"same-authority-in-another-space-is-independent",owner(db,"space-b","owned.first","owned.two","generation-two"));
            var before=InstallProviderIndex.find(db,"space-a","owned.first");
            db.beginTransaction();try{db.delete("installs","generation=?",new String[]{"generation-one"});insert(db,"space-a","owned.one","rollback",null,null);InstallProviderIndex.publish(db,"space-a","rollback",Set.of("owned.new"));}finally{db.endTransaction();}
            check(checks,"failed-upgrade-rolls-back-install-and-index",before.same(InstallProviderIndex.find(db,"space-a","owned.first"))&&InstallProviderIndex.find(db,"space-a","owned.new")==null);
            db.beginTransaction();try{db.delete("installs","generation=?",new String[]{"generation-one"});insert(db,"space-a","owned.one","generation-new",null,null);InstallProviderIndex.publish(db,"space-a","generation-new",Set.of("owned.first","owned.new"));db.setTransactionSuccessful();}finally{db.endTransaction();}
            check(checks,"upgrade-replaces-aliases-and-invalidates-snapshot",!before.same(InstallProviderIndex.find(db,"space-a","owned.first"))&&InstallProviderIndex.find(db,"space-a","owned.alias")==null&&owner(db,"space-a","owned.new","owned.one","generation-new"));
            db.delete("installs","generation=?",new String[]{"generation-new"});
            check(checks,"uninstall-cascades-only-its-generation",InstallProviderIndex.find(db,"space-a","owned.first")==null&&owner(db,"space-b","owned.first","owned.two","generation-two"));
            Files.writeString(file.toPath(),descriptor+" ");boolean rejected=false;
            try{InstallProviderIndex.readDescriptor(file,"owned.one",digest);}catch(SecurityException e){rejected=e.getMessage().equals("PROVIDER_INDEX_DESCRIPTOR_DIGEST_MISMATCH");}
            check(checks,"modified-descriptor-rejected",rejected);
            rejected=false;try{InstallProviderIndex.readDescriptor(file,"owned.other",ApkStore.digest(file));}catch(SecurityException e){rejected=e.getMessage().equals("PROVIDER_INDEX_DESCRIPTOR_METADATA_MISMATCH");}
            check(checks,"descriptor-package-mismatch-rejected",rejected);
            // Reproduce a failed upgrade transaction: the old database must remain usable.
            db.execSQL("DROP TABLE install_provider_authorities");
            db.execSQL("UPDATE installs SET apk=?,descriptorSha=?",new Object[]{new File(directory,"missing.apk").toString(),digest});
            rejected=false;db.beginTransaction();try{InstallProviderIndex.migrate(db);db.setTransactionSuccessful();}catch(IllegalStateException e){rejected=e.getMessage().equals("PROVIDER_INDEX_MIGRATION_FAILED");}finally{db.endTransaction();}
            try(var c=db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='install_provider_authorities'",null)){c.moveToFirst();check(checks,"failed-migration-rolls-back-schema",rejected&&c.getInt(0)==0);}
        }finally{RuntimeFiles.deleteTree(directory);}
        return new JSONObject().put("checks",checks).put("passed",true);
    }
    public static Bundle isolateUnrelated(RuntimeBroker broker,String space)throws Exception{
        synchronized(broker){
            // This package is installed by this test, never a user application.
            var row=broker.apks.row(space,"dev.tripoder.provideridentity");if(row==null||!space.contains("providers-"))throw new IllegalStateException("PROBE_UNRELATED_FIXTURE_REQUIRED");
            android.content.ContentValues values=new android.content.ContentValues();values.put("state","CORRUPT");values.put("reason","PROBE_UNRELATED_INSTALL");
            broker.store.getWritableDatabase().update("installs",values,"space=? AND generation=?",new String[]{space,row.generation});
            // Evict only this acceptance authority's cache before the cold lookup.
            synchronized(broker.apks.integrity){
                var entries=ArtifactIntegrityCache.class.getDeclaredField("entries");entries.setAccessible(true);((Map<?,?>)entries.get(broker.apks.integrity)).clear();
                var bytes=ArtifactIntegrityCache.class.getDeclaredField("bytes");bytes.setAccessible(true);bytes.setLong(broker.apks.integrity,0);
            }
            Bundle result=broker.apks.integrityStats();result.putString("generation",row.generation);return result;
        }
    }
    private static void insert(SQLiteDatabase db,String space,String pkg,String generation,File apk,String digest){db.execSQL("INSERT INTO installs(space,package,generation,apk,descriptorSha) VALUES(?,?,?,?,?)",new Object[]{space,pkg,generation,apk==null?null:apk.toString(),digest});}
    private static boolean owner(SQLiteDatabase db,String space,String authority,String pkg,String generation){var found=InstallProviderIndex.find(db,space,authority);return found!=null&&pkg.equals(found.pkg)&&generation.equals(found.generation);}
    private static int count(SQLiteDatabase db){try(var c=db.rawQuery("SELECT COUNT(*) FROM install_provider_authorities",null)){c.moveToFirst();return c.getInt(0);}}
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    private RuntimeInstallProviderIndexProbe(){}
}
