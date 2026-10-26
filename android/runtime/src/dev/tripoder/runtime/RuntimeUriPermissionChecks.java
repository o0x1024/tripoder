package dev.tripoder.runtime;

import android.content.pm.PackageManager;
import android.net.Uri;
import java.util.List;
import java.util.Objects;

/** Inspects explicit logical grants. No grant issuer is admitted by this runtime yet;
 * host-UID grants must never be inherited by a guest sharing that UID. Inspection
 * of this empty grant set is a denial, not an unsupported mutation or a grant. */
final class RuntimeUriPermissionChecks {
    static int check(Uri uri,int uid){Objects.requireNonNull(uri,"uri");return uid==0?PackageManager.PERMISSION_GRANTED:PackageManager.PERMISSION_DENIED;}
    static int[] check(List<Uri> uris,int uid){int[] values=new int[uris.size()];for(int i=0;i<values.length;i++)values[i]=check(uris.get(i),uid);return values;}
    static void enforce(int result,String message){if(result!=PackageManager.PERMISSION_GRANTED)throw new SecurityException((message==null?"":message+": ")+"URI permission not granted");}
    private RuntimeUriPermissionChecks(){}
}
