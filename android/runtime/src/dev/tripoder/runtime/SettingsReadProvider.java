package dev.tripoder.runtime;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

final class SettingsReadProvider extends ContentProvider {
    private final ContentResolver system;
    SettingsReadProvider(ContentResolver system){this.system=system;}
    @Override public boolean onCreate(){return true;}
    static void uri(Uri uri){
        if(!readable(uri))throw new UnsupportedOperationException("PROVIDER_URI_UNSUPPORTED");
    }
    /** Query targets. Observation registration is wider: a ContentObserver grants no data access. */
    static boolean readable(Uri uri){
        if(uri==null||!"content".equals(uri.getScheme()))return false;
        if(RuntimeEngine.current!=null&&RuntimeEngine.current.mode==RuntimeMode.THIN)return true;
        if("settings".equals(uri.getAuthority()))
            return !uri.getPathSegments().isEmpty()&&java.util.Set.of("system","secure","global").contains(uri.getPathSegments().get(0));
        return "media".equals(uri.getAuthority())||"downloads".equals(uri.getAuthority());
    }
    @Override public Bundle call(String authority,String method,String arg,Bundle extras){
        if(RuntimeEngine.current!=null&&RuntimeEngine.current.mode==RuntimeMode.THIN){
            if(!"settings".equals(authority)||method==null)throw new UnsupportedOperationException("PROVIDER_CALL_UNSUPPORTED");
            if(method.startsWith("PUT_")||method.startsWith("DELETE_")||method.startsWith("RESET_"))return new Bundle();
            return system.call(authority,method,arg,RuntimeProviderFacade.primitive(extras));
        }
        // DeviceConfig is read by the platform's Autofill/ContentCapture UI
        // services after currentApplication becomes the logical Application.
        // Its LIST command has no key argument and is still a read operation;
        // never grant the corresponding SET/DELETE/config mutation commands.
        boolean config="LIST_config".equals(method);
        if(!"settings".equals(authority)||!(config||java.util.Set.of("GET_system","GET_secure","GET_global").contains(method))||
            (config?arg!=null:arg==null||arg.length()>200))throw new UnsupportedOperationException("PROVIDER_CALL_UNSUPPORTED");
        if(extras!=null&&extras.containsKey("_user")&&extras.getInt("_user")!=android.os.Process.myUserHandle().hashCode())throw new UnsupportedOperationException("SETTINGS_OTHER_USER_UNSUPPORTED");
        return system.call(authority,method,arg,RuntimeProviderFacade.primitive(extras));
    }
    @Override public Bundle call(String method,String arg,Bundle extras){throw new UnsupportedOperationException("PROVIDER_AUTHORITY_REQUIRED");}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){uri(uri);return system.query(uri,projection,selection,args,order);}
    @Override public String getType(Uri uri){uri(uri);return system.getType(uri);}
    @Override public Uri insert(Uri uri,ContentValues values){if(RuntimeEngine.current!=null&&RuntimeEngine.current.mode==RuntimeMode.THIN)return uri;throw new UnsupportedOperationException("PROVIDER_WRITE_UNSUPPORTED");}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){if(RuntimeEngine.current!=null&&RuntimeEngine.current.mode==RuntimeMode.THIN)return 0;throw new UnsupportedOperationException("PROVIDER_WRITE_UNSUPPORTED");}
    @Override public int delete(Uri uri,String where,String[] args){if(RuntimeEngine.current!=null&&RuntimeEngine.current.mode==RuntimeMode.THIN)return 0;throw new UnsupportedOperationException("PROVIDER_WRITE_UNSUPPORTED");}
}
