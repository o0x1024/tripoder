package dev.tripoder.runtime;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import java.util.Set;

/** Foreground ingress (not exported); no caller-selected stub, physical path or worker Binder. The one-use capability authorizes the launch. */
public final class RuntimeGuestGateway extends Activity {
    private boolean dispatched;private String operation,token;private RuntimeBroker broker;
    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        try{
            Intent request=getIntent();Bundle extras=request.getExtras();
            if(!isTaskRoot())throw new SecurityException("LAUNCH_GATEWAY_OWN_TASK_REQUIRED");
            if(saved!=null)throw new SecurityException("LAUNCH_GATEWAY_RESTORE_REQUIRES_QUERY");
            if(request.getData()!=null||request.getClipData()!=null||request.getSelector()!=null||request.getAction()!=null||request.getCategories()!=null||extras==null||!extras.keySet().equals(Set.of("operation","capability"))||!(extras.get("operation") instanceof String)||!(extras.get("capability") instanceof String))throw new SecurityException("LAUNCH_GATEWAY_INPUT_REJECTED");
            operation=extras.getString("operation");token=extras.getString("capability");RuntimeFiles.id(operation);
            broker=RuntimeAuthorityOwner.get(this);TextView view=new TextView(this);view.setText("正在启动空间中的应用…");view.setTextSize(20);view.setGravity(android.view.Gravity.CENTER);setContentView(view);
        }catch(Exception error){reject(error);}
    }
    @Override protected void onPostResume(){super.onPostResume();if(dispatched||isFinishing())return;dispatched=true;
        getWindow().getDecorView().post(()->{if(isFinishing()||isDestroyed())return;boolean consumed=false;try{
            Intent physical;int uid=android.os.Process.myUid();
            synchronized(broker){var ledger=new ControllerLaunchLedger(broker);var entry=ledger.get(operation);if(entry==null)throw new SecurityException("LAUNCH_CAPABILITY_REJECTED");RuntimeControllerService.requireLease(entry.lease,uid);physical=ledger.consume(operation,token,uid,ControllerTrust.PACKAGE,getTaskId());consumed=true;}
            startActivity(physical);finish();
        }catch(Exception error){if(consumed)synchronized(broker){if(broker.contract.query(operation).state==ExecutionContract.OperationState.RUNNING)broker.contract.markUnknown(operation,"GATEWAY_START_UNCONFIRMED");}reject(error);}});
    }
    private void reject(Exception error){android.util.Log.e("TripoderGateway",error.getClass().getSimpleName()+":"+error.getMessage());TextView view=new TextView(this);view.setText("应用未确认启动\n"+error.getMessage()+"\n请返回工作台查询原操作。");view.setTextSize(18);view.setPadding(36,120,36,36);setContentView(view);dispatched=true;}
}
