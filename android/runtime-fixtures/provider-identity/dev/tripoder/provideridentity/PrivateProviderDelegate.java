package dev.tripoder.provideridentity;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;

/** An unpublished helper instantiated by a declared provider; no separate authority. */
final class PrivateProviderDelegate extends ContentProvider {
    int creates;
    @Override public boolean onCreate(){creates++;return true;}
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    @Override public String getType(Uri u){throw new UnsupportedOperationException();}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
