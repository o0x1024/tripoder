package dev.tripoder.ingressguest;
import android.app.Activity;
import android.content.Intent;
import android.os.*;

public final class Child extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);try{
        Bundle nested=getIntent().getBundleExtra("nested");
        if(!"ingress".equals(nested.getBundle("child").getSerializable("model",Main.Model.class).value))throw new IllegalStateException("nested-intent");
        Main.passed.add("payload");
        if(state==null){new Handler(Looper.getMainLooper()).postDelayed(this::recreate,100);return;}
        if(state.getBundle("saved").getSerializable("model",Main.Model.class)==null)throw new IllegalStateException("saved-instance-state");Main.passed.add("savedState");
        Bundle result=new Bundle();result.putSerializable("model",new Main.Model());setResult(RESULT_OK,new Intent().putExtra("result",result));finish();
    }catch(Throwable error){Main.failure=error;finish();}}
    @Override public void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);Bundle child=new Bundle();child.putSerializable("model",new Main.Model());state.putBundle("saved",child);}
}
