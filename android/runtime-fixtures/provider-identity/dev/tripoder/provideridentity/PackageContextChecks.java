package dev.tripoder.provideridentity;

import android.content.Context;
import org.json.*;

/** Public SDK checks shared by the platform baseline and the guest fixture. */
final class PackageContextChecks {
    static void inspect(Context source,boolean baseline,JSONArray checks)throws Exception{
        for(int flags:new int[]{0,Context.CONTEXT_IGNORE_SECURITY,Context.CONTEXT_INCLUDE_CODE,Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY}){
            Context derived=source.createPackageContext(source.getPackageName(),flags);
            boolean include=(flags&Context.CONTEXT_INCLUDE_CODE)!=0;
            check(checks,"package-context-new-identity:"+flags,derived!=source&&derived.getPackageName().equals(source.getPackageName())&&derived.getApplicationContext()==(include?source.getApplicationContext():null));
            check(checks,"package-context-owned-paths:"+flags,derived.getDataDir().equals(source.getDataDir())&&derived.getFilesDir().equals(source.getFilesDir())&&derived.getPackageCodePath().equals(source.getPackageCodePath()));
            int label=source.getResources().getIdentifier("creation_marker","string",source.getPackageName());
            check(checks,"package-context-owned-assets:"+flags,label!=0&&"owned creation resources".equals(derived.getString(label))&&derived.getApplicationInfo().packageName.equals(source.getPackageName()));
            boolean loaded=false;
            try{loaded=derived.getClassLoader().loadClass(PackageContextChecks.class.getName())==PackageContextChecks.class;}catch(ClassNotFoundException expected){}
            check(checks,"package-context-code-flag:"+flags,loaded==include);
            Context other=source.createPackageContext(source.getPackageName(),flags);
            check(checks,"package-context-independent-theme:"+flags,derived.getTheme()!=other.getTheme());
            derived.setTheme(android.R.style.Theme_Material_Light_NoActionBar);
            check(checks,"package-context-theme-has-own-resources:"+flags,derived.getTheme().getResources()==derived.getResources());
        }
        if(!baseline){
            boolean foreign=false;try{source.createPackageContext("dev.tripoder.runtimeprobe",0);}catch(UnsupportedOperationException expected){foreign=expected.getMessage().equals("CROSS_PACKAGE_CONTEXT_UNSUPPORTED");}
            check(checks,"package-context-host-package-not-accessible",foreign);
            boolean flags=false;try{source.createPackageContext(source.getPackageName(),Context.CONTEXT_RESTRICTED);}catch(UnsupportedOperationException expected){flags=expected.getMessage().startsWith("PACKAGE_CONTEXT_FLAGS_UNSUPPORTED");}
            check(checks,"package-context-unimplemented-flags-not-ignored",flags);
        }
    }
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    private PackageContextChecks(){}
}
