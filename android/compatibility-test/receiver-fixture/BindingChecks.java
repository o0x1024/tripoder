package dev.tripoder.closurefixture;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;

/** Guest observations of real boot objects at every factory/Application boundary. */
final class BindingChecks {
    static int phases,preparedFactoryPhases;static boolean factoryArgumentIsolated;
    private static Object read(Object owner,String name)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    static void deferOwnedProvider(){
        if(!Application.getProcessName().equals("dev.tripoder.closurefixture"))return;
        try{
            Object thread=Class.forName("android.app.ActivityThread",false,null).getDeclaredMethod("currentActivityThread").invoke(null),bound=read(thread,"mBoundApplication");
            Field field=bound.getClass().getDeclaredField("providers");field.setAccessible(true);
            Object value=field.get(bound);if(!(value instanceof java.util.List<?>))throw new AssertionError("OWNED_BOOTSTRAP_PROVIDER_LIST_REQUIRED");
            java.util.List<Object> selected=new java.util.ArrayList<>();int removed=0,tail=-1,canonical=-1,index=0;Object canonicalInfo=null;
            for(Object item:(java.util.List<?>)value){
                String name=((android.content.pm.ProviderInfo)item).name;
                if(name.equals(TailProvider.class.getName()))tail=index;
                if(name.equals(CanonicalProvider.class.getName())){canonical=index;canonicalInfo=item;}
                if(name.equals(DeferredProvider.class.getName())||name.equals(FailedProvider.class.getName())||name.equals(OwnedDocumentsProvider.class.getName())||name.equals(OwnedDocumentsProvider.Forged.class.getName()))removed++;else selected.add(item);index++;
            }
            ProviderOrder.initialOrder=tail>=0&&canonical>=0&&tail<canonical;
            if(removed!=4||canonicalInfo==null)throw new AssertionError("OWNED_DEFERRED_PROVIDER_SELECTOR_MISSING");
            selected.remove(canonicalInfo);selected.add(0,canonicalInfo);field.set(bound,selected);
        }catch(RuntimeException|Error error){throw error;}catch(Exception error){throw new IllegalStateException("OWNED_PROVIDER_REQUEST_REPLACEMENT",error);}
    }
    static void phase(String stage,Application expected,ClassLoader defaultLoader,Object factory){
        try{
            Class<?> at=Class.forName("android.app.ActivityThread",false,null);Object thread=at.getDeclaredMethod("currentActivityThread").invoke(null);
            Object bound=read(thread,"mBoundApplication"),apk=read(bound,"info");ApplicationInfo info=(ApplicationInfo)read(bound,"appInfo");
            if(!"dev.tripoder.closurefixture".equals(info.packageName)||info.uid!=android.os.Process.myUid()||info.sourceDir==null||info.dataDir==null||!Application.getProcessName().equals(read(bound,"processName"))||read(apk,"mApplicationInfo")!=info||read(apk,"mApplication")!=expected||at.getDeclaredMethod("currentApplication").invoke(null)!=expected||at.getDeclaredMethod("getApplication").invoke(thread)!=expected)throw new AssertionError("BIND_RECORD:"+stage);
            Map<?,?> packages=(Map<?,?>)read(thread,"mPackages");Object entry=packages.get(info.packageName);
            if(!(entry instanceof WeakReference<?>)||((WeakReference<?>)entry).get()!=apk)throw new AssertionError("PACKAGE_CACHE:"+stage);
            if(defaultLoader!=null&&Class.forName(OwnedFactory.class.getName(),false,defaultLoader)!=OwnedFactory.class)throw new AssertionError("DEFAULT_LOADER:"+stage);
            if(factory!=null&&factory.getClass()!=OwnedFactory.class)throw new AssertionError("FACTORY_IDENTITY:"+stage);
            if(stage.equals("factory-clinit")||stage.equals("factory-constructor")){
                preparedFactoryPhases|=stage.equals("factory-clinit")?1:2;
            }
            if(stage.equals("factory-application")&&defaultLoader!=OwnedFactory.selected)throw new AssertionError("SELECTED_LOADER:"+stage);
            phases++;
        }catch(RuntimeException|Error error){throw error;}catch(Exception error){throw new IllegalStateException("BINDING_OBSERVATION:"+stage,error);}
    }
    static void classLoaderArgument(ApplicationInfo supplied){
        try{
            Object thread=Class.forName("android.app.ActivityThread",false,null).getDeclaredMethod("currentActivityThread").invoke(null);
            ApplicationInfo bound=(ApplicationInfo)read(read(thread,"mBoundApplication"),"appInfo");
            if(supplied==bound||!java.util.Objects.equals(supplied.packageName,bound.packageName)||!java.util.Objects.equals(supplied.sourceDir,bound.sourceDir)||supplied.uid!=bound.uid)throw new AssertionError("SDK_FACTORY_LOADER_ARGUMENT_COPY");
            String path=bound.sourceDir;supplied.packageName="dev.tripoder.factory.argument";supplied.sourceDir="/owned-factory-argument/base.apk";supplied.uid=-1;
            factoryArgumentIsolated="dev.tripoder.closurefixture".equals(bound.packageName)&&java.util.Objects.equals(path,bound.sourceDir)&&bound.uid==android.os.Process.myUid();
            if(!factoryArgumentIsolated)throw new AssertionError("SDK_FACTORY_ARGUMENT_CHANGED_CANONICAL_BINDING");
        }catch(RuntimeException|Error error){throw error;}catch(Exception error){throw new IllegalStateException("OWNED_FACTORY_ARGUMENT_OBSERVATION",error);}
    }
    private BindingChecks(){}
}
