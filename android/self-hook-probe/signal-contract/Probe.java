package dev.tripoder.signalcontract;

/** A real ART signal-chain contract, runnable as an owned THICK guest. */
public final class Probe extends android.app.Activity {
    static { System.loadLibrary("signalcontract"); }
    private static native String check();
    private static native String delivery(String path,boolean trapped);

    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        String result=check();
        android.util.Log.i("TripoderSignalContract","pending-mask-contract="+result);
        try{
            java.io.File own=new java.io.File(getFilesDir(),"owned-signal-input");
            java.nio.file.Files.writeString(own.toPath(),"X");
            android.util.Log.i("TripoderSignalContract","sdk-untrapped-delivery-baseline="+delivery(own.getPath(),false));
            android.util.Log.i("TripoderSignalContract","entering-owned-full-mask-handler");
            String delivered=delivery(own.getPath(),true);
            android.util.Log.i("TripoderSignalContract","user-delivery-contract="+delivered);
            result=result+"\n"+delivered;
        }catch(java.io.IOException error){throw new IllegalStateException("OWNED_SIGNAL_INPUT_FAILED",error);}
        android.widget.TextView view=new android.widget.TextView(this);
        view.setText(result);
        setContentView(view);
    }
}
