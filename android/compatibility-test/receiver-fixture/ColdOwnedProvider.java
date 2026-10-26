package dev.tripoder.closurefixture;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import java.util.concurrent.atomic.AtomicInteger;

/** A distinct process first reached through a provider, with only synthetic lifecycle counters. */
public final class ColdOwnedProvider extends ContentProvider {
    private static final AtomicInteger constructors=new AtomicInteger(),attachments=new AtomicInteger(),creates=new AtomicInteger();
    private static volatile boolean createdOnMain;
    public ColdOwnedProvider(){constructors.incrementAndGet();}
    @Override public void attachInfo(Context context,ProviderInfo info){attachments.incrementAndGet();super.attachInfo(context,info);}
    @Override public boolean onCreate(){creates.incrementAndGet();createdOnMain=android.os.Looper.myLooper()==android.os.Looper.getMainLooper();return true;}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
        MatrixCursor rows=new MatrixCursor(new String[]{"pid","constructors","attachments","creates","main","caller"});
        rows.addRow(new Object[]{android.os.Process.myPid(),constructors.get(),attachments.get(),creates.get(),createdOnMain?1:0,getCallingPackage()});return rows;
    }
    @Override public String getType(Uri uri){return "vnd.android.cursor.item/vnd.tripoder.cold-owned";}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
}
