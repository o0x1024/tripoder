package dev.tripoder.closurefixture;

/** Initially disabled: only a real persisted own-package state change may admit it. */
public final class ToggleActivity extends android.app.Activity {
    static final java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
    static final java.util.concurrent.CountDownLatch aliasEntered=new java.util.concurrent.CountDownLatch(1);
    @Override public void onCreate(android.os.Bundle state){super.onCreate(state);if(getIntent().getComponent().getClassName().endsWith(".DisabledAlias"))aliasEntered.countDown();else entered.countDown();finish();}
}
