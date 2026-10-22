#!/usr/bin/env python3
"""Exercise the production Java HTTPS/MCP/schema handler with a host-only control adapter.
Not Android Binder, native Hook or physical-device acceptance.
"""
import os,subprocess,tempfile,time,json
from pathlib import Path
repo=Path(__file__).resolve().parents[3]
java=Path('/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin')
jar=Path(tempfile.gettempdir())/'tripoder-source-test-deps/json-20250517.jar'
if not jar.exists():
    import urllib.request
    jar.parent.mkdir(exist_ok=True)
    urllib.request.urlretrieve('https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar',jar)
with tempfile.TemporaryDirectory(prefix='tripoder-mcp-test-') as folder:
    root=Path(folder);assets=root/'assets';assets.mkdir();classes=root/'classes';classes.mkdir()
    subprocess.run(['node',str(repo/'agent/export-contract.mjs'),str(assets/'mcp-contract.json')],check=True)
    sources={
    'android/content/Context.java':'''package android.content; public class Context {private final java.nio.file.Path root;public Context(java.nio.file.Path root){this.root=root;} public android.content.res.AssetManager getAssets(){return new android.content.res.AssetManager(root);}}''',
    'android/content/res/AssetManager.java':'''package android.content.res; public class AssetManager {private final java.nio.file.Path root;public AssetManager(java.nio.file.Path root){this.root=root;}public java.io.InputStream open(String name)throws java.io.IOException{return java.nio.file.Files.newInputStream(root.resolve(name));}}''',
    'dev/tripoder/hooklab/WorkbenchAgentControl.java':'''package dev.tripoder.hooklab;import org.json.*;final class WorkbenchAgentControl {int calls;JSONObject wire(JSONObject request)throws Exception{calls++;String method=request.getString("method");JSONObject value=request.getJSONObject("params");JSONObject result=new JSONObject().put("method",method).put("params",value).put("dispatchCount",calls);if(value.has("target"))result.put("target",value.get("target"));if(method.equals("import.begin"))result.put("offsets",new JSONArray().put(0));return new JSONObject().put("protocol",2).put("ok",true).put("requestId",request.getString("requestId")).put("result",result);}}''',
    'dev/tripoder/hooklab/AgentNetworkIdentity.java':'''package dev.tripoder.hooklab;final class AgentNetworkIdentity {static final int PORT=29731;final String ip="127.0.0.1",token;AgentNetworkIdentity(String token){this.token=token;}boolean credentialCurrent(){return true;}}''',
    'dev/tripoder/hooklab/McpHost.java':'''package dev.tripoder.hooklab;import java.nio.file.*;import java.security.*;import java.util.*;import javax.net.ssl.*;import org.json.*;public class McpHost {public static void main(String[] args)throws Exception{Path root=Path.of(args[0]);KeyPairGenerator gen=KeyPairGenerator.getInstance("EC");gen.initialize(256);KeyPair pair=gen.generateKeyPair();var cert=AgentCertificate.issue(pair.getPublic(),pair.getPrivate(),"127.0.0.1");KeyStore store=KeyStore.getInstance("PKCS12");store.load(null);store.setKeyEntry("server",pair.getPrivate(),new char[0],new java.security.cert.Certificate[]{cert});KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(store,new char[0]);SSLContext tls=SSLContext.getInstance("TLSv1.3");tls.init(km.getKeyManagers(),null,new SecureRandom());SSLServerSocket server=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket(29731,4,java.net.InetAddress.getByName("127.0.0.1"));byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);AgentNetworkIdentity identity=new AgentNetworkIdentity(Base64.getUrlEncoder().withoutPadding().encodeToString(secret));var mcp=new AgentMcp(new android.content.Context(root.resolve("assets")),new WorkbenchAgentControl());String pem="-----BEGIN CERTIFICATE-----\\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(cert.getEncoded())+"\\n-----END CERTIFICATE-----\\n";Path config=root.resolve("connection.json");Files.writeString(config,new JSONObject().put("url","https://127.0.0.1:29731/mcp").put("token",identity.token).put("certificatePem",pem).toString());Files.setPosixFilePermissions(config,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));System.out.println("READY");while(true){SSLSocket socket=(SSLSocket)server.accept();new Thread(()->{try(socket){AgentHttp.serve(socket,identity,mcp);}catch(Exception ignored){}}).start();}}}'''
    }
    for name,body in sources.items():
        target=root/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(body)
    package=repo/'android/workbench/src/dev/tripoder/hooklab'
    files=[root/name for name in sources]+[package/name for name in ['AgentSchema.java','AgentMcp.java','AgentHttp.java','AgentCertificate.java']]
    subprocess.run([str(java/'javac'),'--release','17','-cp',str(jar),'-d',str(classes),*map(str,files)],check=True)
    server=subprocess.Popen([str(java/'java'),'-cp',str(classes)+os.pathsep+str(jar),'dev.tripoder.hooklab.McpHost',str(root)],stdout=subprocess.PIPE,text=True)
    try:
        if server.stdout.readline().strip()!='READY':raise RuntimeError('JAVA_MCP_SERVER_START_FAILED')
        subprocess.run(['node','--test','agent/agent.test.mjs','agent/audit.test.mjs'],cwd=repo,env={**os.environ,'TRIPODER_MCP_TEST_CONNECTION':str(root/'connection.json')},check=True)
    finally:
        server.terminate();server.wait(timeout=10)
