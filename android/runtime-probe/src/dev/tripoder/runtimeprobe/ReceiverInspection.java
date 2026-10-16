package dev.tripoder.runtimeprobe;

import android.content.*;
import android.os.Bundle;
import dev.tripoder.runtime.RuntimeEngine;
import dev.tripoder.runtime.RuntimeFactory;

/** Runs only in the acceptance APK, in the same Android process as the physical receiver. */
public class ReceiverInspection extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        Bundle out=new Bundle();out.putInt("pid",android.os.Process.myPid());out.putBoolean("engine",RuntimeEngine.current!=null);
        if(RuntimeEngine.current!=null)try{
            var engine=RuntimeEngine.current;out.putString("generation",engine.generation);out.putString("package",engine.packageName);
            Class<?> factory=engine.loader.loadClass("dev.tripoder.p2.Factory");out.putInt("applications",factory.getField("applications").getInt(null));out.putInt("receivers",factory.getField("receivers").getInt(null));
        }catch(Exception error){throw new IllegalStateException("RECEIVER_INSPECTION_FAILED",error);}
        if(intent.hasExtra("probeTicket")){
            String stub="dev.tripoder.runtime.RuntimeReceiverSlots$"+getClass().getSimpleName();
            Intent candidate=new Intent().setPackage(context.getPackageName()).setComponent(new ComponentName(context,stub)).putExtra("tripoder.delivery",intent.getStringExtra("probeTicket"));
            if(intent.getBooleanExtra("tamper",false))candidate.putExtra("extra",true);
            try{new RuntimeFactory().instantiateReceiver(context.getClassLoader(),stub,candidate);out.putBoolean("rejected",false);}
            catch(Exception expected){out.putBoolean("rejected",true);StringBuilder chain=new StringBuilder();for(Throwable e=expected;e!=null;e=e.getCause())chain.append(e.getMessage()).append('\n');out.putString("reason",chain.toString());}
        }
        setResult(72,"owned-inspection",out);
    }
    public static final class R0 extends ReceiverInspection{} public static final class R1 extends ReceiverInspection{}
    public static final class R2 extends ReceiverInspection{} public static final class R3 extends ReceiverInspection{}
    public static final class R4 extends ReceiverInspection{} public static final class R5 extends ReceiverInspection{}
    public static final class R6 extends ReceiverInspection{} public static final class R7 extends ReceiverInspection{}
}
