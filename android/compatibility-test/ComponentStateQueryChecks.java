package dev.tripoder.runtime;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Real SQLite and authenticated broker route; synthetic PID, no physical component write. */
final class ComponentStateQueryChecks {
    private final BiConsumer<String,Boolean> check;
    ComponentStateQueryChecks(BiConsumer<String,Boolean> check){this.check=check;}
    private interface Request {void run()throws Exception;}
    private void refused(String name,String code,Request body)throws Exception{
        boolean denied=false;
        try{body.run();}catch(Exception error){denied=error instanceof ExecutionContract.ContractException?code.equals(((ExecutionContract.ContractException)error).code):code.equals(error.getMessage());}
        check.accept("component-query-model-"+name,denied);
    }
    private static Bundle request(String generation,String component){Bundle value=new Bundle();value.putString("generation",generation);value.putString("kind","get");value.putString("component",component);return value;}
    private static long count(android.database.sqlite.SQLiteDatabase db,String table){try(var rows=db.rawQuery("SELECT COUNT(*) FROM "+table,null)){rows.moveToFirst();return rows.getLong(0);}}
    void run(Context owned,Context assets)throws Exception{
        RuntimeBroker broker=new RuntimeBroker(owned);
        try{
            File stage=new File(owned.getFilesDir(),"component-query-apk");if(!stage.mkdir())throw new IllegalStateException("COMPONENT_QUERY_STAGE");
            try(var input=assets.getAssets().open("aliases.apk")){Files.copy(input,new File(stage,"base.apk").toPath());}
            ArtifactSet artifacts=ArtifactSet.materialize(stage);
            var info=owned.getPackageManager().getPackageArchiveInfo(artifacts.base.toString(),PackageManager.GET_ACTIVITIES|PackageManager.MATCH_DISABLED_COMPONENTS);
            var install=new ApkStore.BundleInstall("owned-component-query",UUID.randomUUID().toString(),artifacts.base,artifacts.parts.get(0).sha256,info,new File(stage,"data"),artifacts);
            var db=broker.store.getWritableDatabase();db.execSQL("INSERT INTO spaces VALUES(?,1)",new Object[]{install.space});
            db.execSQL("INSERT INTO installs VALUES(?,?,?,?,?,?,'COMMITTED','',1,'owned',?,'owned-import')",new Object[]{install.space,info.packageName,install.generation,artifacts.base.toString(),artifacts.parts.get(0).sha256,artifacts.descriptorSha,install.data.getName()});
            int pid=280001,uid=android.os.Process.myUid();var allocation=broker.contract.allocate(install.space,info.packageName,install.generation,info.packageName,uid);
            String generation=allocation.session.processGeneration,stub=RuntimeSlot.activity(7).getName();broker.store.reserve(allocation.session,stub);broker.store.session(broker.contract.attach(allocation.bootstrapToken,pid,uid));
            var field=RuntimeBroker.class.getDeclaredField("slots");field.setAccessible(true);((Map<String,RuntimeBroker.Slot>)field.get(broker)).put(generation,new RuntimeBroker.Slot(install,allocation,stub,null));
            String absent=info.packageName+".OwnedUndeclaredComponentProbe",declared=info.packageName+".Main";Bundle query=request(generation,absent);
            long operations=count(db,"operations"),settings=count(db,"component_settings");
            check.accept("component-query-model-undeclared-class-returns-native-default",RuntimeComponentSettings.route(broker,query,pid,uid).getInt("state")==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
            Bundle malformed=new Bundle(query);malformed.putString("unexpected","owned");refused("unexpected-field-rejected","COMPONENT_SETTINGS_FIELDS_INVALID",()->RuntimeComponentSettings.route(broker,malformed,pid,uid));
            refused("wrong-pid-rejected","CALLER_MISMATCH",()->RuntimeComponentSettings.route(broker,query,pid+1,uid));
            refused("wrong-uid-rejected","CALLER_MISMATCH",()->RuntimeComponentSettings.route(broker,query,pid,uid+1));
            Bundle write=new Bundle(query);write.putString("kind","set");write.putInt("flags",PackageManager.DONT_KILL_APP);write.putInt("state",2);write.putString("operation","owned-undeclared-write");
            refused("undeclared-write-stays-rejected","COMPONENT_NOT_DECLARED:"+absent,()->RuntimeComponentSettings.route(broker,write,pid,uid));
            check.accept("component-query-model-read-and-rejected-write-create-no-state-or-operation",count(db,"operations")==operations&&count(db,"component_settings")==settings);
            ComponentSettingsLedger.set(db,install.space,info.packageName,install.data.getName(),declared,2);
            check.accept("component-query-model-declared-state-keeps-persisted-value",RuntimeComponentSettings.route(broker,request(generation,declared),pid,uid).getInt("state")==2);
            db.execSQL("UPDATE installs SET generation='owned-replaced-install' WHERE generation=?",new Object[]{install.generation});
            refused("replaced-install-cannot-read-old-state","COMPONENT_INSTALL_STALE",()->RuntimeComponentSettings.route(broker,query,pid,uid));
            db.execSQL("UPDATE installs SET generation=? WHERE generation='owned-replaced-install'",new Object[]{install.generation});
            broker.store.session(broker.contract.markDead(generation));
            refused("dead-source-remains-rejected","SESSION_INACTIVE",()->RuntimeComponentSettings.route(broker,query,pid,uid));
        }finally{broker.store.close();}
    }
}
