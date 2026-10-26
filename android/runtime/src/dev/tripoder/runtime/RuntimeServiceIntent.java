package dev.tripoder.runtime;

import android.content.Intent;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Parcel;
import java.security.MessageDigest;
import java.util.*;

/** One captured Android Parcel is both the request identity and the transported payload. */
final class RuntimeServiceIntent {
    static final String PAYLOAD="tripoder.servicePayload";
    static final class Request {
        final Intent intent;final String digest;private final byte[] encoded;
        private Request(Intent intent,String digest,byte[] encoded){this.intent=intent;this.digest=digest;this.encoded=encoded;}
        byte[] payload(){if(encoded==null)throw new IllegalStateException("SERVICE_SNAPSHOT_REQUIRED");return encoded.clone();}
    }
    static Request thin(Intent intent){return new Request(intent,null,null);}
    static Request snapshot(Intent input)throws Exception{
        Intent captured=new Intent(Objects.requireNonNull(input,"SERVICE_INTENT_REQUIRED"));
        if(input.getExtras()!=null)captured.replaceExtras(new Bundle(input.getExtras()));
        shape(captured);Intent metadata=new Intent(captured).replaceExtras((Bundle)null);
        Parcel parcel=Parcel.obtain();
        try{
            captured.writeToParcel(parcel,0);
            if(parcel.hasFileDescriptors())throw new IllegalArgumentException("SERVICE_PAYLOAD_FILE_DESCRIPTORS_UNSUPPORTED");
            if(parcel.dataSize()>48*1024)throw new IllegalArgumentException("SERVICE_INTENT_TOO_LARGE");
            // marshall refuses Binder/object-table capabilities. They are not
            // silently converted into bytes or dropped from the request.
            byte[] encoded=parcel.marshall();
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
            return new Request(metadata,digest,encoded);
        }finally{parcel.recycle();}
    }
    static Intent restore(byte[] encoded,ClassLoader loader){
        if(encoded==null||encoded.length==0||encoded.length>48*1024)throw new IllegalArgumentException("SERVICE_PAYLOAD_INVALID");
        Parcel parcel=Parcel.obtain();
        try{
            parcel.unmarshall(encoded,0,encoded.length);parcel.setDataPosition(0);
            Intent restored=Intent.CREATOR.createFromParcel(parcel);
            if(parcel.dataAvail()!=0)throw new IllegalArgumentException("SERVICE_PAYLOAD_TRAILING_DATA");
            restored.setExtrasClassLoader(Objects.requireNonNull(loader,"SERVICE_PAYLOAD_LOADER_REQUIRED"));
            shape(restored);return restored;
        }finally{parcel.recycle();}
    }
    static Intent delivery(Intent physical,ComponentName logical,ClassLoader loader){
        Intent restored=restore(physical.getByteArrayExtra(PAYLOAD),loader);
        if(!logical.equals(restored.getComponent()))throw new SecurityException("SERVICE_PAYLOAD_COMPONENT_MISMATCH");
        Intent selector=new Intent(physical).replaceExtras((Bundle)null).setComponent(logical);
        if(!selector.filterEquals(restored))throw new SecurityException("SERVICE_PAYLOAD_SELECTOR_MISMATCH");
        return restored;
    }
    private static void shape(Intent intent){
        if(intent.getComponent()==null||intent.getData()!=null||intent.getType()!=null||intent.getClipData()!=null||intent.getSelector()!=null||intent.getFlags()!=0||intent.getCategories()!=null||intent.getPackage()!=null)throw new IllegalArgumentException("SERVICE_INTENT_SHAPE_UNSUPPORTED");
        Bundle extras=intent.getExtras();
        if(extras!=null){
            if(extras.size()>16||extras.hasFileDescriptors())throw new IllegalArgumentException("SERVICE_INTENT_TOO_LARGE");
            for(String key:extras.keySet())if(key==null||key.startsWith("tripoder.")||key.length()>100)throw new IllegalArgumentException("SERVICE_EXTRA_KEY_UNSUPPORTED");
        }
    }
    private RuntimeServiceIntent(){}
}
