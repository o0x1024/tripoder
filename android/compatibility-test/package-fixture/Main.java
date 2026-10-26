package dev.tripoder.packagecontract;

import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.widget.TextView;
import java.lang.reflect.*;
import org.json.JSONObject;

/** Owned raw Context/facade/global objects only; never reads another application's data. */
public final class Main extends Activity {
    private final JSONObject checks=new JSONObject();
    private void check(String name,boolean passed)throws Exception{checks.put(name,passed);if(!passed)throw new AssertionError(name);}
    private static Field field(Method lookup,int slot,Class<?> owner,String name,Class<?> type,boolean stat)throws Exception{
        Field value=(Field)lookup.invoke(null,slot);
        if(value==null||value.getDeclaringClass()!=owner||!value.getName().equals(name)||value.getType()!=type||Modifier.isStatic(value.getModifiers())!=stat)throw new AssertionError("OWNED_PACKAGE_FIELD_IDENTITY:"+name);
        value.setAccessible(true);return value;
    }
    @Override public void onCreate(Bundle state){super.onCreate(state);TextView text=new TextView(this);setContentView(text);text.setText("RUNNING OWNED PACKAGE AUTHORITY CHECKS");new Thread(()->{
        JSONObject report=new JSONObject();try{run();report.put("passed",true);}catch(Throwable error){try{report.put("passed",false).put("error",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}}
        try{report.put("checks",checks);}catch(Exception ignored){}String result=report.toString();android.util.Log.i("TripoderPackageContract",result);runOnUiThread(()->text.setText(result));
    },"owned-package-authority").start();}
    private void run()throws Exception{
        ClassLoader runtime;
        try(ContentProviderClient client=getContentResolver().acquireContentProviderClient("settings")){
            if(client==null||client.getLocalContentProvider()==null)throw new AssertionError("OWNED_PACKAGE_LOCAL_RUNTIME_REQUIRED");runtime=client.getLocalContentProvider().getClass().getClassLoader();
        }
        Class<?> access=Class.forName("dev.tripoder.selfhook.FrameworkAccess",false,runtime),contextType=Class.forName("android.app.ContextImpl",false,null),managerType=Class.forName("android.app.ApplicationPackageManager",false,null),threadType=Class.forName("android.app.ActivityThread",false,null),contract=Class.forName("android.content.pm.IPackageManager",false,null);
        Method lookup=access.getDeclaredMethod("fixedField",int.class);lookup.setAccessible(true);
        Field endpoint=field(lookup,35,managerType,"mPM",contract,false),global=field(lookup,36,threadType,"sPackageManager",contract,true),cache=field(lookup,38,contextType,"mPackageManager",PackageManager.class,false);
        PackageManager initial=getPackageManager();Object canonical=endpoint.get(initial),originalGlobal=global.get(null);
        check("initial-facade-and-global-use-the-same-authority",canonical==originalGlobal);
        ComponentName undeclared=new ComponentName(getPackageName(),getPackageName()+".OwnedUndeclaredComponentProbe");
        check("undeclared-component-query-matches-native-default",initial.getComponentEnabledSetting(undeclared)==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        boolean refused=false;try{initial.setComponentEnabledSetting(undeclared,PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP);}catch(IllegalArgumentException error){refused=("COMPONENT_NOT_DECLARED:"+undeclared.getClassName()).equals(error.getMessage());}
        check("undeclared-component-state-write-remains-rejected",refused);
        check("rejected-undeclared-write-leaves-default-query-state",initial.getComponentEnabledSetting(undeclared)==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        Context base=getBaseContext();while(base instanceof ContextWrapper)base=((ContextWrapper)base).getBaseContext();
        Context derived=base.createConfigurationContext(new Configuration());
        check("new-raw-context-has-no-preexisting-package-facade",derived.getClass()==contextType&&cache.get(derived)==null);
        Object wrapped=Proxy.newProxyInstance(contract.getClassLoader(),new Class<?>[]{contract},(proxy,method,args)->{try{return method.invoke(canonical,args);}catch(InvocationTargetException error){throw error.getCause();}});
        PackageManager created=null;global.set(null,wrapped);
        try{
            created=derived.getPackageManager();
            check("new-facade-uses-captured-authority-after-global-wrapper",endpoint.get(created)==canonical);
            check("constructing-facade-does-not-reset-guest-global",global.get(null)==wrapped);
            check("repeated-raw-context-getter-keeps-the-same-owned-facade",derived.getPackageManager()==created&&endpoint.get(created)==canonical);
            check("package-query-keeps-authenticated-own-metadata",getPackageName().equals(created.getPackageInfo(getPackageName(),0).packageName));
            var resources=created.getResourcesForApplication(getPackageName());
            int marker=resources.getIdentifier("package_contract_value","string",getPackageName());
            check("facade-reads-authenticated-own-package-resource-content",marker!=0&&"owned-package-resource-value".equals(resources.getString(marker)));
            endpoint.set(created,wrapped);boolean denied=false;
            try{derived.getPackageManager();}catch(SecurityException error){denied="PACKAGE_CACHED_ENDPOINT_CHANGED".equals(error.getMessage());}
            check("tampered-registered-facade-remains-rejected",denied);
            check("rejection-does-not-replace-or-repair-the-tampered-endpoint",endpoint.get(created)==wrapped&&cache.get(derived)==created);
            check("cached-rejection-does-not-reset-guest-global",global.get(null)==wrapped);
            endpoint.set(created,canonical);
            check("owned-probe-restoration-returns-the-original-facade",derived.getPackageManager()==created);
            Context other=base.createConfigurationContext(new Configuration());
            check("independent-raw-context-starts-without-cached-manager",cache.get(other)==null);
            cache.set(other,created);
            try{
                denied=false;try{other.getPackageManager();}catch(SecurityException error){denied="PACKAGE_FACADE_CONTEXT_MISMATCH".equals(error.getMessage());}
                check("registered-manager-cannot-be-borrowed-by-another-context",denied&&cache.get(other)==created);
            }finally{cache.set(other,null);}
            check("original-facade-remains-bound-to-canonical-authority",endpoint.get(initial)==canonical&&getPackageManager().getPackageInfo(getPackageName(),0).packageName.equals(getPackageName()));
        }finally{if(created!=null)endpoint.set(created,canonical);global.set(null,originalGlobal);}
        check("owned-global-restoration-keeps-original-authority",global.get(null)==originalGlobal);
    }
}
