package dev.tripoder.closurefixture;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.concurrent.atomic.AtomicInteger;

/** Declared + admitted at bootstrap, deliberately removed from the actual bind request by attach. */
public final class DeferredProvider extends ContentProvider {
    static final AtomicInteger constructors=new AtomicInteger(),attachments=new AtomicInteger(),creates=new AtomicInteger();
    static Thread creator;
    public DeferredProvider(){constructors.incrementAndGet();}
    @Override public void attachInfo(Context context,ProviderInfo info){attachments.incrementAndGet();super.attachInfo(context,info);}
    @Override public boolean onCreate(){creates.incrementAndGet();creator=Thread.currentThread();return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){Bundle out=new Bundle();out.putInt("creates",creates.get());return out;}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){return null;}
    @Override public String getType(Uri uri){return null;}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
