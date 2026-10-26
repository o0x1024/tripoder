package dev.tripoder.closurefixture;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.concurrent.atomic.AtomicInteger;

/** Own provider reproduces the shell's guard after superclass attach/onCreate. */
public class CanonicalProvider extends ContentProvider {
    public static final class Disabled extends CanonicalProvider {}
    public static final class Remote extends CanonicalProvider {}
    static final AtomicInteger constructors=new AtomicInteger(),attachments=new AtomicInteger(),creates=new AtomicInteger();
    static CanonicalProvider original;private static Object strategy;
    public CanonicalProvider(){if(constructors.incrementAndGet()==1)original=this;}
    @Override public void attachInfo(Context context,ProviderInfo info){
        attachments.incrementAndGet();super.attachInfo(context,info);
        if(strategy!=null)throw new SecurityException("OWNED_PROVIDER_ATTACHED_TWICE");strategy=new Object();
    }
    @Override public boolean onCreate(){creates.incrementAndGet();ProviderOrder.events.add("canonical");return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){Bundle result=new Bundle();result.putInt("creates",creates.get());return result;}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){return null;}
    @Override public String getType(Uri uri){return null;}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
