package dev.tripoder.hooklab;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;

/** Only the fixed self-owned fixture's identity/lifecycle rows; no guest contents. */
final class IsolatedExecutionInspection {
    static JSONObject inspect(Context host,String space,String pkg,String generation,String processGeneration)throws Exception{
        var scopes=java.util.Map.of("dev.tripoder.isolatedservicecontract","paired-dev-tripoder-isolatedservicecontract","dev.tripoder.isolatedfdprobe","paired-dev-tripoder-isolatedfdprobe");
        if(!host.getPackageName().startsWith("dev.tripoder.compat")||!space.equals(scopes.get(pkg)))throw new SecurityException("OWNED_ISOLATED_FIXTURE_REQUIRED");
        JSONObject result=new JSONObject().put("readOnly",true).put("accountDataRead",false);
        try(var db=SQLiteDatabase.openDatabase(host.getDatabasePath("tripoder-runtime-v2.db").toString(),null,SQLiteDatabase.OPEN_READONLY)){
            String authority;
            try(var row=db.rawQuery("SELECT authorityGeneration FROM sessions WHERE generation=? AND space=? AND package=? AND installGeneration=? AND processKind='ORDINARY'",new String[]{processGeneration,space,pkg,generation})){
                if(!row.moveToFirst())throw new SecurityException("OWNED_ISOLATED_PARENT_SESSION_REQUIRED");authority=row.getString(0);result.put("executionAuthorityGeneration",authority);
            }
            try(var row=db.rawQuery("SELECT generation,dataId,state FROM installs WHERE space=? AND package=?",new String[]{space,pkg})){
                if(!row.moveToFirst()||!generation.equals(row.getString(0)))throw new SecurityException("OWNED_ISOLATED_INSTALL_CHANGED");result.put("installGeneration",generation).put("dataIncarnation",row.getString(1)).put("installState",row.getString(2));
            }
            JSONArray sessions=new JSONArray();
            try(var rows=db.rawQuery("SELECT generation,pid,realUid,state,processInstance,authorityGeneration,closeRequested FROM sessions WHERE space=? AND package=? AND installGeneration=? AND processKind='ISOLATED_SERVICE' ORDER BY generation",new String[]{space,pkg,generation})){
                while(rows.moveToNext())sessions.put(new JSONObject().put("generation",rows.getString(0)).put("pid",rows.getInt(1)).put("realUid",rows.getInt(2)).put("state",rows.getString(3)).put("lease",rows.getString(4)).put("authorityGeneration",rows.getString(5)).put("closeRequested",rows.getInt(6)!=0));
            }
            result.put("sessions",sessions);JSONArray leases=new JSONArray();
            try(var rows=db.rawQuery("SELECT id,generation,state,isolatedInstanceName FROM service_leases WHERE space=? AND package=? AND installGeneration=? AND isolatedInstanceName!='' ORDER BY id",new String[]{space,pkg,generation})){
                while(rows.moveToNext())leases.put(new JSONObject().put("lease",rows.getString(0)).put("generation",rows.getString(1)).put("state",rows.getString(2)).put("logicalInstance",rows.getString(3)));
            }
            result.put("leases",leases);JSONArray calls=new JSONArray();
            try(var rows=db.rawQuery("SELECT c.kind,c.state,COUNT(*) FROM service_calls c JOIN service_leases l ON l.id=c.lease WHERE l.space=? AND l.package=? AND l.installGeneration=? AND l.isolatedInstanceName!='' GROUP BY c.kind,c.state",new String[]{space,pkg,generation})){
                while(rows.moveToNext())calls.put(new JSONObject().put("kind",rows.getString(0)).put("state",rows.getString(1)).put("count",rows.getInt(2)));
            }
            result.put("calls",calls);
            try(var rows=db.rawQuery("SELECT COUNT(*) FROM recovery_paused WHERE generation IN (SELECT generation FROM sessions WHERE space=? AND package=? AND installGeneration=? AND processKind='ISOLATED_SERVICE')",new String[]{space,pkg,generation})){rows.moveToFirst();result.put("isolatedPausedGenerations",rows.getInt(0));}
            try(var rows=db.rawQuery("SELECT COUNT(*) FROM recovery_paused WHERE generation IN (SELECT generation FROM sessions WHERE space=? AND package=? AND installGeneration=? AND processKind='ISOLATED_SERVICE' AND authorityGeneration=?)",new String[]{space,pkg,generation,authority})){rows.moveToFirst();result.put("currentAuthorityIsolatedPausedGenerations",rows.getInt(0));}
        }
        return result;
    }
    private IsolatedExecutionInspection(){}
}
