package dev.tripoder.selfhookprobe;

import android.app.Application;

/** Separate declaring class makes private reflection access checks observable. */
public final class AppIdentityFixture {
    private Application current;
    public Application other;
    public AppIdentityFixture(Application initial) { current=initial;other=initial; }
    public Application physical() { return current; }
    public void physical(Application replacement) { current=replacement; }
}
