package dev.tripoder.runtime;

import android.database.Cursor;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;

/** A durable metadata snapshot, independent of APK loading and runtime readiness. */
final class RuntimeControllerCatalog {
    static Bundle read(RuntimeBroker broker)throws Exception{
        long begin=android.os.SystemClock.elapsedRealtime();
        // Missing/legacy references are repaired from install rows, never by loading every APK.
        // Each repair has its own short critical section; serialization is outside the broker.
        new ApplicationProjects(broker).reconcileMetadata();
        JSONArray spaces=new JSONArray(),installs=new JSONArray(),projects=new JSONArray(),applications;
        synchronized(broker){
            for(String space:broker.catalog.spaces())spaces.put(space);
            if(spaces.length()>256)throw new IllegalStateException("CATALOG_PAGE_REQUIRED");
            try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT space,package,generation,state,version FROM installs ORDER BY space,package LIMIT 257",null)){
                while(c.moveToNext())installs.put(new JSONObject().put("space",c.getString(0)).put("package",c.getString(1)).put("generation",c.getString(2)).put("state",c.getString(3)).put("version",c.getLong(4)));
            }
            if(installs.length()>256)throw new IllegalStateException("CATALOG_PAGE_REQUIRED");
            try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT id,space,package,generation,state,operation,mode FROM project_refs WHERE kind='DIRECT' ORDER BY id,space LIMIT 257",null)){
                while(c.moveToNext())projects.put(new JSONObject().put("project",c.getString(0)).put("space",c.getString(1)).put("package",c.getString(2)).put("generation",c.getString(3)).put("state",c.getString(4)).put("bindingOperation",c.getString(5)).put("mode",c.getString(6)));
            }
            if(projects.length()>256)throw new IllegalStateException("CATALOG_PAGE_REQUIRED");
            applications=new ApplicationProjects(broker).list();
        }
        Bundle out=new Bundle();out.putString("spaces",spaces.toString());out.putString("installs",installs.toString());
        out.putString("projects",projects.toString());out.putString("applications",applications.toString());
        android.util.Log.i("TripoderBoot","CATALOG_READY installs="+installs.length()+" ms="+(android.os.SystemClock.elapsedRealtime()-begin));
        return out;
    }
    private RuntimeControllerCatalog(){}
}
