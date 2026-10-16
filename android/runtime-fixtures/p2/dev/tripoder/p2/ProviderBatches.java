package dev.tripoder.p2;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.*;

final class ProviderBatches {
    private static final String AUTH="dev.tripoder.p2.write";
    private static final Uri URI=Uri.parse("content://"+AUTH+"/items");
    private interface Action {void run()throws Exception;}
    private static boolean denied(Action action,String message)throws Exception{try{action.run();return false;}catch(Exception error){return android.util.Log.getStackTraceString(error).contains(message);}}
    static void run(Context context,ProviderWrites.Check test)throws Exception{
        ContentResolver r=context.getContentResolver();ArrayList<ContentProviderOperation> ops=new ArrayList<>();
        ops.add(ContentProviderOperation.newInsert(URI).withValue("_id",10L).withValue("text","parent").build());
        ops.add(ContentProviderOperation.newInsert(URI).withValue("_id",11L).withValueBackReference("number",0).build());
        ops.add(ContentProviderOperation.newUpdate(URI).withValue("text","batch-updated").withSelection("_id=?",new String[]{"ignored"}).withSelectionBackReference(0,0).withExpectedCount(1).build());
        ops.add(ContentProviderOperation.newAssertQuery(URI).withSelection("_id=?",new String[]{"11"}).withValueBackReference("number",0).withExpectedCount(1).build());
        ops.add(ContentProviderOperation.newAssertQuery(URI).withSelection("_id=?",new String[]{"10"}).withValue("text","batch-updated").withExpectedCount(1).build());
        ops.add(ContentProviderOperation.newDelete(URI).withSelection("_id=?",new String[]{"11"}).withExpectedCount(1).build());
        ContentProviderResult[] result=r.applyBatch(AUTH,ops);test.check("provider-batch-real-backrefs-and-results",result.length==6&&ContentUris.parseId(result[0].uri)==10&&ContentUris.parseId(result[1].uri)==11&&result[2].count==1&&result[3].count==1&&result[4].count==1&&result[5].count==1,Arrays.toString(result));
        try(Cursor c=r.query(URI,new String[]{"text"},"_id=?",new String[]{"10"},null)){test.check("provider-batch-real-database-result",c.moveToFirst()&&c.getString(0).equals("batch-updated"),c.getCount());}
        try(ContentProviderClient client=r.acquireContentProviderClient(AUTH)){ContentProviderResult[] deleted=client.applyBatch(new ArrayList<>(List.of(ContentProviderOperation.newDelete(URI).withSelection("_id=?",new String[]{"10"}).withExpectedCount(1).build())));test.check("provider-explicit-client-batch",deleted.length==1&&deleted[0].count==1,Arrays.toString(deleted));}
        for(int key:new int[]{-1,128,Integer.MAX_VALUE}){
            ArrayList<ContentProviderOperation> invalid=new ArrayList<>();invalid.add(ContentProviderOperation.newInsert(URI).withValue("_id",12).build());invalid.add(ContentProviderOperation.newDelete(URI).withSelection("_id=?",new String[]{"12"}).withSelectionBackReference(key,0).build());
            test.check("provider-batch-sparse-index-rejected-"+key,denied(()->r.applyBatch(AUTH,invalid),"PROVIDER_BATCH_SELECTION_INDEX_INVALID"),key);
        }
        ArrayList<ContentProviderOperation> forward=new ArrayList<>(List.of(ContentProviderOperation.newDelete(URI).withSelection("_id=?",new String[]{"12"}).withSelectionBackReference(0,0).build()));test.check("provider-batch-forward-reference-rejected",denied(()->r.applyBatch(AUTH,forward),"PROVIDER_BATCH_BACK_REFERENCE_INVALID"),true);
        ArrayList<ContentProviderOperation> mixed=new ArrayList<>(List.of(ContentProviderOperation.newInsert(URI).withValue("_id",12).build(),ContentProviderOperation.newDelete(Uri.parse("content://dev.tripoder.peer.public/items")).build()));test.check("provider-batch-cross-authority-rejected",denied(()->r.applyBatch(AUTH,mixed),"PROVIDER_BATCH_URI_MISMATCH"),true);
        String peer="dev.tripoder.peer.pathlocked";ArrayList<ContentProviderOperation> forbidden=new ArrayList<>(List.of(ContentProviderOperation.newInsert(Uri.parse("content://"+peer+"/public/items")).withValue("probe",1).build(),ContentProviderOperation.newInsert(Uri.parse("content://"+peer+"/locked/items")).withValue("probe",2).build()));
        test.check("provider-batch-checks-every-path-before-callback",denied(()->r.applyBatch(peer,forbidden),"PROVIDER_PATH_PERMISSION_UNSUPPORTED"),true);
        try(Cursor c=r.query(Uri.parse("content://dev.tripoder.peer.public/items"),null,null,null,null)){c.moveToFirst();test.check("provider-denied-batch-has-zero-partial-writes",c.getInt(0)==2,c.getInt(0));}
        Bundle counts=r.call(URI,"counts",null,null);test.check("provider-batch-callback-once-and-invalid-input-never-enters",counts.getInt("batches")==2&&counts.getInt("inserts")==6&&counts.getInt("updates")==3&&counts.getInt("deletes")==4,counts.toString());
    }
}
