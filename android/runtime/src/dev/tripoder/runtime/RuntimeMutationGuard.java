package dev.tripoder.runtime;

import android.os.Bundle;
import android.database.sqlite.SQLiteDatabase;

/** Effect scopes outlive connection/session ids. Unknown effects block conflicting NEW ids. */
public final class RuntimeMutationGuard implements AutoCloseable {
    private static final ThreadLocal<RuntimeMutationGuard> CURRENT=new ThreadLocal<>();
    final String scope,command,installPackage;final boolean explicitStart,maintenance;private final String requestedOperation;
    private final RuntimeMutationGuard previous;
    private RuntimeMutationGuard(String scope,String command,boolean start,boolean maintenance,String operation){this(scope,command,start,maintenance,operation,null);}
    private RuntimeMutationGuard(String scope,String command,boolean start,boolean maintenance,String operation,String installPackage){this.installPackage=installPackage;this.scope=scope;this.command=command;this.explicitStart=start;this.maintenance=maintenance;this.requestedOperation=operation;previous=CURRENT.get();CURRENT.set(this);}
    public static RuntimeMutationGuard enter(RuntimeBroker broker,Bundle input){
        String command=input.getString("command","");String space=input.getString("space"),pkg=input.getString("package");
        if(command.equals("install")&&space!=null&&pkg==null)
            pkg=input.getString("expectedGeneration")!=null?upgradePackage(broker.store.getReadableDatabase(),space,input.getString("expectedGeneration")):RuntimeInstallPackagePeek.of(input);
        String installPackage=command.equals("install")&&space!=null?pkg:null;
        if(space==null&&input.getString("project")!=null){
            boolean explicit=input.containsKey("bindingOperation");
            String sql="SELECT DISTINCT space,package FROM project_refs WHERE id=? AND state='COMMITTED'"+(explicit?" AND operation=?":"")+" LIMIT 2";
            String[] values=explicit?new String[]{input.getString("project"),input.getString("bindingOperation")}:new String[]{input.getString("project")};
            try(var c=broker.store.getReadableDatabase().rawQuery(sql,values)){if(c.moveToFirst()){space=c.getString(0);pkg=c.getString(1);if(c.moveToNext()){space=null;pkg=null;}}}
        }
        // Deleting one application reaches only that package's installations; uninstallOne re-checks each against this scope.
        if(space==null&&pkg!=null&&command.equals("deleteApplication"))space=installedSpace(broker.store.getReadableDatabase(),pkg);
        if(space==null&&command.equals("agentExplicit"))try{var target=new org.json.JSONObject(input.getString("payload")).getJSONObject("params").getJSONObject("target");space=target.getString("space");pkg=target.getString("package");}catch(Exception unscoped){/* Unknown legacy shapes remain globally fenced. */}
        String scope=space==null?"*":"space/"+space+(pkg==null?"":"/package/"+pkg);
        return new RuntimeMutationGuard(scope,command,command.equals("prepareLaunch")||command.equals("prepareApplicationLaunch")||command.equals("agentPrepareLaunch"),
            command.equals("cancelNotAccepted")||command.equals("closeLaunchEntry")||command.equals("stopApplication")||command.equals("agentStopApplication")||command.equals("applicationTarget"),input.getString("operation"),installPackage);
    }
    static RuntimeMutationGuard generation(RuntimeBroker broker,String generation,String command){
        try(var c=broker.store.getReadableDatabase().rawQuery("SELECT space,package FROM sessions WHERE generation=?",new String[]{generation})){
            if(!c.moveToFirst())throw new IllegalStateException("RECOVERY_SCOPE_SESSION_MISSING");
            return new RuntimeMutationGuard("space/"+c.getString(0)+"/package/"+c.getString(1),command,false,false,null);
        }
    }
    /** An install was admitted for the package its bytes appeared to name; the committed artifact set must be exactly that package. */
    static void requireInstallPackage(String actual){
        RuntimeMutationGuard value=CURRENT.get();
        if(value!=null&&value.installPackage!=null&&!value.installPackage.equals(actual))throw new SecurityException("INSTALL_SCOPE_PACKAGE_MISMATCH:"+value.installPackage+"!="+actual);
    }
    /** A command admitted for a narrower scope must not reach an installation outside it. */
    static void requireCovers(String space,String pkg){
        RuntimeMutationGuard value=CURRENT.get();
        if(value!=null&&!RuntimeOperationScope.overlap(value.scope,"space/"+space+"/package/"+pkg))throw new SecurityException("RECOVERY_SCOPE_EXCEEDED:"+value.scope+"!=space/"+space+"/package/"+pkg);
    }
    static boolean explicitStart(){RuntimeMutationGuard value=CURRENT.get();return value!=null&&value.explicitStart;}
    static String explicitStartOperation(){RuntimeMutationGuard value=CURRENT.get();return value!=null&&value.explicitStart?value.requestedOperation:null;}
    /** Only the authenticated, dead-generation task plan may enter this scope. */
    static RuntimeMutationGuard taskRetirement(String space,String pkg){return new RuntimeMutationGuard("space/"+space+"/package/"+pkg,"retireInertTask",false,true,null);}
    static void accepting(SQLiteDatabase db,ExecutionContract.Operation operation){
        if(!RuntimeRecoveryGate.productEnabled())return;
        RuntimeMutationGuard guard=CURRENT.get();java.util.function.Function<String,String> installation=generation->installationScope(db,generation);
        String scope=guard==null?RuntimeOperationScope.identity(operation.callerIdentity,installation):guard.scope;
        String command=guard==null?"legacy-or-internal":guard.command;
        java.util.List<String> acknowledgements=new java.util.ArrayList<>();
        if(guard==null||!guard.maintenance)try(var c=db.rawQuery("SELECT o.id,s.scope,s.command,o.caller,s.dispatch FROM operations o LEFT JOIN operation_scopes s ON s.operation=o.id WHERE o.state='UNKNOWN' AND o.id!=?",new String[]{operation.id})){
            while(c.moveToNext()){
                String effectScope=RuntimeOperationScope.recorded(c.getString(1),c.getString(2),c.getString(3),installation);
                if(!overlap(scope,effectScope))continue;
                if("createSpace".equals(c.getString(2))&&RuntimeSpaceCreationProof.permits(db,c.getString(0),scope))continue;
                if(scope.equals(effectScope)){
                    if(guard!=null&&(guard.explicitStart&&RuntimeEffectRecoveryProof.permitsFreshStart(db,c.getString(0),scope)||guard.command.equals("install")&&RuntimeEffectRecoveryProof.permitsExplicitStart(db,c.getString(0),scope))){acknowledgements.add(c.getString(0));continue;}
                    if(RuntimeEffectRecoveryProof.permitsAcknowledgedContinuation(db,c.getString(0),c.getString(4),scope))continue;
                    if(guard==null&&RuntimeEffectRecoveryProof.permitsLiveSessionContinuation(db,c.getString(0)))continue;
                    // The user's explicit delete stops and removes every installation of this scope; its success retires these effects (retireDeleted).
                    if(guard!=null&&guard.command.equals("deleteApplication"))continue;
                }
                android.util.Log.w("TripoderRecovery","admission-blocked command="+command+";scope="+scope+";prior="+c.getString(0)+";priorScope="+effectScope+";caller="+operation.callerIdentity+";guard="+(guard==null?"none":guard.command)+";"+diagnose(db,c.getString(0),effectScope));
                throw new ExecutionContract.AdmissionRejected(c.getString(0));
            }
        }
        // The same admission transaction records the user's fresh start and the
        // exact old execution it proved inert. Later callbacks of that new owner
        // can proceed; original UNKNOWN business receipts remain untouched.
        for(String previous:acknowledgements)db.execSQL("UPDATE operation_scopes SET dispatch=? WHERE operation=?",new Object[]{RuntimeEffectRecoveryProof.ACK+operation.id,previous});
        db.execSQL("INSERT OR IGNORE INTO operation_scopes(operation,scope,command,dispatch) VALUES(?,?,?,'NOT_DISPATCHED')",new Object[]{operation.id,scope,command});
    }
    /** Why an UNKNOWN effect still fences its scope: its recorded detail and the states of the sessions that own the scope. */
    private static String diagnose(SQLiteDatabase db,String operation,String effectScope){
        StringBuilder out=new StringBuilder();
        try(var c=db.rawQuery("SELECT detail FROM operations WHERE id=?",new String[]{operation})){out.append("detail=").append(c.moveToFirst()?c.getString(0):"?");}catch(RuntimeException unreadable){out.append("detail=?");}
        try(var c=db.rawQuery("SELECT state,COUNT(*) FROM sessions WHERE 'space/'||space||'/package/'||package=? GROUP BY state",new String[]{effectScope})){out.append(";sessions=");while(c.moveToNext())out.append(c.getString(0)).append(':').append(c.getInt(1)).append(',');}catch(RuntimeException unreadable){out.append("?");}
        return out.toString();
    }
    public static boolean overlap(String a,String b){return RuntimeOperationScope.overlap(a,b);}
    static String upgradePackage(SQLiteDatabase db,String space,String generation){
        try(var rows=db.rawQuery("SELECT package FROM installs WHERE space=? AND generation=? LIMIT 2",new String[]{space,generation})){
            if(!rows.moveToFirst())return null;String pkg=rows.getString(0);return rows.moveToNext()?null:pkg;
        }
    }
    /** A deleted installation leaves no owner for its UNKNOWN effects: they stop fencing the scope, so the package can be added again. Their outcomes stay UNKNOWN. */
    static void retireDeleted(SQLiteDatabase db,String space,String pkg,String operation){
        String owner="space/"+space+"/package/"+pkg;java.util.function.Function<String,String> installation=generation->installationScope(db,generation);
        java.util.List<String[]> retired=new java.util.ArrayList<>();
        try(var c=db.rawQuery("SELECT o.id,s.scope,s.command,o.caller,s.dispatch FROM operations o LEFT JOIN operation_scopes s ON s.operation=o.id WHERE o.state='UNKNOWN' AND o.id!=?",new String[]{operation})){
            while(c.moveToNext())if(owner.equals(RuntimeOperationScope.recorded(c.getString(1),c.getString(2),c.getString(3),installation)))
                retired.add(new String[]{c.getString(0),c.isNull(2)?"legacy-or-internal":c.getString(2),c.isNull(4)?"MAY_HAVE_DISPATCHED":c.getString(4)});
        }
        for(String[] row:retired){
            db.execSQL("INSERT OR REPLACE INTO operation_scopes(operation,scope,command,dispatch) VALUES(?,?,?,?)",new Object[]{row[0],"retired/"+operation+"/"+owner,row[1],row[2]});
            android.util.Log.i("TripoderRecovery","retired-by-delete operation="+row[0]+";scope="+owner+";delete="+operation);
        }
    }
    /** The single space holding this package; none or several keep the global fence. */
    private static String installedSpace(SQLiteDatabase db,String pkg){
        try(var rows=db.rawQuery("SELECT DISTINCT space FROM installs WHERE package=? LIMIT 2",new String[]{pkg})){
            if(!rows.moveToFirst())return null;String space=rows.getString(0);return rows.moveToNext()?null:space;
        }
    }
    private static String installationScope(SQLiteDatabase db,String generation){
        try(var c=db.rawQuery("SELECT space,package FROM installs WHERE generation=? UNION SELECT space,package FROM sessions WHERE installGeneration=?",new String[]{generation,generation})){
            if(!c.moveToFirst())return null;String scope="space/"+c.getString(0)+"/package/"+c.getString(1);
            return c.moveToNext()?null:scope;
        }
    }
    @Override public void close(){if(CURRENT.get()!=this)throw new IllegalStateException("RECOVERY_SCOPE_NESTED");if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
}
