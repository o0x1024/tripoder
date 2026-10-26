package dev.tripoder.selfhook;

import java.lang.reflect.Field;
import java.util.*;

/** Publish the existing settings-only transport; preserve SDK values and generation trackers. */
public final class SettingsNamespaceAccess {
    private static boolean attempted;private static volatile boolean published;
    private static final Field[] caches=new Field[4];
    private static Field holderField,lockField,providerField;
    private static final Object[] cacheRoots=new Object[4],holders=new Object[4],locks=new Object[4];
    private static Object handle;private static int cold,warm;
    public static synchronized void publish(Object selected){
        if(attempted)throw new IllegalStateException("SETTINGS_NAMESPACE_ALREADY_ATTEMPTED");attempted=true;Objects.requireNonNull(selected);
        try{
            holderField=FixedFrameworkMembers.field(61,75,"mProviderHolder","Landroid/provider/Settings$ContentProviderHolder;",false);
            lockField=FixedFrameworkMembers.field(62,76,"mLock","Ljava/lang/Object;",false);
            providerField=FixedFrameworkMembers.field(63,76,"mContentProvider","Landroid/content/IContentProvider;",false);
            // Resolve and initialize every table outside all provider locks.
            for(int i=0;i<4;i++){
                caches[i]=FixedFrameworkMembers.field(57+i,71+i,"sNameValueCache","Landroid/provider/Settings$NameValueCache;",true);
                cacheRoots[i]=Objects.requireNonNull(caches[i].get(null),"SETTINGS_CACHE_MISSING");
                holders[i]=Objects.requireNonNull(holderField.get(cacheRoots[i]),"SETTINGS_HOLDER_MISSING");
                locks[i]=Objects.requireNonNull(lockField.get(holders[i]),"SETTINGS_HOLDER_LOCK_MISSING");
            }
            for(int i=0;i<4;i++)synchronized(locks[i]){if(providerField.get(holders[i])==null)cold++;else warm++;}
            for(int i=0;i<4;i++)synchronized(locks[i]){
                providerField.set(holders[i],selected);
                if(providerField.get(holders[i])!=selected)throw new IllegalStateException("SETTINGS_NAMESPACE_BIND_NOT_PERSISTED");
            }
            handle=selected;published=true;check();
        }catch(Exception failure){throw new IllegalStateException("SETTINGS_NAMESPACE_FIXED_BIND_FAILED",failure);}
    }
    public static synchronized Map<String,Object> check(){
        if(!published)throw new IllegalStateException("SETTINGS_NAMESPACE_NOT_PUBLISHED");
        try{
            for(int i=0;i<4;i++){
                if(caches[i].get(null)!=cacheRoots[i]||holderField.get(cacheRoots[i])!=holders[i]||lockField.get(holders[i])!=locks[i])throw new SecurityException("SETTINGS_CACHE_IDENTITY_CHANGED");
                synchronized(locks[i]){if(providerField.get(holders[i])!=handle)throw new SecurityException("SETTINGS_NAMESPACE_HANDLE_CHANGED");}
            }
            return Map.of("tables",4,"cold",cold,"warm",warm,"sameHandle",true,"cacheIdentityPreserved",true);
        }catch(IllegalAccessException failure){throw new IllegalStateException("SETTINGS_NAMESPACE_CHECK_FAILED",failure);}
    }
    private SettingsNamespaceAccess(){}
}
