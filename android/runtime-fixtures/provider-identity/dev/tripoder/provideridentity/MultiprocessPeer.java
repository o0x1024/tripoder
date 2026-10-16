package dev.tripoder.provideridentity;

import android.app.Service;
import android.content.*;
import android.net.Uri;
import android.os.*;
import org.json.*;

public final class MultiprocessPeer extends Service {
    @Override public IBinder onBind(Intent intent){return new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){if(code!=1)throw new IllegalArgumentException("CODE_REQUIRED");try{data.enforceNoDataAvail();String result=sample(MultiprocessPeer.this).toString();reply.writeNoException();reply.writeString(result);}catch(Exception error){reply.writeException(new IllegalStateException(error));}return true;}};}
    static JSONObject sample(Context context)throws Exception{
        JSONObject result=new JSONObject().put("pid",android.os.Process.myPid()).put("before",MultiprocessProvider.creations.get());
        Uri uri=Uri.parse("content://dev.tripoder.identity.multi");
        for(String key:new String[]{"first","again"}){
            Bundle value=context.getContentResolver().call(uri,"identity",null,null);if(value==null)throw new IllegalStateException("NULL_PROVIDER_RESULT");
            JSONObject observed=new JSONObject();for(String field:value.keySet())observed.put(field,value.get(field));result.put(key,observed);
        }
        return result.put("after",MultiprocessProvider.creations.get());
    }
}
