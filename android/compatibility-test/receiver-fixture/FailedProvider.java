package dev.tripoder.closurefixture;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.content.pm.ProviderInfo;
import java.util.concurrent.atomic.AtomicInteger;

public final class FailedProvider extends ContentProvider {
    static final AtomicInteger constructors=new AtomicInteger(),attachments=new AtomicInteger(),creates=new AtomicInteger();
    public FailedProvider(){constructors.incrementAndGet();}
    @Override public void attachInfo(Context context,ProviderInfo info){attachments.incrementAndGet();super.attachInfo(context,info);}
    @Override public boolean onCreate(){creates.incrementAndGet();throw new IllegalStateException("OWNED_EXPECTED_PROVIDER_INIT_FAILURE");}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){return null;}
    @Override public String getType(Uri uri){return null;}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
