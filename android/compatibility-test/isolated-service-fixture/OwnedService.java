package dev.tripoder.isolatedservicecontract;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.system.*;
import java.util.UUID;

/** Only this fixture's own paths and Binder identities are inspected. */
public final class OwnedService extends Service {
    static final String WIRE="dev.tripoder.owned.isolated.service";
    private final String instance=UUID.randomUUID().toString();private int creates,binds,privateErrno;private boolean main,privateDenied,storageDenied,pmDenied,contextLoader;
    @Override public void onCreate(){
        super.onCreate();creates++;main=Looper.myLooper()==Looper.getMainLooper();contextLoader=Thread.currentThread().getContextClassLoader()==getClass().getClassLoader();
        try{var fd=Os.open(getApplicationInfo().dataDir+"/files/owned-isolated-marker",OsConstants.O_RDONLY,0);Os.close(fd);}catch(ErrnoException error){privateErrno=error.errno;privateDenied=error.errno==OsConstants.EACCES||error.errno==OsConstants.EPERM;}
        try{getFilesDir();}catch(UnsupportedOperationException expected){storageDenied=true;}
        try{getPackageManager();}catch(UnsupportedOperationException expected){pmDenied=true;}
    }
    @Override public IBinder onBind(Intent intent){binds++;if(!new android.content.ComponentName(getPackageName(),getClass().getName()).equals(intent.getComponent()))throw new SecurityException("OWNED_LOGICAL_SERVICE_INTENT_REQUIRED");return endpoint;}
    private final Binder endpoint=new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
        if(code!=1)return super.onTransact(code,data,reply,flags);data.enforceInterface(WIRE);ParcelFileDescriptor witness=data.readTypedObject(ParcelFileDescriptor.CREATOR);data.enforceNoDataAvail();
        Bundle out=new Bundle();out.putInt("pid",android.os.Process.myPid());out.putInt("uid",android.os.Process.myUid());out.putBoolean("isolated",android.os.Process.isIsolated());out.putInt("callerUid",Binder.getCallingUid());out.putInt("ownerUid",getApplicationInfo().uid);out.putInt("creates",creates);out.putInt("appCreates",OwnedApplication.creates);out.putInt("binds",binds);out.putString("instance",instance);out.putBoolean("main",main);out.putInt("privateErrno",privateErrno);out.putBoolean("privateDenied",privateDenied);out.putBoolean("storageDenied",storageDenied);out.putBoolean("pmDenied",pmDenied);
        try(witness){
            if(witness==null)throw new SecurityException("OWNED_PRIVATE_FILE_FD_REQUIRED");
            var stat=Os.fstat(witness.getFileDescriptor());byte[] bytes=new byte[64];int count=Os.pread(witness.getFileDescriptor(),bytes,0,bytes.length,0);
            boolean verified=stat.st_uid==Binder.getCallingUid()&&stat.st_nlink==1&&(stat.st_mode&0777)==0600&&"OWNED_PRIVATE_BOUNDARY\n".equals(new String(bytes,0,count,java.nio.charset.StandardCharsets.UTF_8));
            String label=Os.readlink("/proc/self/fd/"+witness.getFd());boolean canonical=label.startsWith("/data/")&&!label.endsWith(" (deleted)");int error=0;
            try{var opened=Os.open(label,OsConstants.O_RDONLY,0);Os.close(opened);}catch(ErrnoException denied){error=denied.errno;}
            out.putInt("declaredPathErrno",privateErrno);out.putBoolean("privateFdVerified",verified);out.putBoolean("privatePathLabelCanonical",canonical);out.putInt("privateErrno",error);out.putBoolean("privateDenied",verified&&canonical&&(error==OsConstants.EACCES||error==OsConstants.EPERM||error==OsConstants.ENOENT));
        }catch(Exception failure){throw new IllegalStateException("OWNED_PRIVATE_FD_CHECK_FAILED",failure);}
        out.putBoolean("physicalStopResult",stopSelfResult(-1));
        out.putBoolean("factoryCreation",OwnedFactory.loaders==1&&OwnedFactory.applications==1&&OwnedFactory.services==1&&OwnedFactory.serviceIntentNull);
        out.putBoolean("applicationIdentity",getApplication()!=null&&getApplicationContext()==getApplication()&&getApplication().getClass().getClassLoader()==getClass().getClassLoader()&&getApplication().getPackageName().equals(getPackageName()));
        out.putBoolean("contextLoader",contextLoader);try{out.putInt("dexMarker",(Integer)Class.forName("dev.tripoder.isolatedservicecontract.OwnedDexMarker",true,getClassLoader()).getMethod("answer").invoke(null));}catch(Exception error){throw new IllegalStateException(error);}out.putString("resource",getString(getResources().getIdentifier("owned_isolated_marker","string",getPackageName())));reply.writeNoException();reply.writeBundle(out);return true;
    }};
}
