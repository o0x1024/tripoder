package dev.tripoder.controltest;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Actual Android SQLiteOpenHelper v34->v35 and source recovery, isolated from the app's real database. */
public final class SingleProjectMigrationIntegration extends ControlTestBase {
    @Override void test()throws Exception{
        report.put("scope","android-sqlite-v34-upgrade-and-source-recovery");
        File fixture=new File(getTargetContext().getFilesDir(),"migration-"+run);if(!fixture.mkdirs())throw new IllegalStateException("FIXTURE_DIRECTORY");
        Context context=new ContextWrapper(getTargetContext()){
            @Override public File getFilesDir(){return fixture;}
            @Override public File getDatabasePath(String name){File dir=new File(fixture,"databases");dir.mkdirs();return new File(dir,name);}
            @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
            @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getAbsolutePath(),factory,handler);}
        };
        Class<?> type=Class.forName("dev.tripoder.runtime.RuntimeStore");Constructor<?> constructor=type.getConstructor(Context.class);SQLiteOpenHelper helper=(SQLiteOpenHelper)constructor.newInstance(context);
        helper.getWritableDatabase();helper.close();
        try(SQLiteDatabase legacy=context.openOrCreateDatabase("tripoder-runtime-v2.db",0,null)){
            legacy.beginTransaction();try{
                legacy.execSQL("DROP TABLE project_refs");legacy.execSQL("DROP TABLE application_projects");legacy.execSQL("DROP TABLE project_aliases");
                legacy.execSQL("CREATE TABLE project_refs(id TEXT PRIMARY KEY, space TEXT NOT NULL, package TEXT NOT NULL, generation TEXT NOT NULL, sourceSha TEXT NOT NULL, snapshot TEXT NOT NULL, state TEXT NOT NULL, operation TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'MIGRATED', mode TEXT NOT NULL DEFAULT 'thin')");
                for(String id:List.of("migration-a","migration-b","migration-c"))legacy.execSQL("INSERT INTO project_refs VALUES(?,?,?,?,?,?,?,?,?,?)",new Object[]{id,id.equals("migration-c")?"space-b":"space-a","owned.migration","generation",id,"snapshot-"+id,"COMMITTED","operation-"+id,"DIRECT","thin"});
                legacy.setVersion(34);legacy.setTransactionSuccessful();
            }finally{legacy.endTransaction();}
        }
        for(String id:List.of("migration-a","migration-b","migration-c","unbound-source")){
            File dir=new File(fixture,"projects/"+id+"/scripts");if(!dir.mkdirs())throw new IllegalStateException("SOURCE_FIXTURE_DIRECTORY");
            Files.writeString(new File(dir,"main.ts").toPath(),"// "+id+"\n");Files.writeString(new File(dir.getParentFile(),"project.json").toPath(),new JSONObject().put("schema",2).put("id",id).put("name",id).put("createdAt",1).toString());
        }
        Files.writeString(new File(fixture,"projects/migration-b/selectors.json").toPath(),"[{\"id\":\"restored\"}]");
        helper=(SQLiteOpenHelper)constructor.newInstance(context);
        JSONArray owners=new JSONArray();
        try{
            SQLiteDatabase db=helper.getWritableDatabase();check("android-helper-upgrades-to-schema-35",db.getVersion()==35,db.getVersion());
            check("old-binding-metadata-preserved",DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM project_refs_archive_v34",null)==3,true);
            check("one-application-owner-after-upgrade",DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM application_projects",null)==1,true);
            check("two-installation-bindings-share-one-project",DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM project_refs",null)==2&&DatabaseUtils.longForQuery(db,"SELECT COUNT(DISTINCT id) FROM project_refs",null)==1,true);
            check("old-binding-receipts-are-invalidated",DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM project_refs WHERE state='STALE'",null)==2,true);
            try(Cursor rows=db.rawQuery("SELECT id,package FROM application_projects",null)){while(rows.moveToNext()){JSONArray aliases=new JSONArray();try(Cursor old=db.rawQuery("SELECT id FROM project_aliases WHERE project=? ORDER BY id",new String[]{rows.getString(0)})){while(old.moveToNext())aliases.put(old.getString(0));}owners.put(new JSONObject().put("project",rows.getString(0)).put("package",rows.getString(1)).put("legacyProjects",aliases));}}
        }finally{helper.close();}
        Class<?> sources=Class.forName("dev.tripoder.hooklab.WorkbenchProjects");Constructor<?> sourceConstructor=sources.getDeclaredConstructor(Context.class);sourceConstructor.setAccessible(true);Object projects=sourceConstructor.newInstance(context);
        invoke(projects,"reconcile",new Class<?>[]{JSONArray.class},owners);
        check("only-canonical-source-remains-runnable",((List<?>)invoke(projects,"all",new Class<?>[0])).size()==1,true);
        check("canonical-source-is-preserved",invoke(projects,"script",new Class<?>[]{String.class},"migration-a").equals("// migration-a\n"),true);
        check("different-old-scripts-require-review",((JSONObject)invoke(projects,"get",new Class<?>[]{String.class},"migration-a")).getBoolean("migrationPending"),true);
        check("duplicates-and-unbound-source-are-archived",((List<?>)invoke(projects,"archived",new Class<?>[]{String.class},(Object)null)).size()==3,true);
        check("old-script-content-remains-recoverable",invoke(projects,"archivedScript",new Class<?>[]{String.class},"migration-b").equals("// migration-b\n")&&invoke(projects,"archivedScript",new Class<?>[]{String.class},"unbound-source").equals("// unbound-source\n"),true);
        invoke(projects,"restore",new Class<?>[]{String.class,String.class},"migration-a","migration-b");invoke(projects,"acceptMigration",new Class<?>[]{String.class},"migration-a");
        check("restore-selects-script-and-selectors",invoke(projects,"script",new Class<?>[]{String.class},"migration-a").equals("// migration-b\n")&&new JSONArray((String)invoke(projects,"selectors",new Class<?>[]{String.class},"migration-a")).getJSONObject(0).getString("id").equals("restored"),true);
        check("restore-creates-a-backup-first",((List<?>)invoke(projects,"archived",new Class<?>[]{String.class},(Object)null)).size()==4,true);
        invoke(projects,"reconcile",new Class<?>[]{JSONArray.class},owners);
        check("migration-restart-does-not-duplicate-or-overwrite",((List<?>)invoke(projects,"all",new Class<?>[0])).size()==1&&invoke(projects,"script",new Class<?>[]{String.class},"migration-a").equals("// migration-b\n")&&!((JSONObject)invoke(projects,"get",new Class<?>[]{String.class},"migration-a")).getBoolean("migrationPending"),true);
        invoke(projects,"reconcile",new Class<?>[]{JSONArray.class},new JSONArray());check("stale-catalog-cannot-archive-established-source",invoke(projects,"script",new Class<?>[]{String.class},"migration-a").equals("// migration-b\n"),true);
        invoke(projects,"delete",new Class<?>[]{String.class},"migration-a");invoke(projects,"reconcile",new Class<?>[]{JSONArray.class},owners);
        check("stale-snapshot-cannot-resurrect-deleted-source",((List<?>)invoke(projects,"all",new Class<?>[0])).isEmpty(),true);
        report.put("fixtureOnly",true).put("productionDatabaseTouched",false).put("fixtureDirectory",fixture.toString());
    }
    private static Object invoke(Object object,String name,Class<?>[] parameters,Object...args)throws Exception{Method method=object.getClass().getDeclaredMethod(name,parameters);method.setAccessible(true);return method.invoke(object,args);}
}
