package dev.tripoder.provideridentity;

import android.content.*;
import android.content.pm.*;
import android.content.res.Configuration;
import android.os.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Own proxy-chain acceptance. No third-party application code or installed-app queries. */
final class PackageWorldChecks {
    static void inspect(Context context,JSONArray checks)throws Exception{
        PackageManager pm=context.getPackageManager();Class<?> ipm=Class.forName("android.content.pm.IPackageManager");
        check(checks,"package-manager-never-null",pm!=null,String.valueOf(pm));
        check(checks,"package-context-facade-cached",pm==context.getPackageManager(),true);
        check(checks,"package-facade-is-platform-class",pm.getClass().getName().equals("android.app.ApplicationPackageManager"),pm.getClass().getName());
        Field member=field(pm.getClass(),"mPM");Object original=member.get(pm);
        check(checks,"package-endpoint-local-and-shaped",ipm.isInstance(original)&&Proxy.isProxyClass(original.getClass()),original.getClass().getName());
        IBinder binder=((IInterface)original).asBinder();check(checks,"package-binder-local-endpoint",binder.queryLocalInterface("android.content.pm.IPackageManager")==original,true);
        AtomicInteger hits=new AtomicInteger();Object proxy=wrap(ipm,original,hits);
        try{
            member.set(pm,proxy);pm.getServiceInfo(new ComponentName(context,HistoryFailureService.class),0);
            check(checks,"package-instance-proxy-really-invoked",hits.get()>0,hits.get());
            AtomicReference<Throwable> failure=new AtomicReference<>();Thread thread=new Thread(()->{try{pm.getApplicationInfo(context.getPackageName(),0);}catch(Throwable error){failure.set(error);}},"owned-package-query");int before=hits.get();thread.start();thread.join(5000);
            check(checks,"package-instance-proxy-background-thread",!thread.isAlive()&&failure.get()==null&&hits.get()>before,String.valueOf(failure.get()));
        }finally{member.set(pm,original);}
        int stopped=hits.get();pm.getApplicationInfo(context.getPackageName(),0);check(checks,"package-instance-proxy-disabled",hits.get()==stopped,hits.get());
        Class<?> activityThread=Class.forName("android.app.ActivityThread");Field global=field(activityThread,"sPackageManager");Object oldGlobal=global.get(null);AtomicInteger globalHits=new AtomicInteger();Object globalProxy=wrap(ipm,oldGlobal,globalHits);
        try{
            global.set(null,globalProxy);Method getter=activityThread.getDeclaredMethod("getPackageManager");getter.setAccessible(true);
            check(checks,"package-global-proxy-visible-to-static-getter",getter.invoke(null)==globalProxy,true);
            Context configured=context.createConfigurationContext(new Configuration(context.getResources().getConfiguration()));
            PackageManager child=configured.getPackageManager();check(checks,"package-new-context-inherits-current-global-endpoint",member.get(child)==globalProxy,true);
            child.getApplicationInfo(context.getPackageName(),0);check(checks,"package-global-proxy-actually-invoked",globalHits.get()>0,globalHits.get());
            Context base=context.getApplicationContext();while(base instanceof ContextWrapper)base=((ContextWrapper)base).getBaseContext();
            check(checks,"package-logical-base-context-uses-local-root",Proxy.isProxyClass(member.get(base.getPackageManager()).getClass()),true);
        }finally{global.set(null,oldGlobal);}
        int globalStopped=globalHits.get();context.createConfigurationContext(new Configuration()).getPackageManager().getApplicationInfo(context.getPackageName(),0);
        check(checks,"package-global-proxy-disabled-for-new-context",globalHits.get()==globalStopped,globalHits.get());
        Class<?> manager=Class.forName("android.os.ServiceManager");
        for(String name:new String[]{"getService","getServiceOrThrow","checkService","waitForService","waitForDeclaredService"}){
            Method method=lookup(checks,manager,name,String.class);if(method!=null){method.setAccessible(true);check(checks,"package-service-getter-local-"+name,method.invoke(null,"package")==binder,true);}
        }
        boolean denied=false;try{pm.getApplicationInfo("dev.tripoder.not-in-this-world",0);}catch(UnsupportedOperationException expected){denied=true;}check(checks,"package-other-app-query-denied",denied,true);
        denied=false;try{pm.getPackageInstaller();}catch(UnsupportedOperationException expected){denied=true;}check(checks,"package-installer-not-granted",denied,true);
        Method hidden=lookup(checks,PackageManager.class,"getHoldLockToken");if(hidden!=null){hidden.setAccessible(true);denied=false;try{hidden.invoke(pm);}catch(InvocationTargetException expected){denied=expected.getCause() instanceof UnsupportedOperationException;}check(checks,"package-inherited-hidden-api-denied",denied,true);}
        Parcel input=Parcel.obtain(),output=Parcel.obtain();try{denied=false;try{binder.transact(IBinder.FIRST_CALL_TRANSACTION,input,output,0);}catch(UnsupportedOperationException expected){denied=true;}check(checks,"package-raw-binder-transaction-denied",denied,true);}finally{input.recycle();output.recycle();}
        Method raw=lookup(checks,manager,"rawGetService",String.class);if(raw!=null){raw.setAccessible(true);denied=false;try{raw.invoke(null,"package");}catch(InvocationTargetException expected){denied=expected.getCause() instanceof UnsupportedOperationException;}check(checks,"package-managed-raw-service-denied",denied,true);}
        boolean feature=pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);check(checks,"package-real-feature-query-no-recursion",feature,feature);
        Class<?> bridge=Class.forName("dev.tripoder.runtime.RuntimePackageBridge");int user=(Integer)bridge.getMethod("userId").invoke(null);
        String[] packages=pm.getPackagesForUid(android.os.Process.myUid());String infrastructure=null;for(String pkg:packages)if(!pkg.equals(context.getPackageName()))infrastructure=pkg;
        check(checks,"package-infrastructure-identity-present",infrastructure!=null,true);
        Method physicalPackage=bridge.getMethod("physicalPackageInfo",String.class,long.class,int.class),physicalApp=bridge.getMethod("physicalApplicationInfo",String.class,long.class,int.class),physicalComponent=bridge.getMethod("physicalComponent",String.class,ComponentName.class,long.class,int.class);
        check(checks,"package-direct-physical-other-package-denied",securityDenied(physicalPackage,"dev.tripoder.not-in-this-world",0L,user),true);
        check(checks,"package-direct-physical-other-application-denied",securityDenied(physicalApp,"dev.tripoder.not-in-this-world",0L,user),true);
        check(checks,"package-direct-physical-other-user-denied",securityDenied(physicalApp,infrastructure,0L,user+1),true);
        check(checks,"package-direct-physical-undeclared-component-denied",securityDenied(physicalComponent,"getServiceInfo",new ComponentName(infrastructure,"dev.tripoder.UndeclaredOwnedTest"),0L,user),true);
        check(checks,"package-direct-fake-context-denied",securityDenied(bridge.getMethod("contextManager",Context.class),new ContextWrapper(context)),true);
        Object prior=member.get(pm);boolean repeatedRejected;
        try{repeatedRejected=securityDenied(bridge.getMethod("activate",Object.class,Context.class),wrap(ipm,prior,new AtomicInteger()),context);check(checks,"package-repeated-activation-rejected-before-mutation",repeatedRejected&&member.get(pm)==prior,true);}
        finally{member.set(pm,prior);}
        java.util.List<java.lang.ref.WeakReference<Object>> refs=temporaryContexts(context);
        int retained=refs.size();for(int attempt=0;attempt<20&&retained>0;attempt++){System.gc();System.runFinalization();Thread.sleep(50);retained=0;for(var ref:refs)if(ref.get()!=null)retained++;}
        check(checks,"package-temporary-contexts-and-facades-reclaimed",retained==0,"retained="+retained+"/"+refs.size());
    }
    private static Method lookup(JSONArray checks,Class<?> type,String name,Class<?>... arguments)throws Exception{
        // The pinned adapter separately requires these exact methods to exist.
        // Do not claim route execution when Android denies this guest's lookup.
        try{return type.getDeclaredMethod(name,arguments);}catch(NoSuchMethodException denied){check(checks,"platform-hidden-api-denies-lookup-"+type.getSimpleName()+"-"+name,true,"NoSuchMethodException; runtime route not exercised");return null;}
    }
    private static boolean securityDenied(Method method,Object... args)throws Exception{try{method.invoke(null,args);return false;}catch(InvocationTargetException error){return error.getCause() instanceof SecurityException;}}
    private static java.util.List<java.lang.ref.WeakReference<Object>> temporaryContexts(Context context){
        var refs=new java.util.ArrayList<java.lang.ref.WeakReference<Object>>();for(int i=0;i<32;i++){Context temporary=context.createConfigurationContext(new Configuration());PackageManager facade=temporary.getPackageManager();refs.add(new java.lang.ref.WeakReference<>(temporary));refs.add(new java.lang.ref.WeakReference<>(facade));}return refs;
    }
    private static Object wrap(Class<?> contract,Object original,AtomicInteger hits){return Proxy.newProxyInstance(contract.getClassLoader(),new Class<?>[]{contract},(self,method,args)->{
        if(method.getDeclaringClass()!=Object.class&&!method.getName().equals("asBinder"))hits.incrementAndGet();
        try{return method.invoke(original,args);}catch(InvocationTargetException error){throw error.getCause();}
    });}
    private static Field field(Class<?> type,String name)throws Exception{Field result=type.getDeclaredField(name);result.setAccessible(true);return result;}
    private static void check(JSONArray checks,String name,boolean passed,Object observed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed).put("observed",observed));if(!passed)throw new AssertionError(name+":"+observed);}
    private PackageWorldChecks(){}
}
