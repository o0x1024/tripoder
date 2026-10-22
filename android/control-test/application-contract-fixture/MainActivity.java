package dev.tripoder.applicationcontract;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);ProxyApplication.activity=this;
        sendBroadcast(new Intent("owned.contract.UPDATE").setPackage(getPackageName()));
        sendBroadcast(new Intent("owned.contract.UPDATE").setPackage(getPackageName()).addCategory("owned.contract.ABSENT"));
        sendBroadcast(new Intent("owned.contract.UPDATE"));
        new Handler(Looper.getMainLooper()).postDelayed(()->{TextView text=new TextView(this);text.setText(receipt());setContentView(text);},1200);
    }
    public static String receipt(){
        MainActivity value=ProxyApplication.activity;
        return "application="+(value.getApplication()==ProxyApplication.real)+",context="+(value.getApplicationContext()==ProxyApplication.real)
            +",created="+ProxyApplication.created+",received="+ProxyApplication.received+",unexpected="+ProxyApplication.unexpected;
    }
}
