package dev.tripoder.isolatedfdprobe;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import dev.tripoder.isolatedfdpayload.NativePayload;
import org.json.JSONObject;

/** JNI is invoked by a class in the actual guest defining loader, not another probe loader. */
public final class ProductionService extends Service {
    static final String WIRE="dev.tripoder.owned.production.native";private boolean named,claimed;
    @Override public IBinder onBind(Intent intent){
        if(intent==null||!new android.content.ComponentName(getPackageName(),getClass().getName()).equals(intent.getComponent()))throw new SecurityException("OWNED_PRODUCTION_LOGICAL_INTENT_REQUIRED");
        named="OWNED_NAMED".equals(intent.getAction());return endpoint;
    }
    private final Binder endpoint=new Binder(){@Override protected boolean onTransact(int code,Parcel request,Parcel reply,int flags)throws RemoteException{
        if(code!=1)return super.onTransact(code,request,reply,flags);request.enforceInterface(WIRE);String challenge=request.readString();IBinder callback=request.readStrongBinder();request.enforceNoDataAvail();
        synchronized(ProductionService.this){if(claimed||Binder.getCallingUid()!=getApplicationInfo().uid||!android.os.Process.isIsolated()||callback==null||challenge==null)throw new SecurityException("OWNED_PRODUCTION_CALLER_REQUIRED");claimed=true;}
        try{
            var info=getApplicationInfo();boolean extracted=(info.flags&0x10000000)!=0;String root=extracted?info.nativeLibraryDir:info.sourceDir+"!/lib/arm64-v8a";
            JSONObject observed=new JSONObject(NativePayload.run(root,named));
            observed.put("actualGuestDefiningLoader",NativePayload.class.getClassLoader()==getClass().getClassLoader()&&getClass().getClassLoader()==getClassLoader()).put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid()).put("ownerUid",info.uid).put("challenge",challenge).put("nativeMode",extracted?"DIRECTORY":"APK");
            Parcel data=Parcel.obtain(),response=Parcel.obtain();try{data.writeInterfaceToken(WIRE);data.writeString(observed.toString());if(!callback.transact(1,data,response,0))throw new RemoteException("OWNED_PRODUCTION_CALLBACK_REJECTED");response.readException();}finally{data.recycle();response.recycle();}
            reply.writeNoException();return true;
        }catch(Exception failure){throw new IllegalStateException("OWNED_PRODUCTION_NATIVE_FAILED",failure);}
    }};
}
