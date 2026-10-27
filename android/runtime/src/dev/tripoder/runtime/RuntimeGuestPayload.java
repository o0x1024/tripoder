package dev.tripoder.runtime;

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import java.util.Objects;

/** Guest-process ingress only. Re-parcelling preserves opaque lazy values and
 * Binder handles while discarding a LazyValue's previously captured boot loader.
 * Merely calling setClassLoader after metadata was read cannot do that. */
final class RuntimeGuestPayload {
    static Bundle copy(Bundle source,ClassLoader loader){
        if(source==null)return null;
        Objects.requireNonNull(loader,"GUEST_PAYLOAD_LOADER_REQUIRED");
        Parcel parcel=Parcel.obtain();
        try{
            parcel.writeBundle(source);
            parcel.setDataPosition(0);
            Bundle result=parcel.readBundle(loader);
            if(parcel.dataAvail()!=0)throw new IllegalStateException("GUEST_PAYLOAD_TRAILING_DATA");
            return result;
        }finally{parcel.recycle();}
    }
    static Intent prepare(Intent intent,ClassLoader loader){
        if(intent==null)return null;
        // getExtras copies the Bundle without resolving its values. readBundle
        // passes this loader to nested bundles when they are eventually read.
        Bundle extras=intent.getExtras();
        if(extras!=null)intent.replaceExtras(copy(extras,loader));
        intent.setExtrasClassLoader(loader);
        return intent;
    }
    private RuntimeGuestPayload(){}
}
