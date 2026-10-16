package dev.tripoder.runtime;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;

/** Synthetic database fixtures only: migration retention and atomic Hook recovery fences. */
final class RuntimeJobSchemaProbe {
    static void run(Context host,String run,JSONArray checks)throws Exception{
        Context context=new RuntimeJobProbeContext(host,run+"-schema");
        try(RuntimeStore store=new RuntimeStore(context)){
            var db=store.getWritableDatabase();
            db.execSQL("INSERT INTO operations(id,caller,digest,state,result,detail,created,updated) VALUES('old-operation','fixture','digest','UNKNOWN','','old-evidence',1,1)");
        }
        // Manufacture the previous schema in a brand-new fixture path. No existing
        // runtime database or historical user record is altered by this test.
        try(var db=SQLiteDatabase.openDatabase(context.getDatabasePath("tripoder-runtime-v2.db").toString(),null,SQLiteDatabase.OPEN_READWRITE)){
            for(String table:new String[]{"job_attempt_events","job_attempts","job_instances","job_mutation_targets","job_mutations","job_versions","job_components"})db.execSQL("DROP TABLE "+table);
            db.setVersion(26);
        }
        try(RuntimeStore store=new RuntimeStore(context)){
            var db=store.getWritableDatabase();
            check(checks,"schema-26-upgrades-to-28",db.getVersion()==28);
            check(checks,"schema-migration-retains-historical-unknown",text(db,"SELECT state||':'||detail FROM operations WHERE id='old-operation'").equals("UNKNOWN:old-evidence"));
            db.execSQL("INSERT INTO sessions(generation,space,package,installGeneration,pid,state,processName,activityStub,authorityGeneration) VALUES('first','s','p','i',0,'DEAD','process','stub','fixture')");
            db.execSQL("INSERT INTO sessions(generation,space,package,installGeneration,pid,state,processName,activityStub,authorityGeneration) VALUES('second','s','p','i',0,'DEAD','process','stub','fixture')");
            db.execSQL("INSERT INTO job_components(id,space,package,installGeneration,processName,processStub,stub,component) VALUES('lease','s','p','i','process','stub','job-stub','component')");
            db.execSQL("INSERT INTO job_versions(version,lease,installGeneration,namespace,physicalNamespace,jobId,logical,physical,operation,state) VALUES('version','lease','i','','physical',1,X'01',X'01','old-operation','ACCEPTED')");
            db.execSQL("CREATE TRIGGER job_fence_fault BEFORE UPDATE ON job_versions BEGIN SELECT RAISE(ABORT,'INJECTED_JOB_FENCE_WRITE_FAILURE'); END");
            boolean failed=false;try{store.requireExplicitHookRecovery("first","hook-first");}catch(android.database.sqlite.SQLiteException error){failed=String.valueOf(error.getMessage()).contains("INJECTED_JOB_FENCE_WRITE_FAILURE");}
            check(checks,"hook-fence-write-failure-propagates",failed);
            check(checks,"hook-session-marker-rolls-back-with-job-fence",store.hookOperation("first")==null&&text(db,"SELECT COALESCE(hookOperation,'none') FROM job_versions").equals("none"));
            db.execSQL("DROP TRIGGER job_fence_fault");store.requireExplicitHookRecovery("first","hook-first");
            check(checks,"pending-job-and-session-share-hook-fence",store.hookOperation("first").equals("hook-first")&&text(db,"SELECT hookGeneration||':'||hookOperation FROM job_versions").equals("first:hook-first"));
            store.requireExplicitHookRecovery("second","hook-second");
            check(checks,"new-worker-hook-cannot-rebind-old-job-contract",text(db,"SELECT hookGeneration||':'||hookOperation FROM job_versions").equals("first:hook-first"));
        }
        try(RuntimeStore store=new RuntimeStore(context)){
            check(checks,"hook-fence-survives-database-reopen",text(store.getReadableDatabase(),"SELECT hookGeneration||':'||hookOperation FROM job_versions").equals("first:hook-first"));
        }
    }
    private static String text(SQLiteDatabase db,String sql){try(var cursor=db.rawQuery(sql,null)){if(!cursor.moveToFirst())throw new AssertionError("ROW_MISSING");return cursor.getString(0);}}
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    private RuntimeJobSchemaProbe(){}
}
