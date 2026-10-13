package dev.tripoder.hooklab;
import android.app.*;
import android.content.Intent;

public final class GuestComponentFactory extends AppComponentFactory {
    @Override public Activity instantiateActivity(ClassLoader cl, String name, Intent intent) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (name.equals(GameStubActivity.class.getName()) && GameRuntime.current != null) {
            try { return GameRuntime.current.newActivity(); }
            catch (Exception | LinkageError error) { GameRuntime.current.failure(error); throw new IllegalStateException("Game component initialization failed", error); }
        }
        if (name.equals(GuestStubActivity.class.getName())) {
            GuestRuntime runtime = GuestService.peekRuntime();
            if (runtime != null && runtime.componentLoader() != null) return (Activity) runtime.componentLoader().loadClass("dev.tripoder.fixture.FixtureActivity").newInstance();
        }
        return super.instantiateActivity(cl, name, intent);
    }
}
