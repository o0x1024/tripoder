package dev.tripoder.runtimeprobe;

import android.os.Bundle;
import dev.tripoder.runtime.RuntimeBroker;
import org.json.*;
import java.util.UUID;

/** Read model for the owned UI preview only; runtime tables remain the authority. */
final class PreviewCatalog {
    static Bundle dispatch(RuntimeBroker broker,String command)throws Exception {
        Bundle out=new Bundle();
        if(command.equals("previewCreateSpace")){
            long count=broker.catalog.spaces().stream().filter(s->s.startsWith("preview-")).count();
            if(count>=8)throw new IllegalStateException("PREVIEW_SPACE_LIMIT_8");
            String id="preview-"+UUID.randomUUID();broker.catalog.createSpace(id);out.putString("space",id);return out;
        }
        JSONArray spaces=new JSONArray();
        try(var c=broker.store.getReadableDatabase().rawQuery("SELECT id,created FROM spaces WHERE id LIKE 'preview-%' ORDER BY created,id",null)){
            while(c.moveToNext()){
                String id=c.getString(0);JSONObject space=new JSONObject().put("id",id).put("created",c.getLong(1));
                try(var i=broker.store.getReadableDatabase().rawQuery("SELECT generation,sha256,state FROM installs WHERE space=? AND package='dev.tripoder.previewguest'",new String[]{id})){
                    if(i.moveToFirst())space.put("install",new JSONObject().put("generation",i.getString(0)).put("sha256",i.getString(1)).put("state",i.getString(2)));
                }
                JSONArray sessions=new JSONArray();try(var s=broker.store.getReadableDatabase().rawQuery("SELECT generation,pid,state FROM sessions WHERE space=? AND state NOT IN ('DEAD','FAILED')",new String[]{id})){
                    while(s.moveToNext())sessions.put(new JSONObject().put("generation",s.getString(0)).put("pid",s.getInt(1)).put("state",s.getString(2)));
                }
                space.put("sessions",sessions);spaces.put(space);
            }
        }
        out.putString("spaces",spaces.toString());return out;
    }
}
