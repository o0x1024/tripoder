package dev.tripoder.runtime;

import android.content.*;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.FileNotFoundException;
import java.util.ArrayList;

/** Explicit first Provider surface; unsupported operations never reach a host provider. */
abstract class RuntimeProviderBase extends ContentProvider {
    static UnsupportedOperationException unsupported(){return new UnsupportedOperationException("PROVIDER_OPERATION_NOT_IMPLEMENTED");}
    @Override public boolean onCreate(){return true;}
    @Override public Cursor query(Uri uri,String[] projection,String where,String[] args,String order){Bundle b=new Bundle();if(where!=null)b.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,where);if(args!=null)b.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,args);if(order!=null)b.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,order);return query(uri,projection,b,null);}
    @Override public abstract Cursor query(Uri uri,String[] projection,Bundle args,CancellationSignal signal);
    @Override public Cursor query(Uri uri,String[] projection,String where,String[] args,String order,CancellationSignal signal){if(signal!=null)throw unsupported();return query(uri,projection,where,args,order);}
    @Override public Uri insert(Uri uri,ContentValues values){return insert(uri,values,null);}
    @Override public Uri insert(Uri uri,ContentValues values,Bundle extras){throw unsupported();}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){return update(uri,values,RuntimeProviderValues.selection(where,args));}
    @Override public int update(Uri uri,ContentValues values,Bundle extras){throw unsupported();}
    @Override public int delete(Uri uri,String where,String[] args){return delete(uri,RuntimeProviderValues.selection(where,args));}
    @Override public int delete(Uri uri,Bundle extras){throw unsupported();}
    @Override public int bulkInsert(Uri uri,ContentValues[] values){throw unsupported();}
    @Override public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)throws OperationApplicationException{throw unsupported();}
    @Override public ContentProviderResult[] applyBatch(String authority,ArrayList<ContentProviderOperation> operations)throws OperationApplicationException{throw unsupported();}
    @Override public String[] getStreamTypes(Uri uri,String filter){throw unsupported();}
    @Override public Uri canonicalize(Uri uri){throw unsupported();}
    @Override public Uri uncanonicalize(Uri uri){throw unsupported();}
    @Override public boolean refresh(Uri uri,Bundle extras,CancellationSignal signal){throw unsupported();}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{throw unsupported();}
    @Override public AssetFileDescriptor openAssetFile(Uri uri,String mode)throws FileNotFoundException{throw unsupported();}
    @Override public AssetFileDescriptor openTypedAssetFile(Uri uri,String type,Bundle opts)throws FileNotFoundException{throw unsupported();}
}
