package dev.tripoder.closurefixture;

import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.*;
import android.content.res.loader.*;
import android.os.ParcelFileDescriptor;
import android.webkit.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Local HTML/JS only, with a real held ContextImpl PM before and after provider initialization. */
final class WebViewResourceChecks {
    static void run(Activity activity,ResourceOriginChecks.Check check)throws Exception{
        Context context=activity.getApplication();Class<?> exact=Class.forName("android.app.ContextImpl",false,null);
        Set<Context> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        while(context.getClass()!=exact&&context instanceof ContextWrapper&&seen.size()<16&&seen.add(context))context=((ContextWrapper)context).getBaseContext();
        if(context.getClass()!=exact)throw new AssertionError("OWNED_WEBVIEW_EXACT_CONTEXT_REQUIRED");
        String pkg=activity.getPackageName();PackageManager held=context.getPackageManager();var info=held.getApplicationInfo(pkg,0);Resources before=held.getResourcesForApplication(pkg);
        int drawable=before.getIdentifier("owned","drawable",pkg);CharSequence label=held.getApplicationLabel(info);
        try(var xml=held.getXml(pkg,drawable,info)){check.test("held-raw-manager-loads-owned-label-and-xml-before-webview",label!=null&&xml!=null);}
        AtomicReference<WebView> created=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();AtomicBoolean javascript=new AtomicBoolean();CountDownLatch constructor=new CountDownLatch(1),page=new CountDownLatch(1);
        activity.runOnUiThread(()->{
            try{
                WebView web=new WebView(activity);created.set(web);web.getSettings().setJavaScriptEnabled(true);
                web.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView view,String url){view.evaluateJavascript("document.body.textContent === 'OWNED_WEBVIEW_READY'",value->{javascript.set("true".equals(value));page.countDown();});}});
                activity.addContentView(web,new android.view.ViewGroup.LayoutParams(64,64));
                web.loadDataWithBaseURL("https://owned.invalid/","<html><body>OWNED_WEBVIEW_READY</body></html>","text/html","UTF-8",null);
            }catch(Throwable failure){error.set(failure);}finally{constructor.countDown();}
        });
        try{
            if(!constructor.await(12,TimeUnit.SECONDS))throw new AssertionError("OWNED_WEBVIEW_CONSTRUCTOR_TIMEOUT");
            if(error.get()!=null)throw new AssertionError("OWNED_WEBVIEW_CONSTRUCTOR_FAILED",error.get());
            check.test("real-platform-webview-constructor-completes",created.get()!=null);
            ResourceRegistrationChecks.run(activity,check);
            try(ContentProviderClient settings=activity.getContentResolver().acquireContentProviderClient("settings")){
                ClassLoader runtime=settings.getLocalContentProvider().getClass().getClassLoader();Class<?> engine=Class.forName("dev.tripoder.runtime.RuntimeEngine",false,runtime);
                var current=engine.getDeclaredField("current");current.setAccessible(true);Object worker=current.get(null);var field=engine.getDeclaredField("webView");field.setAccessible(true);Object dependency=field.get(worker);
                var diagnostic=dependency.getClass().getDeclaredMethod("diagnosticAssetRelations",Resources.class);diagnostic.setAccessible(true);
                android.util.Log.i("TripoderClosureFixture","postWebViewAssetRelations="+new org.json.JSONObject((java.util.Map<?,?>)diagnostic.invoke(dependency,before)));
            }
            try(var xml=held.getXml(pkg,drawable,info)){check.test("held-raw-manager-keeps-label-and-owned-xml-after-real-webview-registration",label.equals(held.getApplicationLabel(info))&&xml!=null&&held.getResourcesForApplication(pkg)==before&&context.getPackageManager()==held);}
            File foreign=new File(activity.getFilesDir(),"owned-webview-foreign-"+UUID.randomUUID()+".apk");
            try(var input=activity.getAssets().open("foreign-resources.apk")){Files.copy(input,foreign.toPath());}if(!foreign.setReadOnly())throw new IOException("OWNED_RESOURCE_READONLY_REQUIRED");
            try(var fd=ParcelFileDescriptor.open(foreign,ParcelFileDescriptor.MODE_READ_ONLY);var provider=ResourcesProvider.loadFromApk(fd)){
                ResourcesLoader loader=new ResourcesLoader();loader.addProvider(provider);before.addLoaders(loader);
                try{
                    boolean denied=false;try{held.getResourcesForApplication(pkg);}catch(SecurityException expected){denied="PACKAGE_CONTEXT_RESOURCE_ORIGIN_DENIED".equals(expected.getMessage());}
                    check.test("webview-resource-registration-never-admits-foreign-same-package-loader",denied);
                }finally{before.removeLoaders(loader);loader.removeProvider(provider);}
            }
            check.test("removing-foreign-loader-restores-exact-webview-extended-owned-view",held.getResourcesForApplication(pkg)==before);
            check.test("real-webview-renders-owned-local-html-and-executes-javascript",page.await(12,TimeUnit.SECONDS)&&javascript.get());
        }finally{
            WebView web=created.get();if(web!=null){CountDownLatch destroyed=new CountDownLatch(1);activity.runOnUiThread(()->{try{((android.view.ViewGroup)web.getParent()).removeView(web);web.destroy();}finally{destroyed.countDown();}});if(!destroyed.await(5,TimeUnit.SECONDS))throw new AssertionError("OWNED_WEBVIEW_DESTROY_TIMEOUT");}
        }
    }
    private WebViewResourceChecks(){}
}
