package dev.tripoder.selfhookprobe;

import static dev.tripoder.selfhookprobe.ProbeFiles.*;
import android.app.Activity;
import android.app.Application;
import android.app.AppComponentFactory;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.TextView;
import dalvik.system.DexClassLoader;
import dev.tripoder.selfhook.FrameworkAccess;
import dev.tripoder.selfhook.ApplicationAdapter;
import dev.tripoder.selfhook.PhysicalApplication;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Cold owned application lifecycle, separate from every earlier hook slot. */
public final class ApplicationProbeActivity extends Activity {
    private static int hotReadWrite(Field field,Object owner,Object expected) throws Exception {
        int errors=0;for(int i=0;i<10000;i++){field.set(owner,expected);if(field.get(owner)!=expected)errors++;}return errors;
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);TextView view=new TextView(this);view.setPadding(32,64,32,32);setContentView(view);
        JSONObject report=new JSONObject();File directory=null;boolean loaded=false,owns=false;
        try {
            String runId=UUID.fromString(getIntent().getStringExtra("runId")).toString();
            String compilation=getIntent().getStringExtra("artCompilation");
            require("jit".equals(compilation)||"aot".equals(compilation),"EXPLICIT_ART_COMPILATION_REQUIRED");
            File base=getExternalFilesDir(null);require(base!=null,"EVIDENCE_DIRECTORY_UNAVAILABLE");
            directory=new File(base,runId);require(directory.mkdir(),"RUN_DIRECTORY_EXISTS_OR_UNAVAILABLE");owns=true;
            report.put("schema",1).put("phase","H3f-application").put("runId",runId).put("artCompilation",compilation);
            ProbeEnvironment.load(this,report);loaded=true;
            FrameworkAccess.Members members=FrameworkAccess.prepare();
            Application host=getApplication();ApplicationInfo info=new ApplicationInfo(getApplicationInfo());
            PhysicalApplication.prepare(host,members);PhysicalApplication.check();
            ApplicationAdapter.prepareOwnership(host,members);ApplicationAdapter.beginView();
            info.packageName="dev.tripoder.selfhookprobe.logical";
            File data=new File(getFilesDir(),"logical-application");require(data.isDirectory()||data.mkdir(),"LOGICAL_DATA_DIRECTORY");
            info.dataDir=data.getAbsolutePath();info.processName=info.packageName;
            DexClassLoader definingLoader=new DexClassLoader(info.sourceDir,null,null,ClassLoader.getSystemClassLoader().getParent());
            ClassLoader loader=new ClassLoader(definingLoader){};
            Class<?> factoryType=loader.loadClass(LogicalFactoryFixture.class.getName());
            AppComponentFactory factory=(AppComponentFactory)factoryType.getConstructor().newInstance();
            info.appComponentFactory=factoryType.getName();
            Application guest=factory.instantiateApplication(loader,LogicalApplicationFixture.class.getName());
            require(guest.getClass()!=LogicalApplicationFixture.class&&guest.getClass().getClassLoader()==definingLoader&&
                Class.forName(guest.getClass().getName(),false,loader)==guest.getClass(),"DELEGATING_LOADER_APPLICATION_IDENTITY");
            Context logical=ApplicationAdapter.create(host,guest,info,loader,definingLoader,factory,getResources());
            require(logical.getClass().getName().equals("android.app.ContextImpl")&&logical.getApplicationContext()==null&&
                logical.getClassLoader()==loader&&logical.getPackageName().equals(info.packageName),"LOGICAL_CONTEXT_CONSTRUCTION");
            Context wrapper=new ContextWrapper(new ContextWrapper(logical));
            ApplicationAdapter.attach(wrapper);ApplicationAdapter.check();
            PhysicalApplication.check();
            guest.onCreate();ApplicationAdapter.check();
            PhysicalApplication.check();
            JSONObject fixture=new JSONObject((String)guest.getClass().getMethod("report").invoke(guest));
            report.put("fixture",fixture);
            require(fixture.getInt("constructors")==1&&fixture.getInt("attaches")==1&&fixture.getInt("creates")==1&&fixture.getInt("denials")==2,
                "APPLICATION_LIFECYCLE_AND_VIEW_DENIALS");
            require(factoryType.getField("constructors").getInt(null)==1,"FACTORY_NOT_RECONSTRUCTED");
            Object thread=((Method)members.methods[0]).invoke(null);
            Field initial=members.fields[0];initial.setAccessible(true);
            require(initial.get(thread)==guest&&((Method)members.methods[1]).invoke(null)==host,"LOGICAL_VIEW_PHYSICAL_CURRENT_APPLICATION");
            Method hot=ApplicationProbeActivity.class.getDeclaredMethod("hotReadWrite",Field.class,Object.class,Object.class);
            int primeErrors=0,primeRounds=0;long deadline=SystemClock.elapsedRealtime()+10000;JSONObject before;
            do {primeErrors+=(Integer)hot.invoke(null,initial,thread,guest);before=ArtProbe.sample(hot);primeRounds++;
                if(compilation.equals(ArtCompilation.kind(before)))break;
                require(SystemClock.elapsedRealtime()<deadline,"REQUESTED_COMPILATION_NOT_OBSERVED");Thread.sleep(10);
            }while(true);
            require(primeErrors==0,"COMPILED_APPLICATION_VIEW_PRIME");
            JSONObject countBefore=new JSONObject(ApplicationAdapter.reportNative());
            int errors=0;for(int i=0;i<100;i++)errors+=(Integer)hot.invoke(null,initial,thread,guest);
            JSONObject countAfter=new JSONObject(ApplicationAdapter.reportNative());
            require(errors==0&&countAfter.getLong("mappedGets")-countBefore.getLong("mappedGets")==1000000&&
                countAfter.getLong("mappedSets")-countBefore.getLong("mappedSets")==1000000,"EXACT_COMPILED_REAL_APPLICATION_VIEW_COUNTS");
            report.put("compilationBefore",before).put("compilationAfter",ArtProbe.sample(hot)).put("primeRounds",primeRounds);
            AtomicInteger workerErrors=new AtomicInteger(),physicalWorkerChecks=new AtomicInteger();Thread[] workers=new Thread[4];
            for(int i=0;i<4;i++){workers[i]=new Thread(()->{try{for(int n=0;n<10000;n++){
                    if(initial.get(thread)!=guest)workerErrors.incrementAndGet();
                    if(n%100==0){PhysicalApplication.check();physicalWorkerChecks.incrementAndGet();}}}
                catch(Throwable error){workerErrors.incrementAndGet();}},"tp-application-view-"+i);workers[i].start();}
            for(Thread worker:workers)worker.join();require(workerErrors.get()==0,"APPLICATION_VIEW_CONCURRENT_READS");
            for(int i=0;i<4;i++){byte[][] pressure=new byte[8][];for(int n=0;n<8;n++)pressure[n]=new byte[32768];System.gc();
                require(initial.get(thread)==guest,"APPLICATION_VIEW_AFTER_GC_REQUEST");ApplicationAdapter.check();PhysicalApplication.check();}
            ApplicationAdapter.closeProbeView();ApplicationAdapter.check();
            PhysicalApplication.check();
            require(initial.get(thread)==host&&getApplication()==host,"PHYSICAL_VIEW_RESTORED");
            report.put("workerReads",40000).put("workerErrors",workerErrors.get()).put("factoryConstructors",factoryType.getField("constructors").getInt(null))
                .put("logicalContextCreated",true).put("applicationAttached",true).put("onCreateReturned",true).put("physicalOwnershipPreserved",true)
                .put("viewRestored",true).put("compiledGetCalls",1000000).put("compiledSetCalls",1000000);
            report.put("delegatingSelectedLoader",true).put("physicalWorkerChecks",physicalWorkerChecks.get())
                .put("physicalApplication",new JSONObject(PhysicalApplication.reportNative()));
            String maps=read("/proc/self/maps");write(new File(directory,"maps.txt"),maps);report.put("mapsSha256",sha(new File(directory,"maps.txt")));
            JSONArray forbidden=new JSONArray();for(String line:maps.split("\n")){String lower=line.toLowerCase(Locale.ROOT);
                if(lower.contains("frida")||lower.contains("gadget")||lower.contains("libgum"))forbidden.put(line);}
            report.put("forbiddenMappings",forbidden);require(forbidden.length()==0,"FORBIDDEN_MAPPING");report.put("passed",true);
        }catch(Throwable error){try{report.put("passed",false).put("error",error.toString());
            if(error.getCause()!=null)report.put("cause",error.getCause().toString());}catch(Exception ignored){}}
        try {
            if(loaded){report.put("frameworkAccess",new JSONObject(FrameworkAccess.statusNative())).put("application",new JSONObject(ApplicationAdapter.reportNative()));
                JSONObject state=report.getJSONObject("application");
                if(state.optBoolean("viewEnabled")){ApplicationAdapter.closeProbeView();report.put("failureViewTeardown",new JSONObject(ApplicationAdapter.reportNative()));}}
            if(!owns||directory==null){view.setText("Application probe requires a fresh runId");return;}
            publishResult(directory,report.toString(2)+"\n");
            view.setText("Application "+(report.optBoolean("passed")?"PASS":"FAIL")+"\n"+report.optString("runId"));
        }catch(Throwable error){view.setText("Application evidence failed: "+error);}
    }
}
