package dev.tripoder.provideridentity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Independent Application-only contract: package failures do not create Android task pins. */
public final class PackageWorldApplication extends MetadataApplication {
    @Override public void onCreate(){super.onCreate();JSONArray checks=new JSONArray();JSONObject report=new JSONObject();
        try{ReflectionAccessChecks.inspect(checks);PackageWorldChecks.inspect(this,checks);PackageInfoChecks.inspect(this,false,checks);ActivityInfoChecks.inspect(this,false,checks);MissingProviderChecks.inspect(this,checks);PackageMetadataChecks.inspect(this,false,checks);
            report.put("battery",BatteryChecks.inspect(this,false,checks));report.put("notifications",NotificationChecks.inspect(this,false,checks));
            report.put("power",PowerChecks.inspect(this,false,checks));
            report.put("shortcutCapability",ShortcutCapabilityChecks.inspect(this,false,checks));
            report.put("networkObservations",NetworkObservationChecks.inspect(this,false,checks));report.put("wifiP2p",WifiP2pChecks.inspect(this,false,checks));report.put("passed",true);}
        catch(Throwable error){try{report.put("passed",false).put("error",android.util.Log.getStackTraceString(error));}catch(Exception failure){throw new IllegalStateException(failure);}}
        try{report.put("checks",checks).put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid()).put("completed",true);
            try(FileOutputStream out=new FileOutputStream(new File(getFilesDir(),"package-world-contract.json"))){out.write(report.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        }catch(Exception error){throw new IllegalStateException("PACKAGE_WORLD_REPORT_FAILED",error);}
    }
}
