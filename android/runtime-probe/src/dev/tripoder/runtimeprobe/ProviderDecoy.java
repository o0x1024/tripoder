package dev.tripoder.runtimeprobe;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
/** Host-owned same-authority decoy. Any resolution reaching it fails acceptance. */
public final class ProviderDecoy extends ContentProvider {
    public static final java.util.concurrent.atomic.AtomicInteger hits=new java.util.concurrent.atomic.AtomicInteger();
    @Override public boolean onCreate(){return true;}
    private RuntimeException hit(){hits.incrementAndGet();return new IllegalStateException("HOST_PROVIDER_DECOY_REACHED");}
    @Override public String getType(Uri uri){throw hit();}
    @Override public Cursor query(Uri uri,String[] projection,String where,String[] args,String sort){throw hit();}
    @Override public Bundle call(String method,String arg,Bundle extras){throw hit();}
    @Override public Uri insert(Uri uri,ContentValues values){throw hit();}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw hit();}
    @Override public int delete(Uri uri,String where,String[] args){throw hit();}
}
