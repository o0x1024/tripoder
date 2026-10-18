package dev.tripoder.selfhookprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dalvik.system.DexClassLoader;
import dev.tripoder.selfhook.ManagedHook;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.json.*;
/** The exact owned APK/class/signature from the historical controller failure. */
public final class ManagedRealGuestProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView view=new TextView(this);setContentView(view);String id=getIntent().getStringExtra("runId");new Thread(()->run(id,view),"owned-real-guest-entry").start();}
    private static void copy(InputStream in,File file)throws Exception{try(in;var out=new FileOutputStream(file)){in.transferTo(out);out.getFD().sync();}ProbeFiles.require(file.setReadOnly(),"READ_ONLY_INPUT");}
    private void run(String id,TextView view){JSONObject report=new JSONObject();File evidence=null;
        try{String run=UUID.fromString(id).toString();evidence=new File(getExternalFilesDir(null),run);ProbeFiles.require(evidence.mkdir(),"FRESH_REAL_GUEST_RUN");
            report.put("schema",1).put("phase","managed-real-guest").put("runId",run);ProbeEnvironment.load(this,report);ManagedHook.initializeProcess();
            Map<Executable,Executable[]> occupied=new LinkedHashMap<>();for(int i=0;i<2215;i++){Class<?> owner=Class.forName("dev.tripoder.selfhookprobe.capacity.Group"+(i/128));occupied.put(owner.getDeclaredMethod("value"+(i%128),int.class),new Executable[0]);}
            var handles=ManagedHook.prepareBatch(occupied);Map<ManagedHook.Handle,ManagedHook.Handler> active=new LinkedHashMap<>();for(var handle:handles.values())active.put(handle,c->c.original(c.arguments()));ManagedHook.enableBatch(active);
            File work=new File(getCodeCacheDir(),"real-guest-"+run);ProbeFiles.require(work.mkdir(),"FRESH_REAL_GUEST_CODE");File apk=new File(work,"guest.apk");copy(getAssets().open("managed-owned-guest.apk"),apk);
            report.put("guestApkSha256",ProbeFiles.sha(apk));ProbeFiles.require(report.getString("guestApkSha256").equals(report.getJSONObject("build").getString("managedGuestSha256")),"EXACT_GUEST_APK");
            File library=new File(work,"libp0fixture.so");try(var zip=new ZipFile(apk)){copy(zip.getInputStream(zip.getEntry("lib/arm64-v8a/libp0fixture.so")),library);}
            ClassLoader loader=new DexClassLoader(apk.toString(),work.toString(),work.toString(),getClassLoader());Class<?> owner=Class.forName("dev.tripoder.p0.shared.NativeProbe",true,loader);
            ProbeFiles.require(owner.getClassLoader()==loader,"REAL_DEX_CLASS_LOADER");File file=new File(getFilesDir(),"real-guest-"+run+".txt");
            ProbeFiles.require("owned".equals(owner.getDeclaredMethod("writeRead",String.class,String.class).invoke(null,file.toString(),"owned")),"REAL_GUEST_INITIAL_NATIVE_CALL");
            Method selected=null;for(Method candidate:owner.getDeclaredMethods())if(candidate.getName().equals("canRead")&&Arrays.equals(candidate.getParameterTypes(),new Class<?>[]{String.class}))selected=candidate;
            if(selected==null)throw new AssertionError("REAL_TARGET");Method target=selected;report.put("beforeIdle",new JSONArray(ManagedVisibilityProbeActivity.observe(target)));
            ProbeFiles.write(new File(evidence,"progress.json"),report.toString());Thread.sleep(31000);report.put("afterIdle",new JSONArray(ManagedVisibilityProbeActivity.observe(target)));
            ManagedHook.Handle handle=ManagedHook.prepare(target,new Method[0]);report.put("prepared",new JSONObject(handle.report()));ProbeFiles.write(new File(evidence,"before-enable.json"),report.toString(2));
            handle.enable(c->false);ProbeFiles.require(Boolean.FALSE.equals(target.invoke(null,file.toString())),"REAL_HOOK_VALUE");handle.disable();ProbeFiles.require(Boolean.TRUE.equals(target.invoke(null,file.toString())),"REAL_ORIGINAL_VALUE");
            report.put("retired",new JSONObject(handle.report())).put("prefilledEntries",2215).put("idleMs",31000);ManagedHook.disableBatch(handles.values());
            File maps=new File(evidence,"maps.txt");ProbeFiles.write(maps,ProbeFiles.read("/proc/self/maps"));report.put("mapsSha256",ProbeFiles.sha(maps)).put("passed",true);
        }catch(Throwable error){try{report.put("passed",false).put("error",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}}
        try{if(evidence!=null)ProbeFiles.publishResult(evidence,report.toString(2)+"\n");String text=report.toString();runOnUiThread(()->view.setText(text));}catch(Throwable error){runOnUiThread(()->view.setText(error.toString()));}
    }
}
