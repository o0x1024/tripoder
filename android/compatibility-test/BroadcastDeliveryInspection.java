package dev.tripoder.hooklab;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded read-only terminal receipt evidence for owned fixtures; no payload or filter contents. */
final class BroadcastDeliveryInspection {
    static JSONObject inspect(Context host,String space,String pkg,String generation)throws Exception{
        if(!Set.of("dev.tripoder.closurefixture","dev.tripoder.launchcontract").contains(pkg))throw new SecurityException("OWNED_BROADCAST_FIXTURE_REQUIRED");
        JSONArray deliveries=new JSONArray();JSONObject registrations=new JSONObject();
        try(var db=SQLiteDatabase.openDatabase(host.getDatabasePath("tripoder-runtime-v2.db").toString(),null,SQLiteDatabase.OPEN_READONLY)){
            try(var owner=db.rawQuery("SELECT 1 FROM sessions WHERE generation=? AND space=? AND package=?",new String[]{generation,space,pkg})){
                if(!owner.moveToFirst())throw new SecurityException("OWNED_BROADCAST_SESSION_REQUIRED");
            }
            try(var rows=db.rawQuery("SELECT d.ticket,d.operation,d.source,d.target,d.component,d.registration,d.state,d.returned,d.finished,d.asynchronous,d.ordered,d.systemDone,d.resourceRetired,o.state FROM broadcast_deliveries d JOIN sessions s ON s.generation=d.target LEFT JOIN operations o ON o.id=d.operation WHERE (d.source=? OR d.target=?) AND s.space=? AND s.package=? ORDER BY d.rowid LIMIT 1025",new String[]{generation,generation,space,pkg})){
                while(rows.moveToNext()){
                    if(deliveries.length()>=1024)throw new IllegalStateException("OWNED_BROADCAST_QUERY_CAPACITY");
                    JSONObject row=new JSONObject();String[] text={"ticket","operation","source","target","component","registration","state"};
                    for(int i=0;i<text.length;i++)row.put(text[i],rows.getString(i));
                    String[] bits={"returned","finished","asynchronous","ordered","systemDone","resourceRetired"};
                    for(int i=0;i<bits.length;i++)row.put(bits[i],rows.getInt(7+i)!=0);
                    row.put("operationState",rows.getString(13));deliveries.put(row);
                }
            }
            try(var rows=db.rawQuery("SELECT state,COUNT(*) FROM broadcast_registrations WHERE generation=? GROUP BY state",new String[]{generation})){while(rows.moveToNext())registrations.put(rows.getString(0),rows.getInt(1));}
        }
        return new JSONObject().put("readOnly",true).put("generation",generation).put("deliveries",deliveries).put("registrations",registrations);
    }
    private BroadcastDeliveryInspection(){}
}
