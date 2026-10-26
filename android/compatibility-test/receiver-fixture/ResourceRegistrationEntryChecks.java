package dev.tripoder.closurefixture;

import android.app.Activity;
import android.content.ContentProviderClient;
import android.content.pm.ApplicationInfo;
import java.lang.reflect.*;
import java.util.Arrays;

/** Invalid framework owner and lock order are rejected before resource effects. */
final class ResourceRegistrationEntryChecks {
    private static Throwable call(Method method,Object receiver,String owner,ApplicationInfo info)throws Exception{
        try{method.invoke(receiver,owner,new ApplicationInfo(info));return null;}
        catch(InvocationTargetException error){return error.getCause();}
    }
    static void run(Activity activity,String owner,ApplicationInfo info,ResourceOriginChecks.Check check)throws Exception{
        Method register=null,instance=null;Constructor<?> constructor=null;Field lock;
        try(ContentProviderClient settings=activity.getContentResolver().acquireContentProviderClient("settings")){
            Class<?> access=Class.forName("dev.tripoder.selfhook.FrameworkAccess",false,settings.getLocalContentProvider().getClass().getClassLoader());
            Method members=access.getDeclaredMethod("fixedMembers",int.class);members.setAccessible(true);
            for(Object candidate:(Object[])members.invoke(null,77)){
                if(candidate instanceof Constructor<?> value&&value.getParameterCount()==0)constructor=value;
                if(candidate instanceof Method value){
                    if(value.getName().equals("getInstance")&&Modifier.isStatic(value.getModifiers())&&value.getParameterCount()==0)instance=value;
                    if(value.getName().equals("registerResourcePaths")&&!Modifier.isStatic(value.getModifiers())&&value.getReturnType()==void.class&&Arrays.equals(value.getParameterTypes(),new Class<?>[]{String.class,ApplicationInfo.class}))register=value;
                }
            }
            Method field=access.getDeclaredMethod("fixedField",int.class);field.setAccessible(true);lock=(Field)field.invoke(null,64);
        }
        if(register==null||instance==null||constructor==null||lock==null)throw new AssertionError("OWNED_RESOURCE_MANAGER_FIXED_MEMBERS_REQUIRED");
        register.setAccessible(true);instance.setAccessible(true);constructor.setAccessible(true);lock.setAccessible(true);
        Object manager=instance.invoke(null),other=constructor.newInstance();
        Throwable foreign=call(register,other,owner,info);
        check.test("resource-registration-rejects-another-framework-manager-before-effects",foreign instanceof SecurityException&&"RESOURCE_DEPENDENCY_REGISTRATION_RECEIVER_REQUIRED".equals(foreign.getMessage()));
        Throwable held;Object monitor=lock.get(manager);if(monitor==null)throw new AssertionError("OWNED_RESOURCE_MANAGER_LOCK_REQUIRED");
        synchronized(monitor){held=call(register,manager,owner,info);}
        check.test("resource-registration-rejects-caller-held-manager-lock-before-waiting",held instanceof IllegalStateException&&"RESOURCE_DEPENDENCY_REGISTRATION_LOCK_HELD".equals(held.getMessage()));
    }
    private ResourceRegistrationEntryChecks(){}
}
