package dev.tripoder.closurefixture;

import android.app.Activity;
import android.content.*;
import android.content.pm.ProviderInfo;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Call the actual boot installContentProviders entry, independently validating its ABI. */
final class ProviderInstallationChecks {
    static Method frameworkEntry;static Object frameworkThread;
    interface Check {void test(String name,boolean passed)throws Exception;}
    private static boolean once(){return CanonicalProvider.constructors.get()==1&&CanonicalProvider.attachments.get()==1&&CanonicalProvider.creates.get()==1;}
    private static void install(Activity activity,Method method,Object thread,List<ProviderInfo> infos)throws Exception{
        install(activity,method,thread,infos,true);
    }
    static void install(Activity activity,Method method,Object thread,List<ProviderInfo> infos,boolean main)throws Exception{
        CountDownLatch completed=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
        Runnable action=()->{try{method.invoke(thread,activity.getApplication(),infos);}catch(InvocationTargetException failure){error.set(failure.getCause());}catch(Throwable failure){error.set(failure);}finally{completed.countDown();}};
        if(main)activity.runOnUiThread(action);else action.run();
        if(!completed.await(8,TimeUnit.SECONDS))throw new AssertionError("OWNED_MANUAL_PROVIDER_INSTALL_TIMEOUT");
        if(error.get()!=null){android.util.Log.i("TripoderClosureFixture","manualProviderCounters="+CanonicalProvider.constructors.get()+","+CanonicalProvider.attachments.get()+","+CanonicalProvider.creates.get());if(error.get() instanceof Exception)throw (Exception)error.get();throw new AssertionError(error.get());}
    }
    static void run(Activity activity,Check check)throws Exception{
        check.test("canonical-provider-bootstrap-has-one-constructor-attach-onCreate",once()&&CanonicalProvider.original!=null);
        check.test("initial-provider-bind-request-is-stable-initOrder-descending",ProviderOrder.initialOrder);
        check.test("bootstrap-consumes-actual-after-attach-request-order",ProviderOrder.events.equals(List.of("canonical","tail")));
        Class<?> owner=Class.forName("android.app.ActivityThread",false,null);Object thread=owner.getDeclaredMethod("currentActivityThread").invoke(null);Method entry=null;
        try(ContentProviderClient settings=activity.getContentResolver().acquireContentProviderClient("settings")){
            if(settings==null||settings.getLocalContentProvider()==null)throw new AssertionError("OWNED_SETTINGS_PROVIDER_REQUIRED");
            Class<?> access=Class.forName("dev.tripoder.selfhook.FrameworkAccess",false,settings.getLocalContentProvider().getClass().getClassLoader());
            Method inventory=access.getDeclaredMethod("fixedMembers",int.class);inventory.setAccessible(true);
            for(Object member:(Object[])inventory.invoke(null,1))if(member instanceof Method){Method candidate=(Method)member;
                if(candidate.getDeclaringClass()==owner&&candidate.getName().equals("installContentProviders")&&candidate.getReturnType()==void.class&&!Modifier.isStatic(candidate.getModifiers())&&Arrays.equals(candidate.getParameterTypes(),new Class<?>[]{Context.class,List.class})){
                    if(entry!=null)throw new AssertionError("OWNED_PROVIDER_INSTALL_AMBIGUOUS");entry=candidate;
                }
            }
        }
        if(entry==null)throw new AssertionError("OWNED_PROVIDER_INSTALL_ENTRY_MISSING");entry.setAccessible(true);frameworkEntry=entry;frameworkThread=thread;
        check.test("actual-bind-provider-selector-removal-is-honored-before-onCreate-without-class-heuristic",ThemeApplication.deferredBeforeOnCreate&&DeferredProvider.constructors.get()==0);
        ProviderInfo deferred=activity.getPackageManager().getProviderInfo(new ComponentName(activity,DeferredProvider.class),0);
        ProviderInfo invalidDeferred=new ProviderInfo(deferred);invalidDeferred.authority="dev.tripoder.foreign.deferred";
        boolean rejectedBatch=false;try{install(activity,entry,thread,List.of(deferred,invalidDeferred));}catch(SecurityException expected){rejectedBatch="PROVIDER_INSTALL_SELECTOR_MISMATCH".equals(expected.getMessage());}
        check.test("entire-invalid-batch-is-rejected-before-first-unclaimed-provider-constructor",rejectedBatch&&DeferredProvider.constructors.get()==0&&DeferredProvider.attachments.get()==0&&DeferredProvider.creates.get()==0);
        install(activity,entry,thread,List.of(deferred),false);
        check.test("later-private-batch-first-installs-only-existing-admitted-deferred-provider",DeferredProvider.constructors.get()==1&&DeferredProvider.attachments.get()==1&&DeferredProvider.creates.get()==1);
        check.test("explicit-private-provider-install-preserves-real-calling-thread",DeferredProvider.creator==Thread.currentThread());
        install(activity,entry,thread,List.of(deferred,deferred));
        check.test("deferred-provider-repeated-request-retains-one-canonical-publication",DeferredProvider.constructors.get()==1&&DeferredProvider.attachments.get()==1&&DeferredProvider.creates.get()==1);
        ProviderInfo failing=activity.getPackageManager().getProviderInfo(new ComponentName(activity,FailedProvider.class),0);
        check.test("omitted-failing-provider-has-no-bootstrap-side-effects",FailedProvider.constructors.get()==0);
        for(int attempt=0;attempt<2;attempt++){
            boolean failed=false;try{install(activity,entry,thread,List.of(failing));}catch(Exception expected){for(Throwable cause=expected;cause!=null;cause=cause.getCause())if("OWNED_EXPECTED_PROVIDER_INIT_FAILURE".equals(cause.getMessage()))failed=true;}
            check.test("failed-provider-keeps-original-failure-without-second-factory-attach-or-onCreate-"+attempt,failed&&FailedProvider.constructors.get()==1&&FailedProvider.attachments.get()==1&&FailedProvider.creates.get()==1);
        }
        ProviderInfo info=activity.getPackageManager().getProviderInfo(new ComponentName(activity,CanonicalProvider.class),0);
        CanonicalProvider original=CanonicalProvider.original;
        install(activity,entry,thread,List.of(info));
        check.test("manual-framework-provider-install-reuses-canonical-instance-before-any-constructor",once()&&CanonicalProvider.original==original);
        install(activity,entry,thread,List.of(info,info));
        check.test("manual-framework-provider-batch-repetition-has-no-duplicate-side-effects",once()&&CanonicalProvider.original==original);
        List<ProviderInfo> invalid=new ArrayList<>();
        ProviderInfo wrongAuthority=new ProviderInfo(info);wrongAuthority.authority="dev.tripoder.absent.authority";invalid.add(wrongAuthority);
        ProviderInfo wrongPackage=new ProviderInfo(info);wrongPackage.packageName="dev.tripoder.foreign";invalid.add(wrongPackage);
        ProviderInfo wrongProcess=new ProviderInfo(info);wrongProcess.processName=activity.getPackageName()+":other";invalid.add(wrongProcess);
        invalid.add(activity.getPackageManager().getProviderInfo(new ComponentName(activity,CanonicalProvider.Disabled.class),android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS));
        invalid.add(activity.getPackageManager().getProviderInfo(new ComponentName(activity,CanonicalProvider.Remote.class),0));
        int index=0;for(ProviderInfo rejected:invalid){
            boolean denied=false;try{install(activity,entry,thread,List.of(info,rejected));}catch(SecurityException expected){denied="PROVIDER_INSTALL_SELECTOR_MISMATCH".equals(expected.getMessage());}
            check.test("manual-provider-batch-rejects-unowned-selector-before-any-callback-"+(index++),denied&&once());
        }
    }
    private ProviderInstallationChecks(){}
}
