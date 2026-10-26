package dev.tripoder.factorycontract;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;

/** Same frozen APK native oracle. The explicit instrumentation profile may
 * permit private SDK metadata inspection; it does not alter delivery values. */
public final class OwnedDeliveryInstrumentation extends Instrumentation {
    @Override public android.app.Activity newActivity(ClassLoader loader,String name,Intent intent)throws InstantiationException,IllegalAccessException,ClassNotFoundException{
        DeliveryObservation.factoryName=name;
        return super.newActivity(loader,name,intent);
    }
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            long deadline=SystemClock.elapsedRealtime()+10000;
            while(!DeliveryObservation.complete&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(10);
            if(!DeliveryObservation.complete)throw new IllegalStateException("OWNED_APPLICATION_NOT_READY");
            Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_INFO).setComponent(new ComponentName(getTargetContext(),"dev.tripoder.factorycontract.Entry")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getTargetContext().startActivity(intent);
            deadline=SystemClock.elapsedRealtime()+10000;
            while(Main.lastResult==null&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(10);
            if(Main.lastResult==null)throw new IllegalStateException("OWNED_ACTIVITY_REPORT_MISSING");
            result.putString("report",Main.lastResult.toString());finish(Main.lastResult.getBoolean("passed")?-1:0,result);
        }catch(Exception error){result.putString("error",android.util.Log.getStackTraceString(error));finish(0,result);}
    }
}
