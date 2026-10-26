package dev.tripoder.isolatedfdprobe;

import android.app.Activity;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.widget.TextView;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** One isolated instance per case; a crash remains UNKNOWN and never changes UID. */
public final class OwnerActivity extends Activity {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();private TextView text;
    private final JSONArray observations=new JSONArray();private File evidence;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);text=new TextView(this);text.setText("RUNNING OWNED ISOLATED FD CHECKS");setContentView(text);
        executor.execute(()->{try{runCases();}catch(Throwable failure){try{save("failed",new JSONObject().put("errorType",failure.getClass().getName()).put("message",String.valueOf(failure.getMessage())).put("frames",Arrays.toString(Arrays.copyOf(failure.getStackTrace(),Math.min(10,failure.getStackTrace().length)))));}catch(Exception ignored){}}});
    }
    private void save(String phase,JSONObject extra)throws Exception{
        JSONObject report=new JSONObject().put("phase",phase).put("ownerUid",android.os.Process.myUid()).put("ownerPid",android.os.Process.myPid()).put("dataCleared",false).put("cases",observations).put("detail",extra);
        Files.writeString(evidence.toPath(),report.toString(2));runOnUiThread(()->text.setText(phase+"\n"+observations));
    }
    private void runCases()throws Exception{
        String run=UUID.randomUUID().toString();File directory=new File(getFilesDir(),"owned-"+run);if(!directory.mkdir())throw new IOException("OWNED_DIRECTORY_FAILED");
        evidence=new File(getExternalFilesDir(null),"isolated-fd-"+run+".json");
        ServiceInfo service=getPackageManager().getServiceInfo(new ComponentName(this,IsolatedService.class),0);
        if((service.flags&ServiceInfo.FLAG_ISOLATED_PROCESS)==0||service.exported)throw new SecurityException("PRIVATE_ISOLATED_MANIFEST_REQUIRED");
        Map<String,File> inputs=new HashMap<>();
        for(String name:new String[]{"libowned_fd_payload.so","libowned_fd_dependent.so","payload.dex","payload-resources.apk","payload-jni.apk","payload-other.apk"}){
            File file=new File(directory,name);try(var source=getAssets().open(name)){Files.copy(source,file.toPath());}
            if(!file.setReadOnly())throw new IOException("OWNED_READONLY_REQUIRED");inputs.put(name,file);
        }
        save("running",new JSONObject().put("manifestIsolated",true));
        for(String kind:new String[]{"READ","EXEC_MAP","DLOPEN","DLOPEN_DEPENDENCY","DEX","RESOURCES","MEMFD_EXEC_MAP","MEMFD_DLOPEN","MEMFD_DEPENDENCY","ART_JNI_APK_PLAIN","ART_JNI_APK_NAMED","ART_JNI_PUBLIC_PLAIN","ART_JNI_PUBLIC_NAMED","ART_JNI_PUBLIC_APK_PLAIN","ART_JNI_PUBLIC_APK_NAMED","ART_JNI_OTHER_DIR_PLAIN","ART_JNI_OTHER_DIR_NAMED","ART_JNI_OTHER_APK_PLAIN","ART_JNI_OTHER_APK_NAMED"}){
            boolean dependency=kind.equals("DLOPEN_DEPENDENCY")||kind.equals("MEMFD_DEPENDENCY");
            String name=kind.startsWith("ART_JNI_OTHER_")?"payload-other.apk":kind.startsWith("ART_JNI_")?"payload-jni.apk":kind.equals("DEX")?"payload.dex":kind.equals("RESOURCES")?"payload-resources.apk":dependency?"libowned_fd_dependent.so":"libowned_fd_payload.so";
            observations.put(one(kind,inputs.get(name),dependency?inputs.get("libowned_fd_payload.so"):null));save("running",new JSONObject());
        }
        save("complete",new JSONObject().put("manifestIsolated",true));
    }
    private JSONObject one(String kind,File file,File dependency)throws Exception{
        String challenge=UUID.randomUUID().toString(),digest=ProbeContract.digest(Files.readAllBytes(file.toPath()));
        CompletableFuture<JSONObject> result=new CompletableFuture<>();CountDownLatch connected=new CountDownLatch(1);IBinder[] peer=new IBinder[1];
        ServiceConnection connection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName component,IBinder binder){peer[0]=binder;connected.countDown();}
            @Override public void onServiceDisconnected(ComponentName component){result.complete(new JSONObject(Map.of("kind",kind,"state","UNKNOWN","reason","ISOLATED_PROCESS_DISCONNECTED")));}
            @Override public void onBindingDied(ComponentName component){onServiceDisconnected(component);}
            @Override public void onNullBinding(ComponentName component){onServiceDisconnected(component);connected.countDown();}
        };
        // Callback dispatch cannot share the single thread waiting for it.
        Bundle creation=new Bundle();creation.putString("ownedChallenge",challenge);creation.putBinder("ownedBinder",new Binder());
        boolean bound=bindIsolatedService(new Intent(this,IsolatedService.class).putExtras(creation),BIND_AUTO_CREATE,"owned_"+challenge.replace("-",""),getMainExecutor(),connection);
        if(!bound)return new JSONObject().put("kind",kind).put("state","UNKNOWN").put("reason","ISOLATED_BIND_REJECTED");
        try(var fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);var dep=dependency==null?null:ParcelFileDescriptor.open(dependency,ParcelFileDescriptor.MODE_READ_ONLY)){
            if(!connected.await(12,TimeUnit.SECONDS)||peer[0]==null)return new JSONObject().put("kind",kind).put("state","UNKNOWN").put("reason","ISOLATED_BIND_UNCONFIRMED");
            Binder callback=new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
                if(code!=ProbeContract.RESULT)return super.onTransact(code,data,reply,flags);data.enforceInterface(ProbeContract.DESCRIPTOR);
                int actualUid=Binder.getCallingUid(),actualPid=Binder.getCallingPid();Bundle observed=data.readBundle(ProbeContract.class.getClassLoader());data.enforceNoDataAvail();
                try{
                    if(actualUid==android.os.Process.myUid()||!android.os.Process.isIsolatedUid(actualUid)||observed.getInt("uid")!=actualUid||observed.getInt("pid")!=actualPid||observed.getInt("ownerUid")!=android.os.Process.myUid()||!observed.getBoolean("isolated")||!challenge.equals(observed.getString("challenge"))||!kind.equals(observed.getString("kind")))throw new SecurityException("REAL_ISOLATED_CALLBACK_IDENTITY_MISMATCH");
                    JSONObject row=new JSONObject().put("kind",kind).put("actualUid",actualUid).put("actualPid",actualPid).put("isolated",true).put("sourceSha256",digest).put("observedSha256",observed.getString("sha256")).put("state",observed.getBoolean("passed")?"PASS":"FAIL").put("privatePathDenied",observed.getBoolean("privatePathDenied"));
                    for(String key:new String[]{"native","errorType","frames"})if(observed.containsKey(key))row.put(key,observed.getString(key));
                    if(observed.containsKey("artJni"))row.put("artJni",new JSONObject(observed.getString("artJni")));
                    for(String key:new String[]{"factoryServiceCreated","factoryIntentPresent","factoryChallengePresent","factoryBinderPresent","bindChallengePresent","bindBinderPresent"})row.put(key,observed.getBoolean(key));
                    result.complete(row);
                }catch(Exception failure){result.completeExceptionally(failure);}
                reply.writeNoException();reply.writeBundle(new Bundle());return true;
            }};
            Bundle input=new Bundle();input.putString("kind",kind);input.putString("challenge",challenge);input.putString("sha256",digest);input.putString("privatePath",file.toString());input.putParcelable("fd",fd);input.putBinder("callback",callback);
            if(kind.startsWith("ART_JNI_OTHER_")){
                var other=getPackageManager().getApplicationInfo("dev.tripoder.isolatedfdpayload",0);
                if(!other.packageName.equals("dev.tripoder.isolatedfdpayload")||!digest.equals(ProbeContract.digest(Files.readAllBytes(java.nio.file.Path.of(other.sourceDir)))))throw new SecurityException("OWNED_OTHER_INSTALLED_APK_CHANGED");
                input.putString("ownedPublicDirectory",other.nativeLibraryDir);input.putString("ownedPublicArchive",other.sourceDir);input.putString("ownedPublicPackage",other.packageName);
            }
            if(dep!=null){input.putParcelable("dependency",dep);input.putString("dependencySha256",ProbeContract.digest(Files.readAllBytes(dependency.toPath())));}
            // Keep the owner's orchestration deadline independent of a stalled Binder call.
            Thread dispatch=new Thread(()->{try{ProbeContract.call(peer[0],ProbeContract.RUN,input);}catch(Exception failure){result.complete(new JSONObject(Map.of("kind",kind,"state","UNKNOWN","reason",failure.getClass().getName())));}},"owned-probe-dispatch");dispatch.start();
            try{return result.get(18,TimeUnit.SECONDS);}catch(TimeoutException unknown){return new JSONObject().put("kind",kind).put("state","UNKNOWN").put("reason","OBSERVATION_DEADLINE");}
        }finally{unbindService(connection);}
    }
}
