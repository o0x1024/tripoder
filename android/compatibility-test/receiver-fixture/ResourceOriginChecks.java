package dev.tripoder.closurefixture;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.*;
import android.content.res.loader.*;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;

/** Real framework Context/PM caches and managed resource loaders; ephemeral contexts only. */
final class ResourceOriginChecks {
    interface Check {void test(String name,boolean passed)throws Exception;}
    private static Field field(Class<?> type,String name)throws Exception{Field value=type.getDeclaredField(name);value.setAccessible(true);return value;}
    private static Context platform(Context context)throws Exception{
        Class<?> exact=Class.forName("android.app.ContextImpl",false,null);Set<Context> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        while(context!=null&&seen.size()<16&&seen.add(context)){if(context.getClass()==exact)return context;if(!(context instanceof ContextWrapper))break;context=((ContextWrapper)context).getBaseContext();}
        throw new AssertionError("OWNED_EXACT_FRAMEWORK_CONTEXT_MISSING");
    }
    private static boolean denied(Context context,String code){try{context.getPackageManager();return false;}catch(SecurityException expected){return code.equals(expected.getMessage());}}
    static void run(Activity activity,Check check)throws Exception{
        Context original=platform(activity.getApplication());Configuration configuration=new Configuration(original.getResources().getConfiguration());configuration.fontScale=1.13f;
        Context derived=original.createConfigurationContext(configuration);Class<?> exact=derived.getClass();
        PackageManager manager=derived.getPackageManager();Resources first=derived.getResources();
        check.test("framework-derived-pm-keeps-actual-configuration-resource-view",manager.getResourcesForApplication(activity.getPackageName())==first&&manager.getApplicationInfo(activity.getPackageName(),0).packageName.equals(activity.getPackageName()));
        int drawable=first.getIdentifier("owned","drawable",activity.getPackageName());
        check.test("framework-derived-view-inflates-real-owned-xml",first.getDrawable(drawable,derived.getTheme()).getClass()==OwnedDrawable.class);
        Configuration changed=new Configuration(configuration);changed.fontScale=1.19f;Context other=original.createConfigurationContext(changed);Resources second=other.getResources();
        Field resources=field(exact,"mResources");Object before=resources.get(derived);
        try{
            resources.set(derived,second);
            check.test("cached-pm-revalidates-and-follows-owned-resource-replacement",derived.getPackageManager()==manager&&manager.getResourcesForApplication(activity.getPackageName())==second);
        }finally{resources.set(derived,before);derived.getPackageManager();}
        // Borrowing a registered facade from another Context must not authorize it.
        Field cache=field(exact,"mPackageManager");Object priorCache=cache.get(other);cache.set(other,manager);
        try{check.test("registered-manager-from-another-context-is-rejected",denied(other,"PACKAGE_FACADE_CONTEXT_MISMATCH"));}finally{cache.set(other,priorCache);}
        // Same package text and resource IDs cannot authorize an unrelated LoadedApk.
        Class<?> threadType=Class.forName("android.app.ActivityThread",false,null);Object thread=threadType.getDeclaredMethod("currentActivityThread").invoke(null);
        Field packageInfo=field(exact,"mPackageInfo"),mainThread=field(exact,"mMainThread");Object logical=packageInfo.get(derived);
        var compatibility=logical.getClass().getDeclaredMethod("getCompatibilityInfo");compatibility.setAccessible(true);Object compatible=compatibility.invoke(logical);
        Class<?>[] signature={threadType,android.content.pm.ApplicationInfo.class,Class.forName("android.content.res.CompatibilityInfo",false,null),ClassLoader.class,boolean.class,boolean.class,boolean.class};
        java.lang.reflect.Constructor<?> constructor=null;
        // LoadedApk constructors are hidden from app reflection at this target SDK.
        // Read the existing boot snapshot, checking the exact declaration ourselves.
        try(ContentProviderClient settings=activity.getContentResolver().acquireContentProviderClient("settings")){
            if(settings==null||settings.getLocalContentProvider()==null)throw new AssertionError("OWNED_SETTINGS_PROVIDER_REQUIRED");
            Class<?> access=Class.forName("dev.tripoder.selfhook.FrameworkAccess",false,settings.getLocalContentProvider().getClass().getClassLoader());
            var lookup=access.getDeclaredMethod("fixedMembers",int.class);lookup.setAccessible(true);
            for(Object member:(Object[])lookup.invoke(null,2))if(member instanceof java.lang.reflect.Constructor<?>){var candidate=(java.lang.reflect.Constructor<?>)member;
                if(candidate.getDeclaringClass()==logical.getClass()&&Arrays.equals(candidate.getParameterTypes(),signature)){if(constructor!=null)throw new AssertionError("OWNED_ROOT_CONSTRUCTOR_AMBIGUOUS");constructor=candidate;}
            }
        }
        if(constructor==null)throw new AssertionError("OWNED_ROOT_CONSTRUCTOR_MISSING");constructor.setAccessible(true);
        // Same package, same sealed paths, never published to either platform cache.
        Object foreign=constructor.newInstance(thread,new android.content.pm.ApplicationInfo(activity.getApplicationInfo()),compatible,activity.getClassLoader(),false,false,false);
        try{
            packageInfo.set(derived,foreign);
            check.test("unpublished-root-really-replaces-only-owned-context-package-root",packageInfo.get(derived)==foreign&&foreign!=logical&&activity.getPackageName().equals(derived.getPackageName())&&mainThread.get(derived)==thread&&derived.getResources()==first&&cache.get(derived)==manager);
            check.test("cached-pm-cannot-borrow-unpublished-same-package-root",denied(derived,"APPLICATION_CONTEXT_LOGICAL_ROOT_REQUIRED"));
        }finally{packageInfo.set(derived,logical);derived.getPackageManager();}
        check.test("restored-context-refreshes-held-manager-before-loader-negative",packageInfo.get(derived)==logical&&manager.getResourcesForApplication(activity.getPackageName())==first);
        // This resource APK has the SAME manifest package and resource namespace.
        File file=new File(activity.getFilesDir(),"owned-foreign-resource-"+UUID.randomUUID()+".apk");
        try(var input=activity.getAssets().open("foreign-resources.apk")){Files.copy(input,file.toPath());}if(!file.setReadOnly())throw new IOException("OWNED_RESOURCE_READONLY_REQUIRED");
        try(var fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);var provider=ResourcesProvider.loadFromApk(fd)){
            ResourcesLoader loader=new ResourcesLoader();loader.addProvider(provider);first.addLoaders(loader);
            try{
                check.test("cached-pm-denies-unpublished-same-package-resource-loader",denied(derived,"PACKAGE_CONTEXT_RESOURCE_ORIGIN_DENIED"));
                boolean heldDenied=false;try{manager.getResourcesForApplication(activity.getPackageName());}catch(SecurityException expected){heldDenied="PACKAGE_CONTEXT_RESOURCE_ORIGIN_DENIED".equals(expected.getMessage());}
                check.test("already-held-manager-denies-unpublished-resource-loader",heldDenied);
            }
            finally{first.removeLoaders(loader);loader.removeProvider(provider);}
        }
        check.test("removing-unpublished-loader-restores-exact-owned-pm-view",derived.getPackageManager()==manager&&manager.getResourcesForApplication(activity.getPackageName())==first);
    }
    private ResourceOriginChecks(){}
}
