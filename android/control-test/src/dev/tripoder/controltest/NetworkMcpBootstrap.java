package dev.tripoder.controltest;

import android.content.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Acceptance setup only. Exports endpoint configuration outside evidence; all test calls use LAN MCP. */
public final class NetworkMcpBootstrap extends ControlTestBase {
    @Override void test()throws Exception{
        // An APK update can interrupt the persisted supervisor. An acceptance
        // caller must explicitly request ONE retry; never reopen it implicitly.
        if("true".equals(args.getString("retryEngine")))runOnMainSync(()->
            getTargetContext().startForegroundService(new Intent().setClassName(enginePackage(),"dev.tripoder.runtime.EngineRecoveryService").setAction("retry-once")));
        startThroughWorkbench(false);Object app=getTargetContext().getApplicationContext();Field field=app.getClass().getDeclaredField("agents");field.setAccessible(true);Object agents=field.get(app);
        getTargetContext().getSharedPreferences("mcp-network",Context.MODE_PRIVATE).edit().putBoolean("enabled",true).commit();
        runOnMainSync(()->getTargetContext().startForegroundService(new Intent().setClassName(enginePackage(),"dev.tripoder.hooklab.AgentControlService")));
        Field identityField=agents.getClass().getDeclaredField("networkIdentity");identityField.setAccessible(true);long end=SystemClock.elapsedRealtime()+15000;Object identity;
        while((identity=identityField.get(agents))==null&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);
        if(identity==null){Field message=agents.getClass().getDeclaredField("message");message.setAccessible(true);throw new IllegalStateException("NETWORK_ENDPOINT_NOT_READY:"+message.get(agents));}
        Method connection=identity.getClass().getDeclaredMethod("connection");connection.setAccessible(true);JSONObject config=(JSONObject)connection.invoke(identity);
        java.net.HttpURLConnection probe=(java.net.HttpURLConnection)new java.net.URL(config.getString("url")).openConnection();probe.setConnectTimeout(5000);probe.setReadTimeout(5000);
        try{check("phone-local-http-without-certificate",probe.getResponseCode()==405,"HTTP; GET unsupported");}finally{probe.disconnect();}
        File output=new File(getTargetContext().getExternalFilesDir(null),"mcp-connection-"+run+".json");try(var stream=new FileOutputStream(output)){stream.write(config.toString().getBytes(StandardCharsets.UTF_8));stream.getFD().sync();}
        check("network-endpoint-ready",true,config.getString("url"));report.put("connectionFile",output.getName()).put("setupOnly",true).put("actualMcpVerified",false);
    }
}
