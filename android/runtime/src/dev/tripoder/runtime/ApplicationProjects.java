package dev.tripoder.runtime;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Durable application ownership, independent of installations and UI target selection. */
public final class ApplicationProjects {
    private final RuntimeBroker broker;
    public ApplicationProjects(RuntimeBroker broker){this.broker=broker;}
    static void create(SQLiteDatabase db){
        db.execSQL("CREATE TABLE application_projects(package TEXT PRIMARY KEY, id TEXT NOT NULL UNIQUE, selectedSpace TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE project_aliases(id TEXT PRIMARY KEY, project TEXT NOT NULL, package TEXT NOT NULL)");
    }
    static void migrate(SQLiteDatabase db){
        create(db);
        db.execSQL("INSERT INTO application_projects(package,id,selectedSpace) SELECT package,MIN(id),'' FROM project_refs WHERE kind='DIRECT' GROUP BY package");
        db.execSQL("UPDATE application_projects SET selectedSpace=(SELECT space FROM project_refs WHERE id=application_projects.id)");
        db.execSQL("INSERT INTO project_aliases SELECT p.id,a.id,p.package FROM project_refs p JOIN application_projects a ON a.package=p.package WHERE p.kind='DIRECT' AND p.id!=a.id");
        // Keep the full old metadata for recovery; new binding receipts are issued explicitly.
        db.execSQL("ALTER TABLE project_refs RENAME TO project_refs_archive_v34");
        createTargets(db);
        db.execSQL("INSERT INTO project_refs SELECT CASE WHEN p.kind='DIRECT' THEN a.id ELSE p.id END,p.space,p.package,p.generation,p.sourceSha,p.snapshot,'STALE',p.operation,p.kind,p.mode FROM project_refs_archive_v34 p LEFT JOIN application_projects a ON a.package=p.package WHERE p.id=(SELECT MIN(q.id) FROM project_refs_archive_v34 q WHERE q.space=p.space AND q.package=p.package)");
    }
    static void createTargets(SQLiteDatabase db){
        db.execSQL("CREATE TABLE project_refs(id TEXT NOT NULL, space TEXT NOT NULL, package TEXT NOT NULL, generation TEXT NOT NULL, sourceSha TEXT NOT NULL, snapshot TEXT NOT NULL, state TEXT NOT NULL, operation TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'MIGRATED', mode TEXT NOT NULL DEFAULT 'thin', PRIMARY KEY(id,space))");
        db.execSQL("CREATE UNIQUE INDEX direct_application_target ON project_refs(space,package) WHERE kind='DIRECT'");
    }
    public String ensure(String pkg){
        RuntimeFiles.id(pkg);
        synchronized(broker){
            var db=broker.store.getWritableDatabase();
            ContentValues row=new ContentValues();row.put("package",pkg);row.put("id",UUID.randomUUID().toString());
            db.insertWithOnConflict("application_projects",null,row,SQLiteDatabase.CONFLICT_IGNORE);
            try(var c=db.rawQuery("SELECT id FROM application_projects WHERE package=?",new String[]{pkg})){if(!c.moveToFirst())throw new IllegalStateException("APPLICATION_PROJECT_MISSING");return c.getString(0);}
        }
    }
    public String selected(String project){synchronized(broker){try(var c=broker.store.getReadableDatabase().rawQuery("SELECT selectedSpace FROM application_projects WHERE id=?",new String[]{project})){return c.moveToFirst()?c.getString(0):"";}}}
    public void select(String project,String space){synchronized(broker){ContentValues row=new ContentValues();row.put("selectedSpace",space);broker.store.getWritableDatabase().update("application_projects",row,"id=?",new String[]{project});}}
    public Bundle target(String space,String pkg,String generation)throws Exception{
        RuntimeFiles.id(space);RuntimeFiles.id(pkg);RuntimeFiles.id(generation);
        String project;synchronized(broker){var install=broker.apks.row(space,pkg);if(install==null||!"COMMITTED".equals(install.state)||!generation.equals(install.generation))throw new IllegalStateException("INSTALL_GENERATION_CONFLICT");project=ensure(pkg);}
        var bindings=new ProjectTargetBindings(broker);
        synchronized(broker){
            try{Bundle current=bindings.resolveIn(project,space);if(generation.equals(current.getString("generation")))return current;}catch(IllegalStateException stale){}
            var install=broker.apks.row(space,pkg);if(install==null||!"COMMITTED".equals(install.state)||!generation.equals(install.generation))throw new IllegalStateException("INSTALL_GENERATION_CONFLICT");
            String prior=null;try(var c=broker.store.getReadableDatabase().rawQuery("SELECT operation FROM project_refs WHERE id=? AND space=? AND kind='DIRECT'",new String[]{project,space})){if(c.moveToFirst())prior=c.getString(0);}
            String operation=UUID.nameUUIDFromBytes(("application-target-v2\n"+project+"\n"+space+"\n"+generation).getBytes(StandardCharsets.UTF_8)).toString();
            bindings.bind(operation,"trusted-workbench",project,space,pkg,generation,prior);
            return bindings.resolveIn(project,space);
        }
    }
    public void reconcile()throws Exception{
        List<String[]> installs=new ArrayList<>();synchronized(broker){try(var c=broker.store.getReadableDatabase().rawQuery("SELECT space,package,generation,state FROM installs ORDER BY space,package",null)){while(c.moveToNext())installs.add(new String[]{c.getString(0),c.getString(1),c.getString(2),c.getString(3)});}}
        for(String[] install:installs){
            synchronized(broker){var current=broker.apks.row(install[0],install[1]);if(current==null||!install[2].equals(current.generation))continue;ensure(install[1]);if(!"COMMITTED".equals(current.state))continue;}
            try{target(install[0],install[1],install[2]);}catch(Exception failure){synchronized(broker){var current=broker.apks.row(install[0],install[1]);if(current==null||!install[2].equals(current.generation))continue;if("CORRUPT".equals(current.state)){broker.catalog.invalidateProjects(install[0],install[1]);continue;}}throw failure;}
        }
    }
    public JSONArray list()throws Exception{
        JSONArray out=new JSONArray();synchronized(broker){try(var c=broker.store.getReadableDatabase().rawQuery("SELECT id,package,selectedSpace FROM application_projects ORDER BY package LIMIT 257",null)){
            while(c.moveToNext()){JSONObject row=new JSONObject().put("project",c.getString(0)).put("package",c.getString(1)).put("selectedSpace",c.getString(2));JSONArray aliases=new JSONArray();try(var a=broker.store.getReadableDatabase().rawQuery("SELECT id FROM project_aliases WHERE project=? ORDER BY id",new String[]{c.getString(0)})){while(a.moveToNext())aliases.put(a.getString(0));}out.put(row.put("legacyProjects",aliases));}
        }}if(out.length()>256)throw new IllegalStateException("APPLICATION_PAGE_REQUIRED");return out;
    }
}
