package dev.tripoder.signalcontract;

/** A real ART signal-chain contract, runnable as an owned THICK guest. */
public final class Probe extends android.app.Activity {
    static { System.loadLibrary("signalcontract"); }
    private static native String check();

    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        String result=check();
        android.widget.TextView view=new android.widget.TextView(this);
        view.setText(result);
        setContentView(view);
    }
}
