package dev.tripoder.isolatedfdprobe;

import android.app.Service;
import android.content.*;
import android.content.res.loader.*;
import android.os.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Set;

/** This APK's own isolated service; it never receives a target app's files. */
public final class IsolatedService extends Service {
    private boolean claimed;
    private boolean bindChallengePresent,bindBinderPresent;
    private final Binder endpoint=new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
        if(code!=ProbeContract.RUN)return super.onTransact(code,data,reply,flags);data.enforceInterface(ProbeContract.DESCRIPTOR);
        Bundle input=data.readBundle(IsolatedService.class.getClassLoader());data.enforceNoDataAvail();int owner=Binder.getCallingUid();
        synchronized(IsolatedService.this){if(claimed||owner!=getApplicationInfo().uid||!android.os.Process.isIsolated())throw new SecurityException("OWNED_ISOLATED_CALLER_REQUIRED");claimed=true;}
        IBinder callback=input.getBinder("callback");new Thread(()->observe(input,callback,owner),"owned-isolated-observer").start();reply.writeNoException();reply.writeBundle(new Bundle());return true;
    }};
    @Override public IBinder onBind(Intent intent){bindChallengePresent=intent!=null&&intent.hasExtra("ownedChallenge");bindBinderPresent=intent!=null&&intent.getExtras()!=null&&intent.getExtras().getBinder("ownedBinder")!=null;return endpoint;}
    private void observe(Bundle input,IBinder callback,int owner){
        Bundle result=new Bundle();String kind=input.getString("kind");result.putString("kind",kind);result.putString("challenge",input.getString("challenge"));result.putInt("uid",android.os.Process.myUid());result.putInt("pid",android.os.Process.myPid());result.putInt("ownerUid",owner);result.putBoolean("isolated",android.os.Process.isIsolated());
        result.putBoolean("factoryServiceCreated",ProbeFactory.serviceCreated);result.putBoolean("factoryIntentPresent",ProbeFactory.intentPresent);result.putBoolean("factoryChallengePresent",ProbeFactory.challengePresent);result.putBoolean("factoryBinderPresent",ProbeFactory.binderPresent);result.putBoolean("bindChallengePresent",bindChallengePresent);result.putBoolean("bindBinderPresent",bindBinderPresent);
        try(var fd=input.getParcelable("fd",ParcelFileDescriptor.class);var dependency=input.getParcelable("dependency",ParcelFileDescriptor.class)){
            if(!Set.of("READ","EXEC_MAP","DLOPEN","DLOPEN_DEPENDENCY","DEX","RESOURCES","MEMFD_EXEC_MAP","MEMFD_DLOPEN","MEMFD_DEPENDENCY","ART_JNI_APK_PLAIN","ART_JNI_APK_NAMED","ART_JNI_PUBLIC_PLAIN","ART_JNI_PUBLIC_NAMED","ART_JNI_PUBLIC_APK_PLAIN","ART_JNI_PUBLIC_APK_NAMED","ART_JNI_OTHER_DIR_PLAIN","ART_JNI_OTHER_DIR_NAMED","ART_JNI_OTHER_APK_PLAIN","ART_JNI_OTHER_APK_NAMED").contains(kind))throw new IllegalArgumentException("OWNED_CASE_INVALID");
            byte[] bytes=ProbeContract.bytes(fd);String digest=ProbeContract.digest(bytes);result.putString("sha256",digest);
            if(!digest.equals(input.getString("sha256")))throw new SecurityException("OWNED_FD_DIGEST_CHANGED");
            boolean denied=false;try(var ignored=new FileInputStream(input.getString("privatePath"))){result.putString("pathAccess","UNEXPECTED_SUCCESS");}catch(FileNotFoundException expected){denied=true;result.putString("pathAccess","DENIED");}result.putBoolean("privatePathDenied",denied);
            boolean passed;
            if(kind.startsWith("ART_JNI_")){
                String directory=Set.of("ART_JNI_PUBLIC_PLAIN","ART_JNI_PUBLIC_NAMED").contains(kind)?getApplicationInfo().nativeLibraryDir:null;
                String archive=kind.startsWith("ART_JNI_PUBLIC_APK_")?getApplicationInfo().sourceDir:null;
                if(kind.startsWith("ART_JNI_OTHER_")){
                    if(!"dev.tripoder.isolatedfdpayload".equals(input.getString("ownedPublicPackage")))throw new SecurityException("OWNED_OTHER_PACKAGE_REQUIRED");
                    if(kind.startsWith("ART_JNI_OTHER_DIR_"))directory=input.getString("ownedPublicDirectory");else archive=input.getString("ownedPublicArchive");
                    String actualArchive=input.getString("ownedPublicArchive");if(actualArchive==null||!digest.equals(ProbeContract.digest(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(actualArchive)))))throw new SecurityException("OWNED_OTHER_APK_DIGEST_CHANGED");
                }
                String jni=JniFdChecks.run(fd,digest,kind.endsWith("_NAMED"),directory,archive);result.putString("artJni",jni);passed=new org.json.JSONObject(jni).getBoolean("passed");
            }else if(kind.equals("DEX")){
                ClassLoader loader=new dalvik.system.InMemoryDexClassLoader(ByteBuffer.wrap(bytes),getClassLoader());
                Class<?> type=Class.forName("dev.tripoder.isolatedfdpayload.Payload",true,loader);
                passed=type.getClassLoader()==loader&&Integer.valueOf(37).equals(type.getMethod("answer").invoke(null));
            }else if(kind.equals("RESOURCES")){
                var resources=createConfigurationContext(new android.content.res.Configuration(getResources().getConfiguration())).getResources();
                try(var provider=ResourcesProvider.loadFromApk(fd)){
                    ResourcesLoader loader=new ResourcesLoader();loader.addProvider(provider);resources.addLoaders(loader);
                    try{int id=resources.getIdentifier("owned_fd_value","string","dev.tripoder.isolatedfdpayload");passed=id!=0&&"OWNED_FD_RESOURCE_OK".equals(resources.getString(id));}
                    finally{resources.removeLoaders(loader);loader.removeProvider(provider);}
                }
            }else{
                if(dependency!=null&&!ProbeContract.digest(ProbeContract.bytes(dependency)).equals(input.getString("dependencySha256")))throw new SecurityException("OWNED_DEPENDENCY_DIGEST_CHANGED");
                String nativeResult=NativeChecks.run(kind,fd.getFd(),dependency==null?-1:dependency.getFd());result.putString("native",nativeResult);passed=nativeResult.startsWith("PASS:");
            }
            result.putBoolean("passed",passed&&denied&&owner!=android.os.Process.myUid());
        }catch(Throwable failure){result.putBoolean("passed",false);result.putString("errorType",failure.getClass().getName());result.putString("frames",java.util.Arrays.toString(java.util.Arrays.copyOf(failure.getStackTrace(),Math.min(10,failure.getStackTrace().length))));}
        try{ProbeContract.call(callback,ProbeContract.RESULT,result);}catch(Exception ignored){android.util.Log.e("OwnedIsolatedFD","RESULT_UNCONFIRMED:"+kind);}
    }
}
