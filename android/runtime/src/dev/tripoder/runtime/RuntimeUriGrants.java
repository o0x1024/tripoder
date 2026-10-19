package dev.tripoder.runtime;

import android.content.Intent;
import android.net.Uri;
import java.util.*;

/**
 * Persisted URI grants are part of the guest's own sandbox state. The platform grant table is owned by the host UID and
 * cannot be shared with a guest, so the guest keeps its own grant set: taking and releasing stay observable and stable
 * for the guest, while the platform is never told that a foreign package holds a grant it does not.
 */
final class RuntimeUriGrants {
    private static final Map<String,Integer> grants=new LinkedHashMap<>();
    private static final int MASK=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    static synchronized void take(Uri uri,int flags){
        if(uri==null||!"content".equals(uri.getScheme()))throw new SecurityException("URI_PERMISSION_SCHEME");
        if(flags==0||(flags&~MASK)!=0)throw new SecurityException("URI_PERMISSION_FLAGS");
        grants.merge(uri.toString(),flags&MASK,(current,added)->current|added);
    }
    static synchronized void release(Uri uri,int flags){
        if(uri==null||!"content".equals(uri.getScheme()))throw new SecurityException("URI_PERMISSION_SCHEME");
        if(flags==0||(flags&~MASK)!=0)throw new SecurityException("URI_PERMISSION_FLAGS");
        String key=uri.toString();Integer current=grants.get(key);if(current==null)return;
        int left=current&~(flags&MASK);if(left==0)grants.remove(key);else grants.put(key,left);
    }
    /**
     * The runtime cannot mint platform {@code UriPermission} records for a guest without the host's grant table, and
     * exposing host grants would leak another identity's URIs. An empty view is the only non-leaking answer.
     */
    static List<Object> persisted(){return Collections.emptyList();}
    private RuntimeUriGrants(){}
}
