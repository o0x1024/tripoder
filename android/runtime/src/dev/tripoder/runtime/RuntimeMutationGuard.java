package dev.tripoder.runtime;

import android.os.Bundle;
import android.database.sqlite.SQLiteDatabase;

/** Effect scopes outlive connection/session ids. Unknown effects block conflicting NEW ids. */
public final class RuntimeMutationGuard implements AutoCloseable {
    private static final ThreadLocal<RuntimeMutationGuard> CURRENT=new ThreadLocal<>();
    final String scope,command;final boolean explicitStart,maintenance;
    private final RuntimeMutationGuard previous;
    private RuntimeMutationGuard(String scope,String command,boolean start,boolean maintenance){this.scope=scope;this.command=command;this.explicitStart=start;this.maintenance=maintenance;previous=CURRENT.get();CURRENT.set(this);}
    public static RuntimeMutationGuard enter(RuntimeBroker broker,Bundle input){
        String command=input.getString("command","");String space=input.getString("space"),pkg=input.getString("package");
        if(space==null&&input.getString("project")!=null)try(var c=broker.store.getReadableDatabase().rawQuery("SELECT DISTINCT space,package FROM project_refs WHERE id=? AND state='COMMITTED' LIMIT 2",new String[]{input.getString("project")})){if(c.moveToFirst()){space=c.getString(0);pkg=c.getString(1);if(c.moveToNext()){space=null;pkg=null;}}}
        if(space==null&&command.equals("agentExplicit"))try{var target=new org.json.JSONObject(input.getString("payload")).getJSONObject("params").getJSONObject("target");space=target.getString("space");pkg=target.getString("package");}catch(Exception unscoped){/* Unknown legacy shapes remain globally fenced. */}
        String scope=space==null?"*":"space/"+space+(pkg==null?"":"/package/"+pkg);
        return new RuntimeMutationGuard(scope,command,command.equals("prepareLaunch")||command.equals("prepareApplicationLaunch")||command.equals("agentPrepareLaunch"),
            command.equals("cancelNotAccepted")||command.equals("closeLaunchEntry")||command.equals("stopApplication")||command.equals("agentStopApplication"));
    }
    static RuntimeMutationGuard generation(RuntimeBroker broker,String generation,String command){
        try(var c=broker.store.getReadableDatabase().rawQuery("SELECT space,package FROM sessions WHERE generation=?",new String[]{generation})){
            if(!c.moveToFirst())throw new IllegalStateException("RECOVERY_SCOPE_SESSION_MISSING");
            return new RuntimeMutationGuard("space/"+c.getString(0)+"/package/"+c.getString(1),command,false,false);
        }
    }
    static boolean explicitStart(){RuntimeMutationGuard value=CURRENT.get();return value!=null&&value.explicitStart;}
    static void accepting(SQLiteDatabase db,ExecutionContract.Operation operation){
        if(!RuntimeRecoveryGate.productEnabled())return;
        RuntimeMutationGuard guard=CURRENT.get();String scope=guard==null?identityScope(operation.callerIdentity):guard.scope;
        String command=guard==null?"legacy-or-internal":guard.command;
        if(guard==null||!guard.maintenance)try(var c=db.rawQuery("SELECT o.id,COALESCE(s.scope,'*') FROM operations o LEFT JOIN operation_scopes s ON s.operation=o.id WHERE o.state='UNKNOWN' AND o.id!=?",new String[]{operation.id})){
            while(c.moveToNext())if(overlap(scope,c.getString(1)))throw new ExecutionContract.AdmissionRejected(c.getString(0));
        }
        db.execSQL("INSERT OR IGNORE INTO operation_scopes(operation,scope,command,dispatch) VALUES(?,?,?,'NOT_DISPATCHED')",new Object[]{operation.id,scope,command});
    }
    public static boolean overlap(String a,String b){return a.equals("*")||b.equals("*")||a.equals(b)||a.startsWith(b+"/")||b.startsWith(a+"/");}
    private static String identityScope(String caller){
        try{String[] parts=new String[5];int at=0;for(int i=0;i<5;i++){int colon=caller.indexOf(':',at);int count=Integer.parseInt(caller.substring(at,colon));at=colon+1;parts[i]=caller.substring(at,at+count);at+=count;}if(at==caller.length())return "space/"+parts[0]+"/package/"+parts[1];}catch(RuntimeException legacy){}
        return "*";
    }
    @Override public void close(){if(CURRENT.get()!=this)throw new IllegalStateException("RECOVERY_SCOPE_NESTED");if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
}
