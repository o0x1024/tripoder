package dev.tripoder.controltest;

import android.content.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Acceptance setup only. Exports a temporary credential outside evidence; all test calls use LAN MCP. */
public final class NetworkMcpBootstrap extends ControlTestBase {
    @Override void test()throws Exception{
        startThroughWorkbench(false);Object app=getTargetContext().getApplicationContext();Field field=app.getClass().getDeclaredField("agents");field.setAccessible(true);Object agents=field.get(app);
        getTargetContext().getSharedPreferences("mcp-network",Context.MODE_PRIVATE).edit().putBoolean("enabled",true).commit();
        runOnMainSync(()->getTargetContext().startForegroundService(new Intent().setClassName(enginePackage(),"dev.tripoder.hooklab.AgentControlService")));
        Field identityField=agents.getClass().getDeclaredField("networkIdentity");identityField.setAccessible(true);long end=SystemClock.elapsedRealtime()+15000;Object identity;
        while((identity=identityField.get(agents))==null&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);
        if(identity==null){Field message=agents.getClass().getDeclaredField("message");message.setAccessible(true);throw new IllegalStateException("NETWORK_ENDPOINT_NOT_READY:"+message.get(agents));}
        Method connection=identity.getClass().getDeclaredMethod("connection");connection.setAccessible(true);JSONObject config=(JSONObject)connection.invoke(identity);
        java.security.cert.X509Certificate certificate=(java.security.cert.X509Certificate)java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(config.getString("certificatePem").getBytes(StandardCharsets.US_ASCII)));
        java.security.KeyStore trust=java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());trust.load(null);trust.setCertificateEntry("phone",certificate);javax.net.ssl.TrustManagerFactory managers=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());managers.init(trust);
        javax.net.ssl.SSLContext tls=javax.net.ssl.SSLContext.getInstance("TLSv1.3");tls.init(null,managers.getTrustManagers(),null);
        javax.net.ssl.HttpsURLConnection probe=(javax.net.ssl.HttpsURLConnection)new java.net.URL(config.getString("url")).openConnection();probe.setSSLSocketFactory(tls.getSocketFactory());probe.setConnectTimeout(5000);probe.setReadTimeout(5000);
        try{check("phone-local-tls-and-unauthenticated-rejection",probe.getResponseCode()==401,"trusted certificate; unauthorized request refused");}finally{probe.disconnect();}
        File output=new File(getTargetContext().getExternalFilesDir(null),"mcp-connection-"+run+".json");try(var stream=new FileOutputStream(output)){stream.write(config.toString().getBytes(StandardCharsets.UTF_8));stream.getFD().sync();}
        check("network-endpoint-ready",true,config.getString("url"));report.put("credentialFile",output.getName()).put("setupOnly",true).put("actualMcpVerified",false);
    }
}
