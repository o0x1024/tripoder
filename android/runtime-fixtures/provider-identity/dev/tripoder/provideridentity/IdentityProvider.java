package dev.tripoder.provideridentity;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class IdentityProvider extends ContentProvider {
    private static final AtomicInteger creations=new AtomicInteger();
    private static final AtomicInteger calls=new AtomicInteger();
    private final String instance=UUID.randomUUID().toString();
    private String attachedAuthority,label;
    private final PrivateProviderDelegate delegate=new PrivateProviderDelegate();
    @Override public void attachInfo(Context context,ProviderInfo info){attachedAuthority=info.authority;label=info.metaData==null?"missing":info.metaData.getString("identity.label");super.attachInfo(context,info);delegate.attachInfo(context,info);}
    @Override public boolean onCreate(){creations.incrementAndGet();return true;}
    @Override public Bundle call(String authority,String method,String arg,Bundle extras){
        if(!"identity".equals(method))throw new IllegalArgumentException("OWNED_METHOD_REQUIRED");
        Bundle out=new Bundle();out.putString("instance",instance);out.putString("attachedAuthority",attachedAuthority);out.putString("authorityArgument",authority);out.putString("label",label);out.putInt("creations",creations.get());out.putInt("calls",calls.incrementAndGet());out.putInt("pid",android.os.Process.myPid());out.putInt("uid",android.os.Process.myUid());out.putString("callingPackage",getCallingPackage());
        out.putInt("delegateCreates",delegate.creates);out.putString("delegatePackage",delegate.getContext().getPackageName());
        boolean available;try{available=delegate.getContext().getSystemService(Context.APP_OPS_SERVICE)!=null;}catch(UnsupportedOperationException denied){available=false;}out.putBoolean("delegateAppOpsAvailableToGuest",available);return out;
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    @Override public String getType(Uri uri){return "owned/identity";}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
