package dev.tripoder.providerpeer;

import android.app.Activity;
import android.content.ContentProviderClient;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.lang.reflect.*;
import org.json.JSONObject;

/** Probe only the owned peer's own acquisition of the owned Open provider. */
final class RawTransportChecks {
    private static boolean contains(Throwable error,String code){for(Throwable cause=error;cause!=null;cause=cause.getCause())if(String.valueOf(cause.getMessage()).contains(code))return true;return false;}
    private static Object field(Object value,String name)throws Exception{Field item=value.getClass().getDeclaredField(name);item.setAccessible(true);return item.get(value);}
    private static Bundle envelope(Bundle reference,String source,String uri){
        Bundle payload=new Bundle();payload.putString("method","identity");payload.putBundle("extras",new Bundle());
        Bundle result=new Bundle();result.putString("lease",reference.getString("lease"));result.putString("authority",Uri.parse(uri).getAuthority());result.putString("kind","call");result.putString("uri",uri);result.putBundle("payload",payload);result.putStringArray("projection",null);
        result.putString("source",source);
        return result;
    }
    static void run(Activity activity,JSONObject checks)throws Exception{
        Object engine;
        try(ContentProviderClient settings=activity.getContentResolver().acquireContentProviderClient("settings")){
            Class<?> type=Class.forName("dev.tripoder.runtime.RuntimeEngine",false,settings.getLocalContentProvider().getClass().getClassLoader());Field current=type.getDeclaredField("current");current.setAccessible(true);engine=current.get(null);
        }
        if(!activity.getPackageName().equals(field(engine,"packageName")))throw new SecurityException("OWNED_PEER_ENGINE_REQUIRED");
        String source=(String)field(engine,"generation");Object providers=field(engine,"providers");Method acquire=providers.getClass().getDeclaredMethod("acquire",String.class,boolean.class,boolean.class);acquire.setAccessible(true);
        Object facade=acquire.invoke(providers,Main.OPEN.getAuthority(),true,false);
        if(facade==null)throw new AssertionError("OWNED_OPEN_ACQUISITION_REQUIRED");
        try{
            Bundle reference=(Bundle)field(facade,"reference");ContentProviderClient client=(ContentProviderClient)field(facade,"client");
            if(reference.getString("target")==null||source.equals(reference.getString("target")))throw new AssertionError("OWNED_SEPARATE_TARGET_PROCESS_REQUIRED");
            String method="tripoder.thin.v2";
            Bundle forged=envelope(reference,source,Main.OPEN.toString());forged.putString("callerPackage",Main.TARGET);
            boolean thin=reference.getBoolean("thin"),denied=false;try{client.call(method,null,forged);}catch(Exception expected){denied=contains(expected,thin?"PROVIDER_ENVELOPE_INVALID":"PROVIDER_PROCESS_NOT_AUTHENTICATED");}
            checks.put("owned-transport-rejects-caller-package-impersonation",denied);
            Uri.Builder wire=new Uri.Builder().scheme("content").authority(reference.getString("physical")).appendPath("v2-thin").appendPath(reference.getString("lease")).appendQueryParameter("uri","content://"+Main.TARGET+".not-the-leased-authority/owned");
            wire.appendQueryParameter("source",source);
            denied=false;try(Cursor ignored=client.query(wire.build(),null,new Bundle(),null)){}catch(Exception expected){denied=contains(expected,thin?"PROVIDER_AUTHORITY_NOT_PUBLISHED":"PROVIDER_PROCESS_NOT_AUTHENTICATED");}
            checks.put("owned-transport-rejects-lease-uri-authority-mismatch",denied);
            Bundle wrongSource=envelope(reference,reference.getString("target"),Main.OPEN.toString());
            denied=false;try{client.call(method,null,wrongSource);}catch(Exception expected){denied=contains(expected,thin?"CALLER_MISMATCH":"PROVIDER_PROCESS_NOT_AUTHENTICATED");}
            checks.put("owned-transport-rejects-another-process-generation",denied);
            denied=false;try{client.call("tripoder.thin",null,envelope(reference,source,Main.OPEN.toString()));}catch(Exception expected){denied=contains(expected,"PROVIDER_ENVELOPE_INVALID");}
            checks.put("owned-transport-rejects-retired-v1-protocol",denied);
            Bundle missing=envelope(reference,source,Main.OPEN.toString());missing.remove("source");
            denied=false;try{client.call(method,null,missing);}catch(Exception expected){denied=contains(expected,thin?"PROVIDER_ENVELOPE_INVALID":"PROVIDER_PROCESS_NOT_AUTHENTICATED");}
            checks.put("owned-transport-rejects-missing-source-generation",denied);
        }finally{Method close=facade.getClass().getDeclaredMethod("close");close.setAccessible(true);close.invoke(facade);}
    }
    private RawTransportChecks(){}
}
