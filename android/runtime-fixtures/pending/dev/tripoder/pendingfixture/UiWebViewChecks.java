package dev.tripoder.pendingfixture;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Loads inline owned content through the actual platform provider and renderer. */
final class UiWebViewChecks {
    static JSONObject run(Activity activity)throws Exception{
        CompletableFuture<JSONObject> done=new CompletableFuture<>();
        new Handler(Looper.getMainLooper()).post(()->{
            WebView view=null;
            try{
                view=new WebView(activity);WebView owned=view;
                activity.setContentView(view);view.getSettings().setJavaScriptEnabled(true);
                view.setWebViewClient(new WebViewClient(){
                    @Override public void onPageFinished(WebView web,String url){
                        if(!"https://tripoder.invalid/".equals(url)||done.isDone())return;
                        web.evaluateJavascript("JSON.stringify({title:document.title,text:document.getElementById('owned').textContent,storage:(localStorage.setItem('owned','ok'),localStorage.getItem('owned'))})",value->{
                            try{String decoded=new org.json.JSONArray("["+value+"]").getString(0);done.complete(new JSONObject(decoded));}
                            catch(Throwable error){done.completeExceptionally(error);}finally{owned.destroy();}
                        });
                    }
                    @Override public void onReceivedError(WebView web,android.webkit.WebResourceRequest request,android.webkit.WebResourceError error){
                        if(request.isForMainFrame()){done.completeExceptionally(new IllegalStateException("WEBVIEW_PAGE_ERROR:"+error.getErrorCode()));owned.destroy();}
                    }
                });
                view.getSettings().setDomStorageEnabled(true);
                view.loadDataWithBaseURL("https://tripoder.invalid/","<!doctype html><title>Tripoder owned WebView</title><p id='owned'>rendered</p>","text/html","UTF-8",null);
            }catch(Throwable error){if(view!=null)view.destroy();done.completeExceptionally(error);}
        });
        return done.get(30,TimeUnit.SECONDS);
    }
}
