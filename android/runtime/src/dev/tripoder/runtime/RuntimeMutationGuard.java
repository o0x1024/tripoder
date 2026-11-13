package dev.tripoder.runtime;

import android.os.Bundle;
import android.database.sqlite.SQLiteDatabase;

/** Effect scopes outlive connection/session ids. Unknown effects block conflicting NEW ids. */
public final class RuntimeMutationGuard implements AutoCloseable {
    private static final ThreadLocal<RuntimeMutationGuard> CURRENT=new ThreadLocal<>();
    final String scope,command,installPackage,deletePackage;final boolean explicitStart,maintenance;private final String requestedOperation;
    private final RuntimeMutationGuard previous;
    private RuntimeMutationGuard(String scope,String command,boolean start,boolean maintenance,String operation){this(scope,command,start,maintenance,operation,null,null);}
    private RuntimeMutationGuard(String scope,String command,boolean start,boolean maintenance,String operation,String installPackage){this(scope,command,start,maintenance,operation,installPackage,null);}
    private RuntimeMutationGuard(String scope,String command,boolean start,boolean maintenance,String operation,String installPackage,String deletePackage){this.installPackage=installPackage;this.deletePackage=deletePackage;this.scope=scope;this.command=command;this.explicitStart=start;this.maintenance=maintenance;this.requestedOperation=operation;previous=CURRENT.get();CURRENT.set(this);}
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
        if(space==null&&command.equals("agentExplicit"))try{var target=new org.json.JSONObject(input.getString("payload")).getJSONObject("params").getJSONObject("target");space=target.getString("space");pkg=target.getString("package");}catch(Exception unscoped){/* Unknown legacy shapes remain globally fenced. */}
        String scope=space==null?"*":"space/"+space+(pkg==null?"":"/package/"+pkg);
        // A delete removes one package from every space it holds installations in. Naming the package keeps the
        // effect scope exact; deriving a single space (and degrading to the global fence whenever there was none
        // or several) left an UNKNOWN delete fencing the whole device.
        if(command.equals("deleteApplication")&&input.getString("package")!=null)scope="package/"+input.getString("package");
        scope=RuntimeOperationScope.commandScope(command,scope);
        return new RuntimeMutationGuard(scope,command,command.equals("prepareLaunch")||command.equals("prepareApplicationLaunch")||command.equals("agentPrepareLaunch"),
            command.equals("cancelNotAccepted")||command.equals("closeLaunchEntry")||command.equals("stopApplication")||command.equals("agentStopApplication")||command.equals("applicationTarget"),input.getString("operation"),installPackage,
            command.equals("deleteApplication")?input.getString("package"):null);
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
                // A delete that ended UNKNOWN only removes rows: it can never add state, and while it has no open
                // mutation nothing of it can be delivered any more. Such a record must not fence new work — the
                // worst case is a leftover installation, which stays visible and can simply be deleted again.
                // Builds before this rule admitted those deletes with a global '*', which fenced everything.
                if("deleteApplication".equals(c.getString(2))&&!hasOpenMutation(db,c.getString(0))){
                    android.util.Log.i("TripoderRecovery","inert-delete-unknown operation="+c.getString(0)+";scope="+effectScope+";new="+command);
                    continue;
                }
                // Deleting an application touches only that package: the installations it still holds and the project
                // rows bound to it. Another package's pending effect is unreachable by it; the same package's effect is
                // superseded, because this delete removes that package from every space and retires those scopes.
                if(guard!=null&&guard.command.equals("deleteApplication")&&guard.deletePackage!=null&&packageIn(effectScope)!=null)continue;
                if("createSpace".equals(c.getString(2))&&RuntimeSpaceCreationProof.permits(db,c.getString(0),scope))continue;
                if(scope.equals(effectScope)){
                    if(guard!=null&&(guard.explicitStart&&RuntimeEffectRecoveryProof.permitsFreshStart(db,c.getString(0),scope)||guard.command.equals("install")&&RuntimeEffectRecoveryProof.permitsExplicitStart(db,c.getString(0),scope))){acknowledgements.add(c.getString(0));continue;}
                    if(RuntimeEffectRecoveryProof.permitsAcknowledgedContinuation(db,c.getString(0),c.getString(4),scope))continue;
                    // Generation-scoped observation must not be hidden by a provably retired broadcast.
                    // Mutating Hook commands still need a fresh-start ACK; unproven UNKNOWN effects stay fenced.
                    if((guard==null||guard.command.startsWith("hook:")&&dev.tripoder.runtime.script.HookCommands.readOnly(guard.command.substring(5)))&&RuntimeEffectRecoveryProof.permitsLiveSessionContinuation(db,c.getString(0)))continue;
                    // The user's explicit delete stops and removes every installation of this scope; its success retires these effects (retireDeleted).
                    if(guard!=null&&guard.command.equals("deleteApplication"))continue;
                }
                // The user's explicit space deletion supersedes every UNKNOWN effect inside that space:
                // it stops and removes all of them, and its success retires their scopes (retireDeletedSpace).
                // A partially completed earlier delete becomes retriable instead of a permanent fence;
                // global UNKNOWN effects and other spaces stay fenced.
                if(guard!=null&&guard.command.equals("deleteSpace")&&(effectScope.equals(scope)||effectScope.startsWith(scope+"/package/")))continue;
                android.util.Log.w("TripoderRecovery","admission-blocked command="+command+";scope="+scope+";prior="+c.getString(0)+";priorScope="+effectScope+";caller="+operation.callerIdentity+";guard="+(guard==null?"none":guard.command)+";"+diagnose(db,c.getString(0),effectScope));
                throw new ExecutionContract.AdmissionRejected(c.getString(0));
            }
        }
        // The same admission transaction records the user's fresh start and the
        // exact old execution it proved inert. Later callbacks of that new owner
        // can proceed; original UNKNOWN business receipts remain untouched.
        for(String previous:acknowledgements){
            db.execSQL("UPDATE operation_scopes SET dispatch=? WHERE operation=?",new Object[]{RuntimeEffectRecoveryProof.ACK+operation.id,previous});
            // A recovered GET is only a capability lookup. Its proof requires a later same-install stop,
            // no live old session and every returned token fenced, so its execution ownership can be closed.
            // Keep the business operation UNKNOWN; SEND/CANCEL and unproven GETs remain open and blocking.
            db.execSQL("UPDATE pending_mutations SET closed=1 WHERE operation=? AND kind='GET' AND closed=0",new Object[]{previous});
        }
        db.execSQL("INSERT OR IGNORE INTO operation_scopes(operation,scope,command,dispatch) VALUES(?,?,?,'NOT_DISPATCHED')",new Object[]{operation.id,scope,command});
    }
    /** Why an UNKNOWN effect still fences its scope: its recorded detail and the states of the sessions that own the scope. */
    private static String diagnose(SQLiteDatabase db,String operation,String effectScope){
        StringBuilder out=new StringBuilder();
        try(var c=db.rawQuery("SELECT detail FROM operations WHERE id=?",new String[]{operation})){out.append("detail=").append(c.moveToFirst()?c.getString(0):"?");}catch(RuntimeException unreadable){out.append("detail=?");}
        try(var c=db.rawQuery("SELECT command,dispatch FROM operation_scopes WHERE operation=? ORDER BY command LIMIT 1",new String[]{operation})){out.append(";priorCommand=").append(c.moveToFirst()?c.getString(0):"?").append(";priorDispatch=").append(c.moveToFirst()&&!c.isNull(1)?c.getString(1):"-");}catch(RuntimeException unreadable){out.append(";priorCommand=?");}
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
    /** A deleted space leaves no owner for any UNKNOWN effect inside it — the space scope
     *  itself and every package scope under it. They stop fencing, so the space can be
     *  recreated; their outcomes stay UNKNOWN. Evidence is preserved by the scope rename. */
    static void retireDeletedSpace(SQLiteDatabase db,String space,String operation){
        String owner="space/"+space;java.util.function.Function<String,String> installation=generation->installationScope(db,generation);
        java.util.List<String[]> retired=new java.util.ArrayList<>();
        try(var c=db.rawQuery("SELECT o.id,s.scope,s.command,o.caller,s.dispatch FROM operations o LEFT JOIN operation_scopes s ON s.operation=o.id WHERE o.state='UNKNOWN' AND o.id!=?",new String[]{operation})){
            while(c.moveToNext()){
                String recorded=RuntimeOperationScope.recorded(c.getString(1),c.getString(2),c.getString(3),installation);
                if(recorded!=null&&(recorded.equals(owner)||recorded.startsWith(owner+"/package/")))
                    retired.add(new String[]{c.getString(0),c.isNull(2)?"legacy-or-internal":c.getString(2),c.isNull(4)?"MAY_HAVE_DISPATCHED":c.getString(4)});
            }
        }
        for(String[] row:retired){
            db.execSQL("INSERT OR REPLACE INTO operation_scopes(operation,scope,command,dispatch) VALUES(?,?,?,?)",new Object[]{row[0],"retired/"+operation+"/"+owner,row[1],row[2]});
            android.util.Log.i("TripoderRecovery","retired-by-space-delete operation="+row[0]+";scope="+owner+";delete="+operation);
        }
    }
    /** The package an effect scope names, whether written as package/<pkg> or space/<space>/package/<pkg>; null when it names none. */
    private static String packageIn(String scope){
        if(scope.startsWith("package/"))return scope.substring(8);
        int at=scope.indexOf("/package/");return at<0?null:scope.substring(at+9);
    }
    /** Whether a callback of this operation can still be delivered: an open pending mutation is the only carrier it has left. */
    private static boolean hasOpenMutation(SQLiteDatabase db,String operation){
        try(var c=db.rawQuery("SELECT 1 FROM pending_mutations WHERE operation=? AND closed=0 LIMIT 1",new String[]{operation})){return c.moveToFirst();}
    }
    private static String installationScope(SQLiteDatabase db,String generation){
        try(var c=db.rawQuery("SELECT space,package FROM installs WHERE generation=? UNION SELECT space,package FROM sessions WHERE installGeneration=?",new String[]{generation,generation})){
            if(!c.moveToFirst())return null;String scope="space/"+c.getString(0)+"/package/"+c.getString(1);
            return c.moveToNext()?null:scope;
        }
    }
    @Override public void close(){if(CURRENT.get()!=this)throw new IllegalStateException("RECOVERY_SCOPE_NESTED");if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
}
