package dev.tripoder.selfhookprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.tripoder.selfhook.FixedManagerInventory;
import dev.tripoder.selfhook.FrameworkAccess;
import dev.tripoder.selfhook.ManagedHook;
import java.io.File;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owned fixed-device inventory. Does not query any live system service. */
public final class ManagerInventoryProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);TextView view=new TextView(this);setContentView(view);
        JSONObject report=new JSONObject();File directory=null;
        try {
            String run=UUID.fromString(getIntent().getStringExtra("runId")).toString();
            directory=new File(getExternalFilesDir(null),run);ProbeFiles.require(directory.mkdir(),"FRESH_MANAGER_INVENTORY_RUN");
            report.put("schema",1).put("phase","H4-fixed-manager-inventory").put("runId",run);ProbeEnvironment.load(this,report);
            FrameworkAccess.prepare();JSONArray families=new JSONArray();int total=0;
            for(FixedManagerInventory.Family family:FixedManagerInventory.inspect()) {
                total+=family.instanceMethods.size();
                families.put(new JSONObject().put("owner",family.owner.getName()).put("instanceMethods",family.instanceMethods.size())
                    .put("constructors",family.constructors).put("staticMethods",family.staticMethods).put("abstractMethods",family.abstractMethods)
                    .put("nativeMethods",family.nativeMethods).put("skipped",family.skipped).put("sha256",family.sha256)
                    .put("declarations",new JSONArray(family.declarations)));
            }
            JSONObject policy=new JSONObject(ManagedHook.processPolicy());
            ProbeFiles.require(!policy.getBoolean("owned"),"INVENTORY_MUST_NOT_CLAIM_EXECUTION_POLICY");
            report.put("families",families).put("totalInstanceMethods",total).put("processPolicy",policy)
                .put("frameworkAccess",new JSONObject(FrameworkAccess.statusNative())).put("hookInstalled",false).put("productAccepted",false);
            File maps=new File(directory,"maps.txt");ProbeFiles.write(maps,ProbeFiles.read("/proc/self/maps"));report.put("mapsSha256",ProbeFiles.sha(maps)).put("passed",true);
        }catch(Throwable error){try{report.put("passed",false).put("error",error.toString());}catch(Exception ignored){}}
        try {
            if(directory!=null)ProbeFiles.publishResult(directory,report.toString(2)+"\n");
            view.setText("Fixed manager inventory "+report.optBoolean("passed")+" "+report.optString("error"));
        }catch(Exception error){view.setText("Inventory evidence failed: "+error);}
    }
}
