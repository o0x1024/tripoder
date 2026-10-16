package dev.tripoder.p2;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import java.util.*;

/** Business errors may follow real effects. These operations must remain UNKNOWN, never replayed. */
final class ProviderFaults {
    static void run(Context context,ProviderWrites.Check test)throws Exception{
        ContentResolver r=context.getContentResolver();String authority="dev.tripoder.p2.write";Uri uri=Uri.parse("content://"+authority+"/items");
        ContentValues value=new ContentValues();value.put("_id",30);value.put("text","one-effect");boolean threw=false;String failure="no error";
        try{r.insert(Uri.parse("content://"+authority+"/throwAfterWrite"),value);}catch(RuntimeException error){failure=android.util.Log.getStackTraceString(error);threw=failure.contains("OWNED_WRITE_AFTER_EFFECT");}
        test.check("provider-write-after-effect-error-propagated",threw,failure);
        try(Cursor c=r.query(uri,new String[]{"text"},"_id=?",new String[]{"30"},null)){test.check("provider-write-error-effect-once-no-replay",c.moveToFirst()&&c.getCount()==1&&c.getString(0).equals("one-effect"),c.getCount());}
        ArrayList<ContentProviderOperation> transaction=new ArrayList<>(List.of(ContentProviderOperation.newInsert(uri).withValue("_id",31).build(),ContentProviderOperation.newAssertQuery(uri).withSelection("_id=?",new String[]{"31"}).withExpectedCount(0).build()));
        threw=false;try{r.applyBatch(authority,transaction);}catch(Exception error){threw=true;}test.check("provider-transaction-batch-error-propagated",threw,threw);
        try(Cursor c=r.query(uri,null,"_id=?",new String[]{"31"},null)){test.check("provider-own-transaction-rolls-back",c.getCount()==0,c.getCount());}
        String peer="dev.tripoder.peer.public";Uri other=Uri.parse("content://"+peer+"/items");
        ArrayList<ContentProviderOperation> partial=new ArrayList<>(List.of(ContentProviderOperation.newInsert(other).withValue("probe",3).build(),ContentProviderOperation.newAssertQuery(other).withExpectedCount(0).build()));
        threw=false;try{r.applyBatch(peer,partial);}catch(Exception error){threw=true;}test.check("provider-nontransaction-batch-error-propagated",threw,threw);
        try(Cursor c=r.query(other,null,null,null,null)){c.moveToFirst();test.check("provider-nontransaction-partial-effect-once-no-replay",c.getInt(0)==3,c.getInt(0));}
        android.os.Bundle counts=r.call(uri,"counts",null,null);test.check("provider-failed-callback-counts-not-retried",counts.getInt("inserts")==8&&counts.getInt("batches")==3,counts.toString());
    }
}
