package dev.tripoder.hooklab;
import android.app.*;
import android.os.*;

public final class TripoderApplication extends Application implements Application.ActivityLifecycleCallbacks {
    @Override public void onCreate() { super.onCreate(); registerActivityLifecycleCallbacks(this); }
    @Override public void onActivityPreCreated(Activity activity, Bundle state) {
        if (GameRuntime.owns(activity)) {
            try { GameRuntime.current.attach(activity); }
            catch (Exception error) { GameRuntime.current.failure(error); throw new IllegalStateException("Game context initialization failed",error); }
            return;
        }
        if (!AgentBridge.ownsActivity(activity)) return;
        try { GuestService.peekRuntime().attachComponent(activity); }
        catch (Exception error) { throw new IllegalStateException("Guest component initialization failed", error); }
    }
    @Override public void onActivityPostCreated(Activity activity, Bundle state) {
        if (GameRuntime.owns(activity)) GameRuntime.current.event("activity-created");
        if (AgentBridge.ownsActivity(activity)) ComponentController.install(activity, GuestService.peekRuntime());
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityResumed(Activity activity) {
        if (GameRuntime.owns(activity)) {
            GameRuntime runtime=GameRuntime.current;runtime.event("activity-resumed");
            new Handler(Looper.getMainLooper()).postDelayed(()->{if(GameRuntime.current==runtime)runtime.snapshot();},5000);
        }
    }
    @Override public void onActivityPaused(Activity activity) { if (GameRuntime.owns(activity)) GameRuntime.current.event("activity-paused"); if (AgentBridge.ownsActivity(activity)) ComponentController.cancel(activity); }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) { if (AgentBridge.ownsActivity(activity)) ComponentController.cancel(activity); }
}
