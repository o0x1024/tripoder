package dev.tripoder.p2;

import android.app.Application;
import android.content.*;
import java.lang.reflect.*;

/** Own fixture: an application associates its actual base package with itself during attach. */
final class ApplicationOwnership {
    static Object attach(Application application,Context context)throws Exception{
        Context base=context;
        while(base instanceof ContextWrapper)base=((ContextWrapper)base).getBaseContext();
        if(!base.getClass().getName().equals("android.app.ContextImpl"))throw new AssertionError("APPLICATION_PLATFORM_BASE_TYPE");
        Object apk=field(base.getClass(),"mPackageInfo").get(base);
        android.content.pm.ApplicationInfo info=(android.content.pm.ApplicationInfo)field(apk.getClass(),"mApplicationInfo").get(apk);
        if(!context.getPackageName().equals(info.packageName))throw new AssertionError("APPLICATION_BASE_PACKAGE_OWNERSHIP");
        field(apk.getClass(),"mApplication").set(apk,application);
        Class<?> threadClass=Class.forName("android.app.ActivityThread");Method current=threadClass.getDeclaredMethod("currentActivityThread");current.setAccessible(true);
        Object thread=current.invoke(null);Field initial=field(threadClass,"mInitialApplication");initial.set(thread,application);
        if(initial.get(thread)!=application)throw new AssertionError("LOGICAL_INITIAL_APPLICATION_MISMATCH");
        boolean rejected=false;try{initial.set(thread,new Application());}catch(SecurityException expected){rejected=expected.getMessage().contains("INITIAL_APPLICATION_IDENTITY_IMMUTABLE");}
        if(!rejected||initial.get(thread)!=application)throw new AssertionError("FOREIGN_INITIAL_APPLICATION_NOT_REJECTED");
        rejected=false;try{initial.set(thread,null);}catch(SecurityException expected){rejected=expected.getMessage().contains("INITIAL_APPLICATION_IDENTITY_IMMUTABLE");}
        if(!rejected||initial.get(thread)!=application)throw new AssertionError("NULL_INITIAL_APPLICATION_NOT_REJECTED");
        rejected=false;try{initial.set(thread,new Object());}catch(IllegalArgumentException expected){rejected=true;}
        if(!rejected||initial.get(thread)!=application)throw new AssertionError("INITIAL_APPLICATION_TYPE_CHECK_LOST");
        Field inaccessible=threadClass.getDeclaredField("mInitialApplication");
        rejected=false;try{inaccessible.get(thread);}catch(IllegalAccessException expected){rejected=true;}
        if(!rejected)throw new AssertionError("INITIAL_APPLICATION_READ_PERMISSION_LOST");
        rejected=false;try{inaccessible.set(thread,application);}catch(IllegalAccessException expected){rejected=true;}
        if(!rejected||initial.get(thread)!=application)throw new AssertionError("INITIAL_APPLICATION_WRITE_PERMISSION_LOST");
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();Thread[] readers=new Thread[4];
        for(int i=0;i<readers.length;i++){readers[i]=new Thread(()->{try{Field own=field(threadClass,"mInitialApplication");for(int n=0;n<500;n++){if(own.get(thread)!=application)throw new AssertionError("INITIAL_APPLICATION_CONCURRENT_IDENTITY");if(n%100==0)System.gc();}}catch(Throwable error){failure.compareAndSet(null,error);}},"owned-initial-"+i);readers[i].start();}
        for(Thread reader:readers){reader.join(5000);if(reader.isAlive())throw new AssertionError("INITIAL_APPLICATION_READER_TIMEOUT");}
        if(failure.get()!=null)throw new AssertionError("INITIAL_APPLICATION_CONCURRENT_FAILURE",failure.get());
        return apk;
    }
    static void check(Application application,Object expected)throws Exception{
        if(field(Application.class,"mLoadedApk").get(application)!=expected||field(expected.getClass(),"mApplication").get(expected)!=application||application.getApplicationContext()!=application)
            throw new AssertionError("APPLICATION_LOADED_PACKAGE_NOT_OWNED");
    }
    private static Field field(Class<?> type,String name)throws Exception{Field value=type.getDeclaredField(name);value.setAccessible(true);return value;}
}
