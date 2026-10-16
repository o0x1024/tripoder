package dev.tripoder.provideridentity;

import android.content.*;
import android.net.Uri;
import java.util.ArrayList;
import org.json.*;

/** Real ContentResolver absence semantics, without external application data. */
final class MissingProviderChecks {
    static void inspect(Context context,JSONArray checks)throws Exception{
        ContentResolver resolver=context.getContentResolver();
        String authority="dev.tripoder.owned.absent.provider.47d854ad";
        Uri uri=Uri.parse("content://"+authority+"/rows");
        check(checks,"missing-provider-stable-client-is-null",resolver.acquireContentProviderClient(uri)==null);
        check(checks,"missing-provider-unstable-client-is-null",resolver.acquireUnstableContentProviderClient(uri)==null);
        check(checks,"missing-provider-query-is-null",resolver.query(uri,null,null,null,null)==null);
        check(checks,"missing-provider-type-is-null",resolver.getType(uri)==null);
        rejected(checks,"insert",()->resolver.insert(uri,new ContentValues()));
        rejected(checks,"update",()->resolver.update(uri,new ContentValues(),null,null));
        rejected(checks,"delete",()->resolver.delete(uri,null,null));
        rejected(checks,"bulk-insert",()->resolver.bulkInsert(uri,new ContentValues[0]));
        rejected(checks,"call",()->resolver.call(uri,"owned",null,null));
        rejected(checks,"batch",()->resolver.applyBatch(authority,new ArrayList<>()));
    }
    private interface Action{Object run()throws Exception;}
    private static void rejected(JSONArray checks,String name,Action action)throws Exception{
        boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=true;}
        check(checks,"missing-provider-"+name+"-throws-illegal-argument",rejected);
    }
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{
        checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);
    }
}
