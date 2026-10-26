package dev.tripoder.runtime;

import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded read-only lifecycle and binding facts over the existing Service ledger. */
final class RuntimeServiceResources {
    private RuntimeServiceResources(){}
    static void append(RuntimeBroker broker,Bundle result)throws Exception{
        JSONArray rows=new JSONArray();
        synchronized(broker){
            try(var cursor=broker.store.getReadableDatabase().rawQuery("SELECT l.id,l.space,l.package,l.installGeneration,l.component,l.generation,l.state,l.instance,(SELECT COUNT(*) FROM service_calls c WHERE c.lease=l.id AND c.kind='bind' AND c.state='APPLIED'),(SELECT COUNT(*) FROM service_calls c WHERE c.lease=l.id AND c.state='PREPARED') FROM service_leases l ORDER BY CASE WHEN l.state IN ('LIVE','CLAIMED','RESERVED') THEN 0 WHEN l.state='DORMANT' THEN 1 ELSE 2 END,l.rowid DESC LIMIT 129",null)){
                while(cursor.moveToNext())rows.put(new JSONObject().put("lease",cursor.getString(0)).put("space",cursor.getString(1)).put("package",cursor.getString(2))
                    .put("installGeneration",cursor.getString(3)).put("component",cursor.getString(4)).put("processGeneration",cursor.getString(5))
                    .put("state",cursor.getString(6)).put("instance",cursor.getString(7)).put("bindingReferences",cursor.getInt(8)).put("preparedCalls",cursor.getInt(9)));
            }
        }
        boolean truncated=rows.length()>128;if(truncated)rows.remove(128);
        result.putString("serviceReferences",rows.toString());result.putBoolean("serviceReferencesTruncated",truncated);
    }
}
