package dev.tripoder.closurefixture;

import android.app.Activity;
import android.content.ContentProviderClient;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.webkit.WebView;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Exercise the real registered dependency and rejected selectors; no arbitrary resource registration. */
final class ResourceRegistrationChecks {
    private static void publicationState(Activity activity,ResourceOriginChecks.Check check)throws Exception{
        try(ContentProviderClient settings=activity.getContentResolver().acquireContentProviderClient("settings")){
            ClassLoader runtime=settings.getLocalContentProvider().getClass().getClassLoader();
            Class<?> type=Class.forName("dev.tripoder.runtime.RuntimeEngine",false,runtime);
            Field current=type.getDeclaredField("current");current.setAccessible(true);
            Field owner=type.getDeclaredField("webView");owner.setAccessible(true);Object dependency=owner.get(current.get(null));
            Field state=dependency.getClass().getDeclaredField("resourceState");state.setAccessible(true);
            Field plan=dependency.getClass().getDeclaredField("resourcePlan");plan.setAccessible(true);Object prepared=plan.get(dependency);
            Method recorded=prepared.getClass().getDeclaredMethod("requireRecorded");recorded.setAccessible(true);
            String receipt;
            try{recorded.invoke(prepared);receipt="MATCHES_PRE_GUEST_KEY";}
            catch(InvocationTargetException failure){receipt=failure.getCause().getClass().getName()+":"+failure.getCause().getMessage();}
            android.util.Log.i("TripoderClosureFixture","owned-resource-registration-before-explicit-calls state="+state.get(dependency)+" frameworkReceipt="+receipt);
            check.test("cold-platform-webview-publishes-owned-registration-before-explicit-calls","REGISTERED".equals(String.valueOf(state.get(dependency)))&&"MATCHES_PRE_GUEST_KEY".equals(receipt));
        }
    }
    private static void invoke(Method entry,String owner,ApplicationInfo info)throws Exception{
        try{entry.invoke(null,owner,info);}catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();throw new AssertionError(failure.getCause());}
    }
    static void run(Activity activity,ResourceOriginChecks.Check check)throws Exception{
        Method entry=null;
        try(ContentProviderClient settings=activity.getContentResolver().acquireContentProviderClient("settings")){
            Class<?> access=Class.forName("dev.tripoder.selfhook.FrameworkAccess",false,settings.getLocalContentProvider().getClass().getClassLoader());var inventory=access.getDeclaredMethod("fixedMembers",int.class);inventory.setAccessible(true);
            for(Object candidate:(Object[])inventory.invoke(null,63))if(candidate instanceof Method){var method=(Method)candidate;
                if(method.getDeclaringClass()==Resources.class&&method.getName().equals("registerResourcePaths")&&Modifier.isStatic(method.getModifiers())&&method.getReturnType()==void.class&&Arrays.equals(method.getParameterTypes(),new Class<?>[]{String.class,ApplicationInfo.class})){if(entry!=null)throw new AssertionError("OWNED_RESOURCE_REGISTRATION_AMBIGUOUS");entry=method;}
            }
        }
        if(entry==null)throw new AssertionError("OWNED_RESOURCE_REGISTRATION_ENTRY_MISSING");entry.setAccessible(true);
        var selected=WebView.getCurrentWebViewPackage();if(selected==null||selected.applicationInfo==null)throw new AssertionError("OWNED_REAL_WEBVIEW_PROVIDER_REQUIRED");
        String owner=selected.packageName;ApplicationInfo info=selected.applicationInfo;
        publicationState(activity,check);
        boolean foreign=false;try{invoke(entry,"dev.tripoder.foreign.resource",info);}catch(SecurityException expected){foreign="RESOURCE_DEPENDENCY_OWNER_REQUIRED".equals(expected.getMessage());}
        check.test("resource-registration-cannot-introduce-an-unselected-owner",foreign);
        ApplicationInfo changed=new ApplicationInfo(info);changed.sharedLibraryFiles=new String[]{"/data/user/0/dev.tripoder.foreign/not-authorized.apk"};
        boolean rejected=false;try{invoke(entry,owner,changed);}catch(SecurityException expected){rejected="WEBVIEW_PROVIDER_CODE_IDENTITY_CHANGED".equals(expected.getMessage());}
        check.test("selected-provider-name-cannot-authorize-caller-supplied-resource-paths",rejected);
        Method registration=entry;CountDownLatch start=new CountDownLatch(1),done=new CountDownLatch(8);AtomicReference<Throwable> failed=new AtomicReference<>();
        for(int i=0;i<8;i++)new Thread(()->{try{start.await();invoke(registration,owner,new ApplicationInfo(info));}catch(Throwable error){failed.compareAndSet(null,error);}finally{done.countDown();}},"owned-resource-registration-"+i).start();
        start.countDown();boolean completed=done.await(10,TimeUnit.SECONDS);
        Throwable failure=failed.get();
        if(failure!=null)android.util.Log.e("TripoderClosureFixture","owned-resource-registration-worker-failed",failure);
        if(!completed)android.util.Log.e("TripoderClosureFixture","owned-resource-registration-workers-incomplete="+done.getCount());
        check.test("concurrent-repeated-real-resource-registrations-share-one-published-owner",completed&&failure==null);
        ResourceRegistrationEntryChecks.run(activity,owner,info,check);
    }
    private ResourceRegistrationChecks(){}
}
