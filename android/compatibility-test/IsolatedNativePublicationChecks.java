package dev.tripoder.runtime;

import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.io.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Real installed owned code and signed private fixtures; no guest execution or synthetic PID signals. */
final class IsolatedNativePublicationChecks {
    private final BiConsumer<String,Boolean> check;
    IsolatedNativePublicationChecks(BiConsumer<String,Boolean> check){this.check=check;}
    interface Work{void run()throws Exception;}
    private void denied(String name,Work work)throws Exception{boolean refused=false;try{work.run();}catch(SecurityException|UnsupportedOperationException expected){refused=true;}check.accept(name,refused);}
    private static long count(SQLiteDatabase db,String table){try(var row=db.rawQuery("SELECT COUNT(*) FROM "+table,null)){row.moveToFirst();return row.getLong(0);}}
    private static ApkStore.BundleInstall install(RuntimeBroker broker,Context assets,String file,String space)throws Exception{
        byte[] bytes;try(var input=assets.getAssets().open(file)){bytes=input.readAllBytes();}String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        broker.store.getWritableDatabase().execSQL("INSERT INTO spaces VALUES(?,1)",new Object[]{space});return broker.apks.install(UUID.randomUUID().toString(),space,List.of(new ApkStore.Source("base.apk",sha,()->new ByteArrayInputStream(bytes))),null,ApkStore.NO_FAULT);
    }
    private static void verify(Bundle wire,ApkStore.BundleInstall install)throws Exception{try(var ignored=RuntimePublishedNativeCode.verify(wire,install.space,install.artifacts.packageName,install.generation,install.artifacts.descriptorSha)){} }
    void run(Context target,File scratch,Context assets)throws Exception{
        File root=new File(scratch,"native-publication-models");if(!root.mkdir())throw new IOException("OWNED_NATIVE_MODEL_DIRECTORY_REQUIRED");
        Context owned=new ContextWrapper(target){
            public File getFilesDir(){return root;}
            public File getDatabasePath(String name){return new File(root,name);}
            public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
            public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,android.database.DatabaseErrorHandler handler){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).toString(),factory,handler);}
        };
        RuntimeBroker broker=new RuntimeBroker(owned);
        try{
            var current=install(broker,assets,"native-current.apk","owned-native-current");var stale=install(broker,assets,"native-stale.apk","owned-native-stale");var unpublished=install(broker,assets,"native-unpublished.apk","owned-native-unpublished");
            if(!current.artifacts.packageName.equals("dev.tripoder.isolatedfdprobe")||!stale.artifacts.packageName.equals("dev.tripoder.isolatedfdprobe")||!unpublished.artifacts.packageName.equals("dev.tripoder.isolatedfdpayload"))throw new SecurityException("OWNED_NATIVE_FIXTURE_IDENTITIES_REQUIRED");
            var db=broker.store.getReadableDatabase();long operations=count(db,"operations");
            var plan=RuntimePublishedNativeCode.capture(owned,current);check.accept("native-publication-exact-system-code-from-different-physical-package",plan!=null&&plan.wire.getInt("sourceUid")!=android.os.Process.myUid());
            verify(plan.wire,current);check.accept("native-publication-child-verifies-opened-original-bytes",true);
            Bundle generation=plan.wire.deepCopy();generation.putString("install","wrong-generation");denied("native-publication-cannot-borrow-another-generation",()->verify(generation,current));
            Bundle pkg=plan.wire.deepCopy();pkg.putString("package","dev.owned.foreign");denied("native-publication-cannot-borrow-another-package",()->verify(pkg,current));
            Bundle privateRoot=plan.wire.deepCopy();privateRoot.putString("root",target.getFilesDir().getPath());denied("native-publication-rejects-app-private-code-root",()->verify(privateRoot,current));
            Bundle apkHash=plan.wire.deepCopy();apkHash.getStringArrayList("apkHashes").set(0,"0".repeat(64));denied("native-publication-checks-full-public-apk-hash",()->verify(apkHash,current));
            Bundle nativeHash=plan.wire.deepCopy();nativeHash.getStringArrayList("hashes").set(0,"0".repeat(64));denied("native-publication-checks-native-library-hash",()->verify(nativeHash,current));
            Bundle escape=plan.wire.deepCopy();escape.getStringArrayList("names").set(0,"../foreign.so");denied("native-publication-rejects-library-path-escape",()->verify(escape,current));
            Bundle mode=plan.wire.deepCopy();mode.putString("mode","MEMFD");denied("native-publication-does-not-select-another-execution-source",()->verify(mode,current));
            Bundle directory=plan.wire.deepCopy();directory.putString("nativeDirectory",target.getFilesDir().getPath());denied("native-publication-rejects-private-native-directory",()->verify(directory,current));
            Bundle shape=plan.wire.deepCopy();shape.getStringArrayList("apks").add(shape.getStringArrayList("apks").get(0));denied("native-publication-requires-complete-unambiguous-apk-set",()->verify(shape,current));
            denied("native-publication-stale-system-version-refused-before-allocation",()->broker.isolated.reserve(stale,stale.info.packageName,stale.info.packageName+".IsolatedService","NAMED:STALE"));
            boolean absent=false;try{target.getPackageManager().getPackageInfo(unpublished.info.packageName,0);}catch(android.content.pm.PackageManager.NameNotFoundException expected){absent=true;}check.accept("native-publication-unpublished-negative-fixture-is-not-installed",absent);
            denied("native-publication-no-system-publisher-refused-before-allocation",()->broker.isolated.reserve(unpublished,unpublished.info.packageName,unpublished.info.packageName+".Unused","NAMED:UNPUBLISHED"));
            check.accept("native-publication-rejections-create-no-session-lease-or-business-operation",count(db,"sessions")==0&&count(db,"service_leases")==0&&count(db,"operations")==operations);
        }finally{broker.store.close();}
    }
}
