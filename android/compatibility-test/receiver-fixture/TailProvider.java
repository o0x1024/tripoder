package dev.tripoder.closurefixture;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;

public final class TailProvider extends ContentProvider {
    @Override public boolean onCreate(){ProviderOrder.events.add("tail");return true;}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){return null;}
    @Override public String getType(Uri uri){return null;}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
