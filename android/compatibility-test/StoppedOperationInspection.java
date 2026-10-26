package dev.tripoder.hooklab;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact outer-stop metadata only; no APK contents, account data, memory or trace. */
final class StoppedOperationInspection {
    static JSONObject inspect(Context host,String operation,JSONObject receipt)throws Exception{
        if(!host.getPackageName().startsWith("dev.tripoder.compat"))throw new SecurityException("OWNED_QA_REQUIRED");
        JSONObject out=new JSONObject().put("readOnly",true).put("diagnosticIsNotAdmission",true);
        if(!"trusted-workbench".equals(receipt.optString("caller"))||!java.util.Set.of("stopApplication","agentStopApplication").contains(receipt.optString("command")))return out.put("outerStopRole",false);
        String scope=receipt.getString("effectScope");var match=Pattern.compile("space/([a-zA-Z0-9_-]{1,160})/package/([a-zA-Z0-9_.]+)").matcher(scope);
        if(!match.matches())throw new SecurityException("STOP_SCOPE_NOT_CANONICAL");
        String space=match.group(1),pkg=match.group(2);
        try(var db=SQLiteDatabase.openDatabase(host.getDatabasePath("tripoder-runtime-v2.db").toString(),null,SQLiteDatabase.OPEN_READONLY)){
            String install;long updated;
            try(var rows=db.rawQuery("SELECT generation,state,dataId FROM installs WHERE space=? AND package=?",new String[]{space,pkg})){
                if(!rows.moveToFirst())throw new IllegalStateException("STOP_INSTALL_NOT_FOUND");install=rows.getString(0);out.put("installation",new JSONObject().put("generation",install).put("state",rows.getString(1)).put("dataIncarnation",rows.getString(2)));
            }
            try(var rows=db.rawQuery("SELECT updated FROM operations WHERE id=?",new String[]{operation})){if(!rows.moveToFirst())throw new IllegalStateException("STOP_OPERATION_NOT_FOUND");updated=rows.getLong(0);out.put("originalUpdatedAtMs",updated);}
            Class<?> identity=Class.forName("dev.tripoder.runtime.RuntimeControllerOperationIdentity",false,host.getClassLoader());var digestMethod=identity.getDeclaredMethod("stopDigest",String.class,String.class,String.class);digestMethod.setAccessible(true);
            String digest=(String)digestMethod.invoke(null,space,pkg,install);out.put("currentStopDigestMatchesOriginal",digest.equals(receipt.optString("payloadDigest")));
            JSONArray sessions=new JSONArray();boolean allDead=true;int closedDead=0;
            try(var rows=db.rawQuery("SELECT generation,state,closeRequested,authorityGeneration FROM sessions WHERE space=? AND package=? AND installGeneration=? ORDER BY generation",new String[]{space,pkg,install})){
                while(rows.moveToNext()){boolean dead=rows.getString(1).equals("DEAD"),closed=rows.getInt(2)==1;allDead&=dead;if(dead&&closed)closedDead++;sessions.put(new JSONObject().put("generation",rows.getString(0)).put("state",rows.getString(1)).put("closeRequested",closed).put("authorityGeneration",rows.getString(3)));}
            }
            out.put("sessions",sessions).put("allSessionsConfirmedDead",sessions.length()>0&&allDead).put("closedDeadTargets",closedDead);
            JSONArray stops=new JSONArray();
            try(var rows=db.rawQuery("SELECT o.id,o.created,s.command FROM operations o JOIN operation_scopes s ON s.operation=o.id WHERE o.state='SUCCEEDED' AND o.caller=? AND o.digest=? AND o.created>=? AND s.scope=? AND s.command IN ('stopApplication','agentStopApplication') ORDER BY o.created",new String[]{receipt.getString("caller"),digest,Long.toString(updated),scope})){
                while(rows.moveToNext())stops.put(new JSONObject().put("operation",rows.getString(0)).put("createdAtMs",rows.getLong(1)).put("command",rows.getString(2)));
            }
            out.put("laterSuccessfulMatchingStops",stops);
            Class<?> proof=Class.forName("dev.tripoder.runtime.RuntimeStoppedEffectProof",false,host.getClassLoader());var permits=proof.getDeclaredMethod("permits",SQLiteDatabase.class,String.class,String.class,boolean.class);permits.setAccessible(true);
            out.put("freshStartProofCandidate",permits.invoke(null,db,operation,scope,false));
        }
        return out;
    }
    private StoppedOperationInspection(){}
}
