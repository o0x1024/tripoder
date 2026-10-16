package dev.tripoder.provideridentity;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Owned observation fixture, also installed normally to establish platform behavior. */
public final class MultiprocessProvider extends ContentProvider {
    static final AtomicInteger creations=new AtomicInteger();
    private final String instance=UUID.randomUUID().toString();private String createThread;
    @Override public boolean onCreate(){creations.incrementAndGet();createThread=Thread.currentThread().getName();return true;}
    @Override public Bundle call(String authority,String method,String arg,Bundle extras){
        if(!"identity".equals(method))throw new IllegalArgumentException("METHOD_REQUIRED");
        Bundle out=new Bundle();out.putString("instance",instance);out.putString("createThread",createThread);out.putInt("creations",creations.get());out.putInt("pid",android.os.Process.myPid());out.putInt("uid",android.os.Process.myUid());out.putString("callingPackage",getCallingPackage());return out;
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    @Override public String getType(Uri u){return "owned/multiprocess";}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
