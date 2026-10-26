package dev.tripoder.closurefixture;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.provider.Settings;
import java.lang.reflect.*;
import java.util.*;

/** Verify selected SDK transports BEFORE testing setters; no real settings backend is written. */
final class SettingsNamespaceChecks {
    private interface Write {void run()throws Exception;}
    private static Object read(Class<?> owner,Object receiver,String name)throws Exception{Field field=owner.getDeclaredField(name);field.setAccessible(true);return field.get(receiver);}
    // Config's hidden members are filtered from ordinary app reflection. Use the
    // already resolved boot-member snapshot, then independently verify identity.
    private static Field fixedField(ClassLoader loader,int slot,Class<?> owner,String name,Class<?> type,boolean stat)throws Exception{
        Class<?> access=Class.forName("dev.tripoder.selfhook.FrameworkAccess",false,loader);Method lookup=access.getDeclaredMethod("fixedField",int.class);lookup.setAccessible(true);
        Field field=(Field)lookup.invoke(null,slot);
        if(field==null||field.getDeclaringClass()!=owner||!field.getName().equals(name)||field.getType()!=type||Modifier.isStatic(field.getModifiers())!=stat)throw new AssertionError("OWNED_SETTINGS_FIELD_IDENTITY:"+name);
        field.setAccessible(true);return field;
    }
    private static Method configSetter(ClassLoader loader,Class<?> config)throws Exception{
        Class<?> access=Class.forName("dev.tripoder.selfhook.FrameworkAccess",false,loader);Method lookup=access.getDeclaredMethod("fixedMembers",int.class);lookup.setAccessible(true);
        Method found=null;for(Object member:(Object[])lookup.invoke(null,74))if(member instanceof Method){Method method=(Method)member;
            if(method.getDeclaringClass()==config&&method.getName().equals("putString")&&method.getReturnType()==boolean.class&&Modifier.isStatic(method.getModifiers())&&Arrays.equals(method.getParameterTypes(),new Class<?>[]{String.class,String.class,String.class,boolean.class})){
                if(found!=null)throw new AssertionError("OWNED_CONFIG_SETTER_AMBIGUOUS");found=method;
            }
        }
        if(found==null)throw new AssertionError("OWNED_CONFIG_SETTER_MISSING");found.setAccessible(true);return found;
    }
    private static void rejected(String label,Write write,ResourceOriginChecks.Check check)throws Exception{
        boolean denied=false;try{write.run();}catch(UnsupportedOperationException expected){denied="PROVIDER_CALL_UNSUPPORTED".equals(expected.getMessage());}
        catch(InvocationTargetException expected){Throwable cause=expected.getCause();denied=cause instanceof UnsupportedOperationException&&"PROVIDER_CALL_UNSUPPORTED".equals(cause.getMessage());}
        check.test(label,denied);
    }
    static void run(Activity activity,ResourceOriginChecks.Check check)throws Exception{
        ContentResolver resolver=activity.getContentResolver();
        try(ContentProviderClient client=resolver.acquireContentProviderClient("settings")){
            if(client==null)throw new AssertionError("OWNED_SETTINGS_CLIENT_REQUIRED");
            Object selected=read(ContentProviderClient.class,client,"mContentProvider");ContentProvider provider=client.getLocalContentProvider();
            check.test("settings-sdk-selected-transport-is-existing-readonly-provider",selected!=null&&provider!=null&&provider.getClass().getName().equals("dev.tripoder.runtime.SettingsReadProvider"));
            ClassLoader engineLoader=provider.getClass().getClassLoader();
            Class<?> cacheType=Class.forName("android.provider.Settings$NameValueCache",false,null),holderType=Class.forName("android.provider.Settings$ContentProviderHolder",false,null),transportType=Class.forName("android.content.IContentProvider",false,null);
            String[] tables={"System","Secure","Global","Config"};for(int i=0;i<tables.length;i++){
                Class<?> owner=Class.forName("android.provider.Settings$"+tables[i],false,null);Object cache=fixedField(engineLoader,57+i,owner,"sNameValueCache",cacheType,true).get(null),holder=fixedField(engineLoader,61,cacheType,"mProviderHolder",holderType,false).get(cache);
                check.test("settings-sdk-"+tables[i]+"-cache-binds-same-owned-transport",fixedField(engineLoader,63,holderType,"mContentProvider",transportType,false).get(holder)==selected);
            }
            // All four holders are now positively proven local and readonly.
            // The device validator separately proves its backend sees no write.
            String timeout=Settings.System.getString(resolver,Settings.System.SCREEN_OFF_TIMEOUT),key="tripoder_owned_readonly_"+UUID.randomUUID();
            rejected("settings-sdk-system-setter-rejects-through-owned-cache",()->Settings.System.putString(resolver,key,"owned"),check);
            rejected("settings-sdk-secure-setter-rejects-through-owned-cache",()->Settings.Secure.putString(resolver,key,"owned"),check);
            rejected("settings-sdk-global-setter-rejects-through-owned-cache",()->Settings.Global.putString(resolver,key,"owned"),check);
            Class<?> config=Class.forName("android.provider.Settings$Config",false,null);Method put=configSetter(engineLoader,config);
            rejected("settings-sdk-config-setter-uses-owned-holder",()->put.invoke(null,"tripoder_owned",key,"owned",false),check);
            check.test("settings-sdk-warmed-read-keeps-actual-value",Objects.equals(timeout,Settings.System.getString(resolver,Settings.System.SCREEN_OFF_TIMEOUT)));
            boolean bulkDenied=false;try{resolver.bulkInsert(Settings.System.CONTENT_URI,new ContentValues[0]);}catch(UnsupportedOperationException expected){bulkDenied="PROVIDER_WRITE_UNSUPPORTED".equals(expected.getMessage());}
            check.test("settings-router-empty-bulk-never-falls-through-to-physical-provider",bulkDenied);
            boolean batchDenied=false;try{resolver.applyBatch("settings",new ArrayList<>());}catch(UnsupportedOperationException expected){batchDenied="PROVIDER_WRITE_UNSUPPORTED".equals(expected.getMessage());}
            check.test("settings-router-empty-batch-never-falls-through-to-physical-provider",batchDenied);
        }
    }
    private SettingsNamespaceChecks(){}
}
