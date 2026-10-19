package dev.tripoder.runtime;

import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;

/** Package metadata and resources come from this process's verified installation. */
final class GuestPackageManager extends DeniedPackageManager {
    private final PackageManager platform;
    private final RuntimeEngine engine;
    private final Resources resources;

    GuestPackageManager(PackageManager platform, RuntimeEngine engine, Resources resources) {
        this.platform = platform;
        this.engine = engine;
        this.resources = resources;
    }

    @Override public boolean hasSystemFeature(String name) { return platform.hasSystemFeature(name); }
    @Override public boolean hasSystemFeature(String name, int version) { return platform.hasSystemFeature(name, version); }
    @Override public boolean isInstantApp() { return false; }
    @Override public android.content.pm.PackageInfo getPackageInfo(String name,int flags)throws NameNotFoundException{return GuestPackageInfo.get(engine,name,Integer.toUnsignedLong(flags));}
    @Override public android.content.pm.PackageInfo getPackageInfo(String name,PackageInfoFlags flags)throws NameNotFoundException{if(flags==null)throw new IllegalArgumentException("PACKAGE_INFO_FLAGS_REQUIRED");return GuestPackageInfo.get(engine,name,flags.getValue());}
    @Override public android.content.pm.PackageInfo getPackageInfo(android.content.pm.VersionedPackage name,int flags)throws NameNotFoundException{return getPackageInfo(name,PackageInfoFlags.of(Integer.toUnsignedLong(flags)));}
    @Override public android.content.pm.PackageInfo getPackageInfo(android.content.pm.VersionedPackage name,PackageInfoFlags flags)throws NameNotFoundException{
        // Android resolves the version selector for static shared libraries only.
        // Ordinary applications have one installed version, as verified on the pinned platform.
        if(name==null)throw new IllegalArgumentException("VERSIONED_PACKAGE_REQUIRED");return getPackageInfo(name.getPackageName(),flags);
    }

    /** Another package is not visible to the guest: Android answers NameNotFoundException, never an unsupported-operation error. */
    private void requireSelf(String packageName) throws NameNotFoundException {
        if (!engine.packageName.equals(packageName))
            throw new NameNotFoundException(String.valueOf(packageName));
    }

    @Override public ApplicationInfo getApplicationInfo(String packageName, int flags) throws NameNotFoundException {
        return applicationInfo(packageName, Integer.toUnsignedLong(flags));
    }

    @Override public ApplicationInfo getApplicationInfo(String packageName, ApplicationInfoFlags flags) throws NameNotFoundException {
        if (flags == null) throw new IllegalArgumentException("APPLICATION_INFO_FLAGS_REQUIRED");
        return applicationInfo(packageName, flags.getValue());
    }

    private ApplicationInfo applicationInfo(String packageName, long flags) throws NameNotFoundException {
        requireSelf(packageName);
        long supported = GET_META_DATA | GET_SHARED_LIBRARY_FILES | GET_ACTIVITIES | GET_RECEIVERS | GET_SERVICES | GET_PROVIDERS | MATCH_DISABLED_COMPONENTS | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE | MATCH_UNINSTALLED_PACKAGES | MATCH_DEFAULT_ONLY | MATCH_DIRECT_BOOT_AUTO;
        if ((flags & ~supported) != 0)
            throw new UnsupportedOperationException("APPLICATION_INFO_FLAGS_UNSUPPORTED:0x" + Long.toHexString(flags));
        return copyApplicationInfo(engine.info, (flags & GET_META_DATA) != 0);
    }

    static ApplicationInfo copyApplicationInfo(ApplicationInfo source, boolean includeMetadata) {
        ApplicationInfo copy = new ApplicationInfo(source);
        if (source.splitNames != null) copy.splitNames = source.splitNames.clone();
        if (source.splitSourceDirs != null) copy.splitSourceDirs = source.splitSourceDirs.clone();
        if (source.splitPublicSourceDirs != null) copy.splitPublicSourceDirs = source.splitPublicSourceDirs.clone();
        copy.metaData = includeMetadata && source.metaData != null ? source.metaData.deepCopy() : null;
        if (source.packageName != null) {
            copy.dataDir = "/data/user/0/" + source.packageName;
            copy.deviceProtectedDataDir = "/data/user_de/0/" + source.packageName;
            copy.sourceDir = "/data/app/~~tripoder/" + source.packageName + "/base.apk";
            copy.publicSourceDir = "/data/app/~~tripoder/" + source.packageName + "/base.apk";
            copy.nativeLibraryDir = "/data/app/~~tripoder/" + source.packageName + "/lib";
            setPrimaryCpuAbi(copy, "arm64-v8a");
        }
        return copy;
    }

    private static volatile java.lang.reflect.Field primaryCpuAbiField;
    static void setPrimaryCpuAbi(ApplicationInfo info, String abi) {
        if (info == null) return;
        try {
            java.lang.reflect.Field field = primaryCpuAbiField;
            if (field == null) {
                try {
                    field = ApplicationInfo.class.getField("primaryCpuAbi");
                } catch (Throwable ignored) {
                    field = ApplicationInfo.class.getDeclaredField("primaryCpuAbi");
                    field.setAccessible(true);
                }
                primaryCpuAbiField = field;
            }
            field.set(info, abi);
        } catch (Throwable ignored) {}
    }

    @Override public XmlResourceParser getXml(String packageName, int resourceId, ApplicationInfo application) {
        if (!engine.packageName.equals(packageName)) return null;
        if (application != null && !packageName.equals(application.packageName))
            throw new SecurityException("XML_APPLICATION_IDENTITY_MISMATCH");
        // Caller-supplied paths never select an APK. Only the verified installation owns resources.
        try { return resources.getXml(resourceId); }
        catch (Resources.NotFoundException missing) { return null; }
    }

    @Override public Resources getResourcesForApplication(String packageName) throws NameNotFoundException {
        requireSelf(packageName);
        return resources;
    }

    @Override public Resources getResourcesForApplication(ApplicationInfo application) throws NameNotFoundException {
        if (application == null) throw new IllegalArgumentException("APPLICATION_INFO_REQUIRED");
        requireSelf(application.packageName);
        return resources;
    }

    @Override public ServiceInfo getServiceInfo(ComponentName component,int flags)throws NameNotFoundException{return getServiceInfo(component,ComponentInfoFlags.of(Integer.toUnsignedLong(flags)));}
    @Override public android.content.pm.ActivityInfo getActivityInfo(ComponentName component,int flags)throws NameNotFoundException{return GuestActivityInfo.get(engine,component,Integer.toUnsignedLong(flags));}
    @Override public android.content.pm.ActivityInfo getActivityInfo(ComponentName component,ComponentInfoFlags flags)throws NameNotFoundException{if(flags==null)throw new IllegalArgumentException("ACTIVITY_INFO_FLAGS_REQUIRED");return GuestActivityInfo.get(engine,component,flags.getValue());}
    @Override public ServiceInfo getServiceInfo(ComponentName component,ComponentInfoFlags flags)throws NameNotFoundException{
        if(component==null||flags==null)throw new IllegalArgumentException("SERVICE_COMPONENT_REQUIRED");requireSelf(component.getPackageName());
        long value=flags.getValue();if((value&~(long)(GET_META_DATA|MATCH_DISABLED_COMPONENTS|MATCH_DIRECT_BOOT_AWARE|MATCH_DIRECT_BOOT_UNAWARE|MATCH_DIRECT_BOOT_AUTO))!=0)throw new UnsupportedOperationException("SERVICE_INFO_FLAGS_UNSUPPORTED:0x"+Long.toHexString(value));
        ServiceInfo raw=engine.serviceIndex.get(component.getClassName());
        long directBoot=value&(MATCH_DIRECT_BOOT_AWARE|MATCH_DIRECT_BOOT_UNAWARE);
        if(directBoot==0&&(value&MATCH_DIRECT_BOOT_AUTO)!=0)directBoot=MATCH_DIRECT_BOOT_AWARE|(engine.host.getSystemService(android.os.UserManager.class).isUserUnlocked()?MATCH_DIRECT_BOOT_UNAWARE:0);
        if(raw==null||!raw.enabled&&(value&MATCH_DISABLED_COMPONENTS)==0||directBoot!=0&&(value&(raw.directBootAware?MATCH_DIRECT_BOOT_AWARE:MATCH_DIRECT_BOOT_UNAWARE))==0)throw new NameNotFoundException(component.flattenToString());
        ServiceInfo copy=new ServiceInfo(raw);copy.applicationInfo=copyApplicationInfo(engine.info,(value&GET_META_DATA)!=0);
        copy.metaData=(value&GET_META_DATA)==0||raw.metaData==null?null:raw.metaData.deepCopy();return copy;
    }

    private ProviderInfo providerInfo(ProviderInfo info,long flags){
        long supported=GET_META_DATA|MATCH_DISABLED_COMPONENTS|GET_URI_PERMISSION_PATTERNS|MATCH_DIRECT_BOOT_AWARE|MATCH_DIRECT_BOOT_UNAWARE|MATCH_DIRECT_BOOT_AUTO;
        if((flags&~supported)!=0)throw new UnsupportedOperationException("PROVIDER_INFO_FLAGS_UNSUPPORTED");
        if(info==null||!info.enabled&&(flags&MATCH_DISABLED_COMPONENTS)==0)return null;
        ProviderInfo result=new ProviderInfo(info);result.applicationInfo=copyApplicationInfo(engine.info,(flags&GET_META_DATA)!=0);
        result.metaData=(flags&GET_META_DATA)==0||info.metaData==null?null:info.metaData.deepCopy();
        if((flags&GET_URI_PERMISSION_PATTERNS)==0)result.uriPermissionPatterns=null;
        return result;
    }
    @Override public ProviderInfo getProviderInfo(ComponentName component,int flags)throws NameNotFoundException{return getProviderInfo(component,ComponentInfoFlags.of(Integer.toUnsignedLong(flags)));}
    @Override public ProviderInfo getProviderInfo(ComponentName component,ComponentInfoFlags flags)throws NameNotFoundException{
        if(component==null||flags==null)throw new IllegalArgumentException("PROVIDER_COMPONENT_REQUIRED");requireSelf(component.getPackageName());
        ProviderInfo raw;
        try{raw=engine.providerIndex.component(component.getClassName());}catch(IllegalStateException missing){throw new NameNotFoundException(component.flattenToString());}
        ProviderInfo result=providerInfo(raw,flags.getValue());if(result==null)throw new NameNotFoundException(component.flattenToString());return result;
    }
    @Override public ProviderInfo resolveContentProvider(String authority,int flags){return resolveContentProvider(authority,ComponentInfoFlags.of(Integer.toUnsignedLong(flags)));}
    @Override public ProviderInfo resolveContentProvider(String authority,ComponentInfoFlags flags){
        if(flags==null)throw new IllegalArgumentException("PROVIDER_FLAGS_REQUIRED");
        RuntimeProviderBroker.authority(authority);ProviderInfo info=engine.providerIndex.authority(authority);
        // An authority outside this package is not visible: Android resolves it to null.
        if(info==null)return null;
        return providerInfo(info,flags.getValue());
    }
}
