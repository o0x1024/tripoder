package dev.tripoder.selfhook;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/** The platform bind record and cache point at the same prepared logical LoadedApk. */
final class ApplicationBinding {
    private static Field boundField,appInfoField,infoField,processField,providersField,packagesField;
    private static Object thread,bound,logical,physical,oldAppInfo,oldInfo,oldProcess,oldProviders,oldGuestEntry,hostEntry;
    private static ApplicationInfo declared;
    private static String guestPackage,hostPackage,process;
    private static List<ProviderInfo> providers;
    private static Map<String,Object> packages;
    private static volatile boolean published;private static boolean guestEntryExisted;
    @SuppressWarnings("unchecked")
    static void publish(Application host,ApplicationInfo info,Object apk,String processName,List<ProviderInfo> declarations){
        if(published||thread!=null)throw new IllegalStateException("APPLICATION_BINDING_ALREADY_ATTEMPTED");
        try{
            boundField=FixedFrameworkMembers.field(47,1,"mBoundApplication","Landroid/app/ActivityThread$AppBindData;",false);
            appInfoField=FixedFrameworkMembers.field(48,66,"appInfo","Landroid/content/pm/ApplicationInfo;",false);
            infoField=FixedFrameworkMembers.field(49,66,"info","Landroid/app/LoadedApk;",false);
            processField=FixedFrameworkMembers.field(50,66,"processName","Ljava/lang/String;",false);
            providersField=FixedFrameworkMembers.field(51,66,"providers","Ljava/util/List;",false);
            packagesField=FixedFrameworkMembers.field(52,1,"mPackages","Landroid/util/ArrayMap;",false);
            thread=FixedFrameworkMembers.call(FixedFrameworkMembers.method(1,"currentActivityThread","()Landroid/app/ActivityThread;",true),null);
            physical=FixedFrameworkMembers.field(1,0,"mLoadedApk","Landroid/app/LoadedApk;",false).get(host);
            bound=boundField.get(thread);if(bound==null||physical==null||infoField.get(bound)!=physical)throw new IllegalStateException("APPLICATION_PHYSICAL_BINDING_MISMATCH");
            oldAppInfo=appInfoField.get(bound);oldInfo=infoField.get(bound);oldProcess=processField.get(bound);oldProviders=providersField.get(bound);
            packages=(Map<String,Object>)packagesField.get(thread);hostPackage=host.getPackageName();guestPackage=info.packageName;
            if(guestPackage.equals(hostPackage)||packages==null)throw new IllegalStateException("APPLICATION_BINDING_PACKAGE_MISMATCH");
            logical=apk;declared=info;process=processName;providers=declarations==null?null:new java.util.ArrayList<>(declarations);
            synchronized(packages){
                hostEntry=packages.get(hostPackage);if(!(hostEntry instanceof WeakReference<?>)||((WeakReference<?>)hostEntry).get()!=physical)throw new IllegalStateException("APPLICATION_PHYSICAL_CACHE_MISMATCH");
                guestEntryExisted=packages.containsKey(guestPackage);oldGuestEntry=packages.get(guestPackage);
                if(oldGuestEntry!=null&&(!(oldGuestEntry instanceof WeakReference<?>)||((WeakReference<?>)oldGuestEntry).get()!=null))throw new IllegalStateException("APPLICATION_GUEST_CACHE_CONFLICT");
                appInfoField.set(bound,declared);infoField.set(bound,logical);processField.set(bound,process);providersField.set(bound,providers);
                packages.put(guestPackage,new WeakReference<>(logical));published=true;
            }
            check();
        }catch(Exception error){throw new IllegalStateException("APPLICATION_BINDING_FIXED_ACCESS_FAILED",error);}
    }
    static void check(){
        if(!published)return;
        try{
            if(boundField.get(thread)!=bound)throw new IllegalStateException("APPLICATION_LOGICAL_BINDING_CHANGED:mBoundApplication");
            if(appInfoField.get(bound)!=declared)throw new IllegalStateException("APPLICATION_LOGICAL_BINDING_CHANGED:appInfo");
            if(infoField.get(bound)!=logical)throw new IllegalStateException("APPLICATION_LOGICAL_BINDING_CHANGED:info");
            if(!process.equals(processField.get(bound)))throw new IllegalStateException("APPLICATION_LOGICAL_BINDING_CHANGED:processName");
            // This is guest-owned startup metadata. Shell Applications replace or
            // clear the list while loading their code; it grants no provider entry.
            // Actual publication and invocation remain declaration/lease checked.

            if(packagesField.get(thread)!=packages)throw new IllegalStateException("APPLICATION_LOGICAL_BINDING_CHANGED:mPackages");
            synchronized(packages){Object guest=packages.get(guestPackage);if(packages.get(hostPackage)!=hostEntry||((WeakReference<?>)hostEntry).get()!=physical||!(guest instanceof WeakReference<?>)||((WeakReference<?>)guest).get()!=logical)throw new IllegalStateException("APPLICATION_PACKAGE_CACHE_CHANGED");}
        }catch(IllegalAccessException error){throw new IllegalStateException("APPLICATION_BINDING_CHECK_FAILED",error);}
    }
    /** QA relations only: no roots, addresses, file paths or guest values escape. */
    static Map<String,Object> resourceRelations(android.content.Context context,android.content.res.Resources view,android.content.res.Resources initial)throws Exception{
        return resourceRelations(context,view,initial,null);
    }
    static Map<String,Object> resourceRelations(android.content.Context context,android.content.res.Resources view,android.content.res.Resources initial,ResourceFrameworkAccess.AssetSnapshot frozen)throws Exception{
        Map<String,Object> result=new java.util.LinkedHashMap<>();result.put("published",published);
        if(!published)return result;
        check();result.put("bindingStable",true);
        boolean exact=context!=null&&context.getClass()==FixedFrameworkMembers.owner(3);result.put("exactContext",exact);
        if(!exact)return result;
        Object root=FixedFrameworkMembers.field(54,3,"mPackageInfo","Landroid/app/LoadedApk;",false).get(context);
        Object main=FixedFrameworkMembers.field(55,3,"mMainThread","Landroid/app/ActivityThread;",false).get(context);
        result.put("contextLogical",root==logical);result.put("contextThread",main==thread);
        android.content.res.Resources current=(android.content.res.Resources)FixedFrameworkMembers.field(6,2,"mResources","Landroid/content/res/Resources;",false).get(logical);
        result.put("currentPresent",current!=null);result.put("viewInitial",view==initial);result.put("viewCurrent",view==current);result.put("currentInitial",current==initial);
        result.put("assetsInitial",view.getAssets()==initial.getAssets());result.put("assetsCurrent",current!=null&&view.getAssets()==current.getAssets());
        result.put("implInitial",ResourceFrameworkAccess.sameImplementation(view,initial));result.put("implCurrent",ResourceFrameworkAccess.sameImplementation(view,current));
        result.put("viewClass",view.getClass().getName());if(current!=null)result.put("currentClass",current.getClass().getName());
        if(frozen!=null){result.put("initialAssets",frozen.relations(initial));if(current!=null)result.put("currentAssets",frozen.relations(current));}
        return result;
    }
    static void requireLogicalContext(android.content.Context context){
        if(!published)throw new SecurityException("APPLICATION_BINDING_NOT_PUBLISHED");check();
        try{
            if(context==null||context.getClass()!=FixedFrameworkMembers.owner(3)||FixedFrameworkMembers.field(54,3,"mPackageInfo","Landroid/app/LoadedApk;",false).get(context)!=logical||FixedFrameworkMembers.field(55,3,"mMainThread","Landroid/app/ActivityThread;",false).get(context)!=thread)throw new SecurityException("APPLICATION_CONTEXT_LOGICAL_ROOT_REQUIRED");
        }catch(ReflectiveOperationException error){throw new IllegalStateException("APPLICATION_CONTEXT_FIXED_ACCESS_FAILED",error);}catch(SecurityException error){throw error;}catch(Exception error){throw new IllegalStateException("APPLICATION_CONTEXT_CHECK_FAILED",error);}
    }
    /** This mutable bind list is a bootstrap request, never a publication admission. */
    static List<ProviderInfo> providerInstallationBatch(){
        if(!published)throw new IllegalStateException("APPLICATION_BINDING_NOT_PUBLISHED");check();
        try{
            Object value=providersField.get(bound);if(value==null)return List.of();
            if(!(value instanceof List<?>))throw new SecurityException("APPLICATION_PROVIDER_BATCH_TYPE");
            List<?> source=(List<?>)value;if(source.size()>1024)throw new SecurityException("APPLICATION_PROVIDER_BATCH_TOO_LARGE");
            List<ProviderInfo> result=new java.util.ArrayList<>();
            for(Object item:source){if(item==null||item.getClass()!=ProviderInfo.class)throw new SecurityException("APPLICATION_PROVIDER_SELECTOR_TYPE");result.add(new ProviderInfo((ProviderInfo)item));}
            return List.copyOf(result);
        }catch(IllegalAccessException error){throw new IllegalStateException("APPLICATION_PROVIDER_BATCH_FIXED_ACCESS_FAILED",error);}
    }
    static void restoreProbe(){
        if(!published)throw new IllegalStateException("APPLICATION_BINDING_NOT_PUBLISHED");check();
        try{synchronized(packages){appInfoField.set(bound,oldAppInfo);infoField.set(bound,oldInfo);processField.set(bound,oldProcess);providersField.set(bound,oldProviders);if(guestEntryExisted)packages.put(guestPackage,oldGuestEntry);else packages.remove(guestPackage);published=false;}
            if(appInfoField.get(bound)!=oldAppInfo||infoField.get(bound)!=oldInfo||processField.get(bound)!=oldProcess||providersField.get(bound)!=oldProviders||packages.get(hostPackage)!=hostEntry)throw new IllegalStateException("APPLICATION_BINDING_RESTORE_FAILED");
        }catch(IllegalAccessException error){throw new IllegalStateException("APPLICATION_BINDING_RESTORE_FAILED",error);}
    }
    private ApplicationBinding(){}
}
