package dev.tripoder.closurefixture;

import android.app.Activity;
import android.content.ContentProviderClient;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/** Read the fixed framework field, without relying on hidden-API reflection. */
final class FactoryOwnerChecks {
    static void run(Activity activity,ResourceOriginChecks.Check check)throws Exception{
        try(ContentProviderClient settings=activity.getContentResolver().acquireContentProviderClient("settings")){
            ClassLoader runtime=settings.getLocalContentProvider().getClass().getClassLoader();
            Class<?> adapter=Class.forName("dev.tripoder.selfhook.ApplicationAdapter",false,runtime);
            Map<?,?> evidence=(Map<?,?>)adapter.getMethod("factoryEvaluationEvidence").invoke(null);
            check.test("sdk-prepared-factory-owner-retained-across-real-declared-initializers",BindingChecks.preparedFactoryPhases==3&&Boolean.TRUE.equals(evidence.get("attempted"))&&Boolean.TRUE.equals(evidence.get("completed"))&&Boolean.TRUE.equals(evidence.get("preparedOwnerVerified")));
            Object logical=adapter.getMethod("packageInfo").invoke(null);
            Class<?> access=Class.forName("dev.tripoder.selfhook.FrameworkAccess",false,runtime);Method member=access.getDeclaredMethod("fixedField",int.class);member.setAccessible(true);
            Field field=(Field)member.invoke(null,7);if(field==null||!field.getName().equals("mAppComponentFactory")||!field.getDeclaringClass().getName().equals("android.app.LoadedApk"))throw new AssertionError("OWNED_FACTORY_FIXED_FIELD_REQUIRED");field.setAccessible(true);
            check.test("real-sdk-selected-factory-is-the-published-owned-instance",field.get(logical)==OwnedFactory.instance);
        }
    }
    private FactoryOwnerChecks(){}
}
