package dev.tripoder.selfhook;

import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Parcel;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Objects;
import java.util.Arrays;
import java.util.Map;

/** Canonical shared resource assets come from the actual framework key/cache, before guest code. */
public final class ResourceDependencyAccess {
    private static Method manager,key,assets,allPaths,implementation,originalKey,mergeKey;private static Constructor<?> shared;private static Field lock,registrations;private static Class<?> sharedType;
    private static synchronized void prepare()throws Exception{
        if(manager!=null)return;
        Method selected=FixedFrameworkMembers.method(77,"getInstance","()Landroid/app/ResourcesManager;",true);
        shared=FixedFrameworkMembers.constructor(78,"(Landroid/content/pm/ApplicationInfo;Landroid/content/pm/ApplicationInfo;)V");
        key=FixedFrameworkMembers.method(78,"getResourcesKey","()Landroid/content/res/ResourcesKey;",false);
        assets=FixedFrameworkMembers.method(77,"createAssetManager","(Landroid/content/res/ResourcesKey;)Landroid/content/res/AssetManager;",false);
        implementation=FixedFrameworkMembers.method(63,"getImpl","()Landroid/content/res/ResourcesImpl;",false);
        originalKey=FixedFrameworkMembers.method(77,"findKeyForResourceImplLocked","(Landroid/content/res/ResourcesImpl;)Landroid/content/res/ResourcesKey;",false);
        mergeKey=FixedFrameworkMembers.method(77,"createNewResourceKeyIfNeeded","(Landroid/content/res/ResourcesKey;Landroid/content/res/ResourcesKey;)Landroid/content/res/ResourcesKey;",false);
        lock=FixedFrameworkMembers.field(64,77,"mLock","Ljava/lang/Object;",false);
        registrations=FixedFrameworkMembers.field(65,77,"mSharedLibAssetsMap","Landroid/util/ArrayMap;",false);sharedType=FixedFrameworkMembers.owner(78);
        allPaths=FixedFrameworkMembers.method(4,"getAllApkPaths","()[Ljava/lang/String;",false);manager=selected;
    }
    /** Parcel copies hidden arrays too; a shallow ApplicationInfo copy shares them. */
    public static ApplicationInfo copy(ApplicationInfo value){
        if(value==null||value.getClass()!=ApplicationInfo.class)throw new SecurityException("RESOURCE_DEPENDENCY_EXACT_APPLICATION_INFO_REQUIRED");
        Parcel parcel=Parcel.obtain();try{value.writeToParcel(parcel,0);parcel.setDataPosition(0);return ApplicationInfo.CREATOR.createFromParcel(parcel);}finally{parcel.recycle();}
    }
    public static ResourceFrameworkAccess.AssetSnapshot sharedLibrarySnapshot(ApplicationInfo dependency,ApplicationInfo application)throws Exception{
        prepare();Object instance=Objects.requireNonNull(FixedFrameworkMembers.call(manager,null),"RESOURCE_MANAGER_REQUIRED");
        Object library=FixedFrameworkMembers.create(shared,copy(dependency),copy(application));Object resourceKey=FixedFrameworkMembers.call(key,library);
        try(AssetManager canonical=(AssetManager)Objects.requireNonNull(FixedFrameworkMembers.call(assets,instance,resourceKey),"RESOURCE_DEPENDENCY_ASSET_MANAGER_REQUIRED")){
            return ResourceFrameworkAccess.snapshot(canonical);
        }
    }
    /** Compile the complete ordered result, including pre-existing overlays and system assets. */
    public static ResourceFrameworkAccess.AssetSnapshot extendedSnapshot(Resources source,ApplicationInfo dependency,ApplicationInfo application)throws Exception{
        return plan(source,dependency,application).layout();
    }
    public static RegistrationPlan plan(Resources source,ApplicationInfo dependency,ApplicationInfo application)throws Exception{
        prepare();Object instance=Objects.requireNonNull(FixedFrameworkMembers.call(manager,null),"RESOURCE_MANAGER_REQUIRED");
        ApplicationInfo frozenDependency=copy(dependency),frozenApplication=copy(application);
        Object library=FixedFrameworkMembers.create(shared,frozenDependency,frozenApplication);Object dependencyKey=FixedFrameworkMembers.call(key,library),base,map,monitor;
        monitor=Objects.requireNonNull(FixedFrameworkMembers.get(lock,instance),"RESOURCE_MANAGER_LOCK_REQUIRED");
        synchronized(monitor){
            Object impl=FixedFrameworkMembers.call(implementation,Objects.requireNonNull(source));
            base=Objects.requireNonNull(FixedFrameworkMembers.call(originalKey,instance,impl),"RESOURCE_MANAGER_OWNED_BASE_KEY_REQUIRED");
            map=FixedFrameworkMembers.get(registrations,instance);if(map==null||map.getClass()!=android.util.ArrayMap.class)throw new SecurityException("RESOURCE_REGISTRATION_MAP_IDENTITY_REQUIRED");
        }
        Object merged=FixedFrameworkMembers.call(mergeKey,instance,base,dependencyKey);
        // A null merge key is the framework's exact "no new paths" result.
        Object expected=merged==null?base:merged;
        try(AssetManager canonical=(AssetManager)Objects.requireNonNull(FixedFrameworkMembers.call(assets,instance,expected),"RESOURCE_DEPENDENCY_ASSET_MANAGER_REQUIRED")){
            return new RegistrationPlan(instance,monitor,map,dependencyKey,frozenDependency,frozenApplication,ResourceFrameworkAccess.snapshot(canonical));
        }
    }
    public static final class RegistrationPlan {
        private final Object instance,monitor,map,dependencyKey;
        private final ApplicationInfo dependency,application;
        private final String[] dependencyPaths,applicationPaths;
        private final ResourceFrameworkAccess.AssetSnapshot layout;
        private RegistrationPlan(Object instance,Object monitor,Object map,Object dependencyKey,ApplicationInfo dependency,ApplicationInfo application,ResourceFrameworkAccess.AssetSnapshot layout)throws Exception{
            this.instance=instance;this.monitor=monitor;this.map=map;this.dependencyKey=dependencyKey;this.dependency=dependency;this.application=application;this.layout=layout;
            dependencyPaths=apkPaths(dependency);applicationPaths=apkPaths(application);
        }
        public ResourceFrameworkAccess.AssetSnapshot layout(){return layout;}
        public void requireInputs(ApplicationInfo supplied,ApplicationInfo base)throws Exception{
            if(supplied==null||base==null||!Objects.equals(dependency.packageName,supplied.packageName)||dependency.uid!=supplied.uid||!Objects.equals(application.packageName,base.packageName)||application.uid!=base.uid||!Arrays.equals(dependencyPaths,apkPaths(supplied))||!Arrays.equals(applicationPaths,apkPaths(base)))throw new SecurityException("RESOURCE_DEPENDENCY_PROFILE_CHANGED");
        }
        /** Copy only the trusted pre-guest metadata, never serialize caller Bundles. */
        public ApplicationInfo canonicalInput(){return copy(dependency);}
        private void requireRoot(){
            if(FixedFrameworkMembers.call(manager,null)!=instance||FixedFrameworkMembers.get(lock,instance)!=monitor||FixedFrameworkMembers.get(registrations,instance)!=map)throw new SecurityException("RESOURCE_MANAGER_REGISTRATION_ROOT_CHANGED");
        }
        /** The sole registration owner is the captured framework singleton. A
         * caller holding its lock cannot wait for a registrar that needs it. */
        public void requireEntry(Object receiver){
            requireRoot();
            if(receiver!=instance)throw new SecurityException("RESOURCE_DEPENDENCY_REGISTRATION_RECEIVER_REQUIRED");
            if(Thread.holdsLock(monitor))throw new IllegalStateException("RESOURCE_DEPENDENCY_REGISTRATION_LOCK_HELD");
        }
        public void requireRecorded(){
            requireRoot();
            synchronized(monitor){
                Object registered=((Map<?,?>)map).get(dependency.packageName);
                if(registered==null||registered.getClass()!=sharedType)throw new SecurityException("RESOURCE_DEPENDENCY_REGISTRATION_REQUIRED");
                Object actual=FixedFrameworkMembers.call(key,registered);
                if(actual==null||actual.getClass()!=dependencyKey.getClass()||!dependencyKey.equals(actual))throw new SecurityException("RESOURCE_DEPENDENCY_REGISTERED_KEY_CHANGED");
            }
        }
    }
    public static String[] apkPaths(ApplicationInfo application)throws Exception{
        prepare();if(application==null||application.getClass()!=ApplicationInfo.class)throw new SecurityException("RESOURCE_DEPENDENCY_EXACT_APPLICATION_INFO_REQUIRED");
        return ((String[])Objects.requireNonNull(FixedFrameworkMembers.call(allPaths,application),"RESOURCE_DEPENDENCY_PATHS_REQUIRED")).clone();
    }
    private ResourceDependencyAccess(){}
}
