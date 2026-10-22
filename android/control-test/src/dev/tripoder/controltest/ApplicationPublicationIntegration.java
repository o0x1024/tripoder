package dev.tripoder.controltest;

import android.app.*;
import android.content.*;
import android.content.pm.ApplicationInfo;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicReference;

/** Generic cold Application lifecycle, independent of any third-party package. */
public final class ApplicationPublicationIntegration extends ControlTestBase {
    public static final class Guest extends Application {
        int attaches,creates;boolean prematureInitialization;public int foreignValue=7;
        @Override protected void attachBaseContext(Context base){
            attaches++;
            // Startup libraries commonly select an application-wide implementation only once one exists.
            prematureInitialization=base.getApplicationContext()!=null;
            if(prematureInitialization)throw new IllegalStateException("APPLICATION_PUBLISHED_BEFORE_ATTACH");
            super.attachBaseContext(base);
            if(getApplicationContext()!=null)throw new IllegalStateException("APPLICATION_PUBLISHED_INSIDE_ATTACH");
        }
        @Override public void onCreate(){creates++;if(getApplicationContext()!=this)throw new IllegalStateException("ATTACHED_APPLICATION_NOT_PUBLISHED");}
    }
    @Override void test()throws Exception{
        report.put("scope","generic-application-attach-publication-contract");
        AtomicReference<Throwable> failure=new AtomicReference<>();
        runOnMainSync(()->{try{
            System.loadLibrary("tripoderselfhook");
            Class<?> framework=Class.forName("dev.tripoder.selfhook.FrameworkAccess"),adapter=Class.forName("dev.tripoder.selfhook.ApplicationAdapter");
            Object members=framework.getMethod("prepare").invoke(null);Class<?> memberType=members.getClass();
            Application host=(Application)getTargetContext().getApplicationContext();
            adapter.getMethod("prepareOwnership",Application.class,memberType).invoke(null,host,members);
            adapter.getMethod("beginView").invoke(null);
            Guest guest=new Guest();ApplicationInfo info=new ApplicationInfo(getTargetContext().getApplicationInfo());
            ClassLoader loader=guest.getClass().getClassLoader();
            Context logical=(Context)adapter.getMethod("create",Application.class,Application.class,ApplicationInfo.class,ClassLoader.class,ClassLoader.class,AppComponentFactory.class,android.content.res.Resources.class)
                .invoke(null,host,guest,info,loader,loader,new AppComponentFactory(),getTargetContext().getResources());
            long decodes=new org.json.JSONObject((String)adapter.getMethod("reportNative").invoke(null)).getLong("metadataDecodes");
            Field foreign=Guest.class.getDeclaredField("foreignValue");foreign.setAccessible(true);foreign.set(guest,19);
            check("foreign-field-keeps-original-reflection-behavior",foreign.get(guest).equals(19),true);
            check("foreign-owner-does-not-decode-field-metadata",new org.json.JSONObject((String)adapter.getMethod("reportNative").invoke(null)).getLong("metadataDecodes")==decodes,true);
            check("application-context-null-before-attach",logical.getApplicationContext()==null,true);
            check("current-application-null-before-attach",adapter.getMethod("current").invoke(null)==null,true);
            adapter.getMethod("attach",Context.class).invoke(null,new ContextWrapper(logical));
            check("attach-did-not-run-premature-application-path",guest.attaches==1&&!guest.prematureInitialization,true);
            check("application-context-published-after-attach",logical.getApplicationContext()==guest,true);
            check("current-application-published-after-attach",adapter.getMethod("current").invoke(null)==guest,true);
            guest.onCreate();adapter.getMethod("check").invoke(null);
            check("on-create-runs-once-after-publication",guest.creates==1,true);
            Method publish=adapter.getMethod("publishApplication",Application.class);
            publish.invoke(null,guest);
            check("same-owner-publication-preserves-identity",logical.getApplicationContext()==guest&&adapter.getMethod("current").invoke(null)==guest,true);
            boolean rejected=false;try{publish.invoke(null,new Application());}catch(InvocationTargetException expected){rejected=expected.getCause() instanceof IllegalStateException;}
            check("unattached-publication-rejected",rejected&&logical.getApplicationContext()==guest&&adapter.getMethod("current").invoke(null)==guest,true);
            rejected=false;try{publish.invoke(null,host);}catch(InvocationTargetException expected){rejected=expected.getCause() instanceof IllegalStateException;}
            check("host-publication-rejected",rejected&&logical.getApplicationContext()==guest&&adapter.getMethod("current").invoke(null)==guest,true);
            Application delegate=Instrumentation.newApplication(Application.class,new ContextWrapper(logical));
            publish.invoke(null,delegate);
            check("delegate-publication-updates-context-and-current",logical.getApplicationContext()==delegate&&adapter.getMethod("current").invoke(null)==delegate,true);
            check("delegate-publication-updates-loaded-apk",adapter.getMethod("installedApplication").invoke(null)==delegate,true);
            adapter.getMethod("check").invoke(null);
            check("delegate-keeps-physical-ownership",true,true);
            adapter.getMethod("closeProbeView").invoke(null);
            check("physical-host-restored",adapter.getMethod("current").invoke(null)==host,true);
        }catch(Throwable error){failure.set(error);}});
        if(failure.get()!=null)throw new IllegalStateException("PUBLICATION_CONTRACT_FAILED",failure.get());
    }
}
