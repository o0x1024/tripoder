package dev.tripoder.peer;

import android.content.*;
import android.database.*;
import android.net.Uri;

/** Separate owned package to exercise logical package permissions despite a shared OS UID. */
public class PeerProvider extends ContentProvider {
    private static int writes;
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){return "vnd.android.cursor.item/tripoder-peer";}
    @Override public android.os.Bundle call(String method,String action,android.os.Bundle extras){
        if(!"owned-broadcast".equals(method))throw new UnsupportedOperationException("PEER_METHOD_UNSUPPORTED");
        getContext().sendBroadcast(new Intent(action).setPackage(extras.getString("target")).putExtra("sender","peer"));android.os.Bundle result=new android.os.Bundle();result.putInt("pid",android.os.Process.myPid());return result;
    }
    @Override public Cursor query(Uri uri,String[] projection,String where,String[] args,String sort){MatrixCursor c=new MatrixCursor(new String[]{"writes","caller","pid"});c.addRow(new Object[]{writes,getCallingPackage(),android.os.Process.myPid()});return c;}
    @Override public Uri insert(Uri uri,ContentValues values){writes++;return ContentUris.withAppendedId(uri,writes);}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){writes++;return 1;}
    @Override public int delete(Uri uri,String where,String[] args){writes++;return 1;}
    public static final class Public extends PeerProvider {}
    public static final class ReadLocked extends PeerProvider {}
    public static final class WriteLocked extends PeerProvider {}
    public static final class PathLocked extends PeerProvider {}
    public static final class Private extends PeerProvider {}
}
