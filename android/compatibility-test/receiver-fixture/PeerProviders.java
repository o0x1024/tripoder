package dev.tripoder.closurefixture;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.concurrent.atomic.AtomicInteger;

/** Only synthetic rows/counters, for real cross-package provider authorization. */
public class PeerProviders extends ContentProvider {
    public static final class Open extends PeerProviders {}
    public static final class Protected extends PeerProviders {}
    public static final class Path extends PeerProviders {}
    private static final AtomicInteger openQueries=new AtomicInteger(),protectedQueries=new AtomicInteger(),pathQueries=new AtomicInteger(),identityCalls=new AtomicInteger(),batchCalls=new AtomicInteger(),cursorRequeries=new AtomicInteger(),cursorCloses=new AtomicInteger();
    private static volatile String cursorCaller;
    @Override public boolean onCreate(){return true;}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
        boolean protectedProvider=this instanceof Protected,pathProvider=this instanceof Path;
        (protectedProvider?protectedQueries:pathProvider?pathQueries:openQueries).incrementAndGet();
        MatrixCursor result=new MatrixCursor(new String[]{"caller","marker"}){
            private boolean closed;
            @Override public boolean requery(){cursorRequeries.incrementAndGet();cursorCaller=PeerProviders.this.getCallingPackage();return super.requery();}
            @Override public void close(){if(!closed){closed=true;cursorCloses.incrementAndGet();}super.close();}
        };result.addRow(new Object[]{getCallingPackage(),protectedProvider?"OWNED_PROTECTED_QUERY":"OWNED_OPEN_QUERY"});return result;
    }
    @Override public Bundle call(String method,String arg,Bundle extras){
        Bundle result=new Bundle();
        if("identity".equals(method)){identityCalls.incrementAndGet();result.putString("caller",getCallingPackage());}
        else if(!"status".equals(method))throw new IllegalArgumentException("OWNED_PEER_METHOD");
        result.putInt("openQueries",openQueries.get());result.putInt("protectedQueries",protectedQueries.get());result.putInt("pathQueries",pathQueries.get());result.putInt("identityCalls",identityCalls.get());result.putInt("batchCalls",batchCalls.get());result.putInt("cursorRequeries",cursorRequeries.get());result.putInt("cursorCloses",cursorCloses.get());result.putString("cursorCaller",cursorCaller);return result;
    }
    @Override public ContentProviderResult[] applyBatch(String authority,java.util.ArrayList<ContentProviderOperation> operations)throws OperationApplicationException{batchCalls.incrementAndGet();return super.applyBatch(authority,operations);}
    @Override public String getType(Uri uri){return "vnd.android.cursor.item/vnd.tripoder.owned";}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
