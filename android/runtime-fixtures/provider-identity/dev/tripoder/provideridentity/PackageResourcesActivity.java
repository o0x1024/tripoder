package dev.tripoder.provideridentity;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

/** Same unmodified fixture runs on Android and in Tripoder. No external app data is queried. */
public final class PackageResourcesActivity extends Activity {
    private final JSONArray checks = new JSONArray();
    private JSONObject systemServices;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView text = new TextView(this);
        text.setText("Tripoder · package metadata and XML");
        setContentView(text);
        boolean baseline = "true".equals(getIntent().getStringExtra("baseline"));
        new Thread(() -> {
            JSONObject result = new JSONObject();
            try {
                inspect(baseline);
                result.put("completed", true).put("passed", true);
            } catch (Throwable error) {
                try { result.put("completed", false).put("passed", false).put("error", android.util.Log.getStackTraceString(error)); }
                catch (Exception serialization) { throw new IllegalStateException(serialization); }
            }
            try {
                result.put("checks", checks).put("pid", android.os.Process.myPid()).put("uid", android.os.Process.myUid()).put("systemServices",systemServices);
                String delivered;
                if (baseline) {
                    String run = getIntent().getStringExtra("runId");
                    if (run == null || !run.matches("[a-zA-Z0-9-]{1,80}")) throw new IllegalArgumentException("BASELINE_RUN_REQUIRED");
                    result.put("deviceBuild", android.os.Build.FINGERPRINT);
                    try (var out = new FileOutputStream(new File(getExternalFilesDir(null), "result-" + run + ".json"))) {
                        out.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
                        out.getFD().sync();
                    }
                    delivered=result.toString();
                }else{
                    // A test report is larger than a business Activity result.
                    // Keep the product's result bounds; return a small receipt.
                    String nonce=getIntent().getStringExtra("reportNonce");
                    if(nonce==null||!java.util.UUID.fromString(nonce).toString().equals(nonce))throw new IllegalArgumentException("REPORT_NONCE_REQUIRED");
                    byte[] bytes=result.toString().getBytes(StandardCharsets.UTF_8);
                    if(bytes.length>512*1024)throw new IllegalStateException("REPORT_TOO_LARGE");
                    File target=new File(getFilesDir(),"resources-"+nonce+".json"),stage=new File(target+".pending");
                    if(target.exists()||!stage.createNewFile())throw new IllegalStateException("REPORT_ALREADY_PUBLISHED");
                    try(var out=new FileOutputStream(stage)){out.write(bytes);out.getFD().sync();}
                    if(!stage.renameTo(target))throw new IllegalStateException("REPORT_PUBLICATION_FAILED");
                    delivered=new JSONObject().put("reportNonce",nonce).put("reportSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))).put("reportBytes",bytes.length).put("pid",android.os.Process.myPid())
                        .put("reportDirectory",getFilesDir().toString()).put("reportDirectoryCanonical",getFilesDir().getCanonicalPath()).toString();
                }
                final String receipt=delivered;
                runOnUiThread(() -> {
                    text.setText(result.toString());
                    setResult(RESULT_OK, new Intent().putExtra("result", receipt));
                    if (!baseline) finish();
                });
            } catch (Exception error) { throw new IllegalStateException("PACKAGE_RESOURCES_RESULT_NOT_SAVED", error); }
        }, "owned-package-resources").start();
    }

    private void inspect(boolean baseline) throws Exception {
        PackageInfoChecks.inspect(this,baseline,checks);
        PackageContextChecks.inspect(this,baseline,checks);
        ActivityInfoChecks.inspect(this,baseline,checks);
        MissingProviderChecks.inspect(this,checks);
        Bundle delegated=getContentResolver().call(android.net.Uri.parse("content://dev.tripoder.identity.first"),"identity",null,null);
        check("provider-private-delegate-attaches-once-with-logical-context",delegated.getInt("delegateCreates")==1&&getPackageName().equals(delegated.getString("delegatePackage")));
        check("provider-platform-appops-not-granted-to-guest",delegated.getBoolean("delegateAppOpsAvailableToGuest")==baseline);
        if(!baseline){
            boolean rejected=false;try{new PrivateProviderDelegate().attachInfo(this,getPackageManager().getProviderInfo(new ComponentName(this,IdentityProvider.class),0));}catch(UnsupportedOperationException expected){rejected=expected.getMessage().contains("SYSTEM_SERVICE_UNSUPPORTED:appops");}
            check("provider-private-delegate-outside-publication-has-no-platform-grant",rejected);
        }
        JSONObject receiverPermissions=ReceiverPermissionChecks.inspect(this,baseline,checks);
        JSONObject network=NetworkChecks.inspect(this,baseline,checks);
        JSONObject telephony=TelephonyChecks.inspect(this,baseline,checks);
        JSONObject audio=AudioChecks.inspect(this,baseline,checks);
        JSONObject battery=BatteryChecks.inspect(this,baseline,checks);
        JSONObject power=PowerChecks.inspect(this,baseline,checks);
        JSONObject shortcutCapability=ShortcutCapabilityChecks.inspect(this,baseline,checks);
        JSONObject networkObservations=NetworkObservationChecks.inspect(this,baseline,checks);
        JSONObject wifiP2p=WifiP2pChecks.inspect(this,baseline,checks);
        JSONObject notification=NotificationChecks.inspect(this,baseline,checks);
        FailureDispatchChecks.inspect(checks);
        ReflectionAccessChecks.inspect(checks);
        JSONObject systemBroadcast=SystemBroadcastChecks.inspect(this,baseline,checks);
        systemServices=SystemServiceChecks.inspect(this,baseline,checks);
        systemServices.put("receiverPermissions",receiverPermissions).put("network",network).put("telephony",telephony).put("audio",audio)
            .put("battery",battery).put("power",power).put("shortcutCapability",shortcutCapability).put("networkObservations",networkObservations)
            .put("wifiP2p",wifiP2p).put("notification",notification).put("systemBroadcast",systemBroadcast);
        PackageMetadataChecks.inspect(this,baseline,checks);
    }

    private void check(String name, boolean passed) throws Exception {
        checks.put(new JSONObject().put("name", name).put("passed", passed));
        if (!passed) throw new AssertionError(name);
    }
}
