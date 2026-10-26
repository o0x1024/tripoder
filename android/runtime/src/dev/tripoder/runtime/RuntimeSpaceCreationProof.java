package dev.tripoder.runtime;

import android.database.sqlite.SQLiteDatabase;

/** A completed local SQL creation has no deferred entry or external execution capability. */
final class RuntimeSpaceCreationProof {
    static boolean permits(SQLiteDatabase db,String operation,String requestedScope){
        String scope,digest;
        try(var rows=db.rawQuery("SELECT s.scope,o.digest FROM operations o JOIN operation_scopes s ON s.operation=o.id WHERE o.id=? AND o.state='UNKNOWN' AND o.detail='SPACE_CREATION_PERSISTENCE_UNCONFIRMED' AND o.caller=? AND s.command='createSpace' AND s.dispatch='MAY_HAVE_DISPATCHED'",new String[]{operation,RuntimeControllerOperationIdentity.CALLER})){
            if(!rows.moveToFirst())return false;scope=rows.getString(0);digest=rows.getString(1);
        }
        if(scope==null||!scope.startsWith("space/"))return false;
        String space=scope.substring(6);try{RuntimeFiles.id(space);}catch(IllegalArgumentException invalid){return false;}
        if(!requestedScope.equals(scope)&&!requestedScope.startsWith(scope+"/package/"))return false;
        final String expected;
        try{expected=ApkStore.textDigest("controller-create-space-v1\n"+space);}
        catch(Exception failure){throw new IllegalStateException("SPACE_CREATION_PROOF_DIGEST_UNAVAILABLE",failure);}
        if(!expected.equals(digest))return false;
        // The fixed handler records this detail after its synchronous SQL
        // attempt has exited. No later effect or executable entry is issued.
        // The observed row proves the requested namespace now exists. It does
        // not establish which creation wrote it or change the UNKNOWN receipt.
        try(var rows=db.rawQuery("SELECT 1 FROM spaces WHERE id=?",new String[]{space})){return rows.moveToFirst();}
    }
    private RuntimeSpaceCreationProof(){}
}
