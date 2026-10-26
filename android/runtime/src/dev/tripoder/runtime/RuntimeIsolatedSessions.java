package dev.tripoder.runtime;

import android.database.sqlite.SQLiteDatabase;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.util.ArrayList;

/** Isolated liveness never depends on the host UID's process enumeration. */
final class RuntimeIsolatedSessions {
    static void migrate(SQLiteDatabase db){
        db.execSQL("ALTER TABLE sessions ADD COLUMN processKind TEXT NOT NULL DEFAULT 'ORDINARY'");
        db.execSQL("ALTER TABLE sessions ADD COLUMN processInstance TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE sessions ADD COLUMN realUid INTEGER NOT NULL DEFAULT -1");
        db.execSQL("ALTER TABLE service_leases ADD COLUMN isolatedInstanceName TEXT NOT NULL DEFAULT ''");
        db.execSQL("CREATE UNIQUE INDEX isolated_session_owner ON sessions(space,package,installGeneration,processName,processInstance) WHERE processKind='ISOLATED_SERVICE' AND state!='DEAD'");
    }
    interface Absence {boolean confirmed(int pid);}
    static void persist(SQLiteDatabase db,ExecutionContract.Session session,String authority){
        var v=new android.content.ContentValues();v.put("pid",session.pid);v.put("realUid",session.uid);v.put("state",session.state.name());
        String where="generation=? AND authorityGeneration=? AND processKind='ISOLATED_SERVICE' AND processInstance=?";
        String[] args;
        if(session.state==ExecutionContract.ProcessState.ATTACHED){where+=" AND state='ALLOCATED' AND pid=-1 AND realUid=-1";args=new String[]{session.processGeneration,authority,session.instance};}
        else if(session.state==ExecutionContract.ProcessState.RUNNING){where+=" AND state IN ('ATTACHED','RUNNING') AND pid=? AND realUid=?";args=new String[]{session.processGeneration,authority,session.instance,Integer.toString(session.pid),Integer.toString(session.uid)};}
        else throw new IllegalArgumentException("ISOLATED_SESSION_TRANSITION_UNSUPPORTED");
        if(db.update("sessions",v,where,args)!=1)throw new IllegalStateException("ISOLATED_SESSION_PRESTATE_CHANGED");
    }
    static void recover(SQLiteDatabase db,String authority){retireUnstarted(db,authority);recover(db);}
    /** No guest FD can be published until ATTACHED and CLAIMED are durable.
     * Under the new exclusive authority, this exact old phase has never held
     * guest execution. Retire its capability, not the physical host stub.
     * Source business UNKNOWN and Android request receipts remain unchanged. */
    static int retireUnstarted(SQLiteDatabase db,String authority){
        if(!java.util.UUID.fromString(authority).toString().equals(authority))throw new SecurityException("ISOLATED_RECOVERY_AUTHORITY_INVALID");
        int retired=0;db.beginTransaction();try{
            var candidates=new ArrayList<String[]>();
            try(var rows=db.rawQuery("SELECT s.generation,s.authorityGeneration,l.id FROM sessions s JOIN service_leases l ON l.id=s.processInstance AND l.generation=s.generation AND l.space=s.space AND l.package=s.package AND l.installGeneration=s.installGeneration AND l.processName=s.processName AND l.processStub=s.activityStub AND l.stub=s.activityStub WHERE s.processKind='ISOLATED_SERVICE' AND s.state='ALLOCATED' AND s.pid=-1 AND s.realUid=-1 AND s.authorityGeneration IS NOT NULL AND s.authorityGeneration!=? AND s.activityStub=? AND l.state='RESERVED' AND l.instance IS NULL AND l.isolatedInstanceName!=''",new String[]{authority,RuntimeIsolatedService.class.getName()})){
                while(rows.moveToNext())candidates.add(new String[]{rows.getString(0),rows.getString(1),rows.getString(2)});
            }
            for(var candidate:candidates){
                    String generation=candidate[0],old=candidate[1],lease=candidate[2];
                    try{if(!java.util.UUID.fromString(old).toString().equals(old))continue;}catch(IllegalArgumentException invalid){continue;}
                    var session=new android.content.ContentValues();session.put("state","DEAD");
                    if(db.update("sessions",session,"generation=? AND authorityGeneration=? AND processInstance=? AND processKind='ISOLATED_SERVICE' AND state='ALLOCATED' AND pid=-1 AND realUid=-1",new String[]{generation,old,lease})!=1)throw new IllegalStateException("ISOLATED_RETIREMENT_SNAPSHOT_CHANGED");
                    var resource=new android.content.ContentValues();resource.put("state","RELEASED");
                    if(db.update("service_leases",resource,"id=? AND generation=? AND state='RESERVED' AND instance IS NULL",new String[]{lease,generation})!=1)throw new IllegalStateException("ISOLATED_RETIREMENT_LEASE_CHANGED");retired++;
            }
            db.setTransactionSuccessful();return retired;
        }finally{db.endTransaction();}
    }
    static boolean absent(int pid){
        if(pid<=0)return false;
        try{Os.kill(pid,0);return false;}
        catch(ErrnoException error){return error.errno==OsConstants.ESRCH;}
    }
    static void recover(SQLiteDatabase db){recover(db,RuntimeIsolatedSessions::absent);}
    static void recover(SQLiteDatabase db,Absence absence){
        var rows=new ArrayList<String[]>();
        try(var c=db.rawQuery("SELECT generation,pid,realUid,processKind,processInstance,authorityGeneration,state FROM sessions WHERE processKind!='ORDINARY' AND state!='DEAD'",null)){
            while(c.moveToNext()){
                if(!"ISOLATED_SERVICE".equals(c.getString(3)))throw new SecurityException("PROCESS_ROLE_UNKNOWN");
                String[] row=new String[7];for(int i=0;i<row.length;i++)row[i]=c.getString(i);rows.add(row);
            }
        }
        // Kernel observations precede the write transaction. EPERM, PID reuse,
        // missing identity and unavailable observations all remain fenced.
        for(var row:rows)if(Integer.parseInt(row[1])<=0||!absence.confirmed(Integer.parseInt(row[1])))throw new IllegalStateException("ISOLATED_RECOVERY_DEATH_UNCONFIRMED:"+row[0]);
        db.beginTransaction();try{
            for(var row:rows){
                var v=new android.content.ContentValues();v.put("state","DEAD");
                if(db.update("sessions",v,"generation=? AND pid=? AND realUid=? AND processKind=? AND processInstance=? AND authorityGeneration IS ? AND state=?",row)!=1)throw new IllegalStateException("ISOLATED_RECOVERY_SNAPSHOT_CHANGED");
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private RuntimeIsolatedSessions(){}
}
