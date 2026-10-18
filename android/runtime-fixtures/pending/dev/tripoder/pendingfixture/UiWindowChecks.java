package dev.tripoder.pendingfixture;

import android.app.Activity;
import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Public UI API regression: a real Dialog click must reach its listener. */
final class UiWindowChecks {
    static JSONObject run(Activity activity)throws Exception{
        CompletableFuture<JSONObject> done=new CompletableFuture<>();
        Handler main=new Handler(Looper.getMainLooper());
        main.post(()->{
            Dialog dialog=new Dialog(activity);
            Button button=new Button(dialog.getContext());button.setText("Owned dialog click test");
            button.setSoundEffectsEnabled(true);dialog.setContentView(button);
            button.setOnClickListener(v->{
                try{
                    View owner=activity.getWindow().getDecorView(),root=dialog.getWindow().getDecorView();
                    WindowManager.LayoutParams a=(WindowManager.LayoutParams)owner.getLayoutParams();
                    WindowManager.LayoutParams b=(WindowManager.LayoutParams)root.getLayoutParams();
                    done.complete(new JSONObject().put("listenerReached",true)
                        .put("bothAttached",owner.isAttachedToWindow()&&root.isAttachedToWindow())
                        .put("distinctDecor",owner!=root).put("distinctWindowTokens",owner.getWindowToken()!=root.getWindowToken())
                        .put("sameNonNullApplicationToken",a.token!=null&&a.token==b.token));
                }catch(Throwable error){done.completeExceptionally(error);}
            });
            try{
                dialog.show();
                // View.post waits until attachment; performClick traverses the
                // actual ViewRootImpl sound effect Hook before the listener.
                button.post(()->{try{if(!button.performClick())throw new AssertionError("DIALOG_CLICK_NOT_HANDLED");}
                    catch(Throwable error){done.completeExceptionally(error);}finally{dialog.dismiss();}});
            }catch(Throwable error){dialog.dismiss();done.completeExceptionally(error);}
        });
        return done.get(10,TimeUnit.SECONDS);
    }
}
