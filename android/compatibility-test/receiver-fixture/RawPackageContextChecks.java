package dev.tripoder.closurefixture;

import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;

/** The real ContextImpl public entries must reach the existing owned package seam. */
final class RawPackageContextChecks {
    private static Context raw(Context context)throws Exception{
        Class<?> exact=Class.forName("android.app.ContextImpl",false,null);Set<Context> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        while(context!=null&&seen.size()<16&&seen.add(context)){if(context.getClass()==exact)return context;if(!(context instanceof ContextWrapper))break;context=((ContextWrapper)context).getBaseContext();}throw new AssertionError("OWNED_RAW_CONTEXT_REQUIRED");
    }
    static void run(Activity activity,ResourceOriginChecks.Check check)throws Exception{
        Context original=raw(activity.getApplication());String pkg=activity.getPackageName();
        check.test("raw-context-uses-existing-owned-resolver",original.getContentResolver()==activity.getContentResolver());
        Context configured=original.createConfigurationContext(new android.content.res.Configuration(original.getResources().getConfiguration()));
        check.test("raw-derived-settings-read-keeps-real-uid-attribution",Objects.equals(android.provider.Settings.System.getString(configured.getContentResolver(),android.provider.Settings.System.SCREEN_OFF_TIMEOUT),android.provider.Settings.System.getString(activity.getContentResolver(),android.provider.Settings.System.SCREEN_OFF_TIMEOUT)));
        String key="tripoder_owned_must_not_write";String before=android.provider.Settings.System.getString(activity.getContentResolver(),key);Bundle values=new Bundle();values.putString("value","owned");boolean writeDenied=false;
        try{configured.getContentResolver().call("settings","PUT_system",key,values);}catch(UnsupportedOperationException expected){writeDenied=true;}
        check.test("raw-system-settings-write-is-explicitly-rejected-without-mutation",writeDenied&&Objects.equals(before,android.provider.Settings.System.getString(activity.getContentResolver(),key)));
        Context resources=original.createPackageContext(pkg,0);
        check.test("raw-package-context-zero-flags-has-logical-pm",resources.getPackageManager().getApplicationInfo(pkg,0).packageName.equals(pkg)&&resources.getResources().getAssets()==activity.getApplication().getResources().getAssets());
        boolean hidden=false;try{Class.forName(Main.class.getName(),false,resources.getClassLoader());}catch(ClassNotFoundException expected){hidden=true;}
        check.test("raw-resources-only-context-does-not-expose-guest-dex",hidden);
        Context code=original.createPackageContext(pkg,Context.CONTEXT_INCLUDE_CODE);
        check.test("raw-include-code-context-keeps-exact-guest-class",Class.forName(Main.class.getName(),false,code.getClassLoader())==Main.class);
        boolean absent=false;try{original.createPackageContext("dev.tripoder.absent.foreign",0);}catch(PackageManager.NameNotFoundException expected){absent=true;}
        check.test("raw-package-context-keeps-foreign-package-absent",absent);
        Method asUser=original.getClass().getDeclaredMethod("createPackageContextAsUser",String.class,int.class,UserHandle.class);asUser.setAccessible(true);
        Context own=(Context)asUser.invoke(original,pkg,0,android.os.Process.myUserHandle());
        check.test("raw-package-context-own-user-has-logical-pm",own.getPackageManager().getApplicationInfo(pkg,0).packageName.equals(pkg));
        boolean denied=false;try{asUser.invoke(original,pkg,0,UserHandle.getUserHandleForUid(android.os.Process.myUid()+100000));}catch(InvocationTargetException expected){denied=expected.getCause() instanceof SecurityException;}
        check.test("raw-package-context-cannot-borrow-another-user",denied);
    }
    private RawPackageContextChecks(){}
}
