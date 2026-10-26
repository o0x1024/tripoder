#!/usr/bin/env python3
"""Exercise the production Java HTTP/MCP/schema handler with a host-only control adapter.
Not Android Binder, native Hook or physical-device acceptance.
"""
import os,subprocess,tempfile,time,json,sys
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
    'dev/tripoder/hooklab/WorkbenchAgentControl.java':'''package dev.tripoder.hooklab;import org.json.*;final class WorkbenchAgentControl {int calls;private FileExports exports;private FileExports exports()throws Exception{if(exports==null)exports=new FileExports(java.nio.file.Files.createTempDirectory("mcp-exports-"),s->java.nio.file.Path.of(s.getString("path")));return exports;}volatile String blockMethod="";final java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);JSONObject wire(JSONObject request)throws Exception{calls++;if(request.getString("method").equals(blockMethod)){started.countDown();release.await();}String method=request.getString("method");if(method.startsWith("file.")){FileExports e=exports();JSONObject p=request.getJSONObject("params"),v;switch(method){case "file.exportBegin":v=e.begin(request.getString("requestId"),p.getJSONObject("source"),p.getBoolean("followSymlinks"));break;case "file.exportStatus":v=e.status(p.getString("exportId"));break;case "file.exportManifest":v=e.manifest(p.getString("exportId"),p.getInt("cursor"),p.getInt("limit"));break;case "file.exportRead":v=e.read(p.getString("exportId"),p.getString("entryId"),p.getLong("offset"),p.getInt("length"));break;default:throw new IllegalArgumentException("TEST_FILE_METHOD");}return new JSONObject().put("ok",true).put("result",v);}JSONObject value=request.getJSONObject("params");JSONObject result=new JSONObject().put("method",method).put("params",value).put("dispatchCount",calls);if(value.has("target"))result.put("target",value.get("target"));if(method.equals("import.begin"))result.put("offsets",new JSONArray().put(0));return new JSONObject().put("protocol",2).put("ok",true).put("requestId",request.getString("requestId")).put("result",result);}}''',
    'dev/tripoder/hooklab/AgentNetworkIdentity.java':'''package dev.tripoder.hooklab;final class AgentNetworkIdentity {static int PORT;final String ip="127.0.0.1";}''',
    'dev/tripoder/hooklab/McpHost.java':'''package dev.tripoder.hooklab;import java.nio.file.*;import java.net.*;import org.json.*;public class McpHost {public static void main(String[] args)throws Exception{Path root=Path.of(args[0]);ServerSocket server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));AgentNetworkIdentity.PORT=server.getLocalPort();AgentNetworkIdentity identity=new AgentNetworkIdentity();var mcp=new AgentMcp(new android.content.Context(root.resolve("assets")),new WorkbenchAgentControl());Path config=root.resolve("connection.json");Files.writeString(config,new JSONObject().put("url","http://127.0.0.1:"+AgentNetworkIdentity.PORT+"/mcp").toString());Files.setPosixFilePermissions(config,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));System.out.println("READY");while(true){Socket socket=server.accept();new Thread(()->{try(socket){AgentHttp.serve(socket,identity,mcp);}catch(Exception ignored){}}).start();}}}'''
    }
    for name,body in sources.items():
        target=root/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(body)
    package=repo/'android/workbench/src/dev/tripoder/hooklab'
    files=[repo/'android/runtime/src/dev/tripoder/runtime/RuntimeRecoveryIdentity.java',repo/'android/workbench/test/RuntimeRecoveryIdentityTest.java',repo/'android/runtime/src/dev/tripoder/runtime/InstalledManifestAudit.java',repo/'android/workbench/test/InstalledManifestAuditTest.java',repo/'android/workbench/test/AgentMcpConcurrencyTest.java',repo/'android/workbench/test/FileExportsTest.java']+[root/name for name in sources]+[package/name for name in ['AgentSchema.java','AgentRecovery.java','AgentMcp.java','AgentHttp.java','FileExports.java']]
    subprocess.run([str(java/'javac'),'--release','17','-cp',str(jar),'-d',str(classes),*map(str,files)],check=True)
    subprocess.run([str(java/'java'),'-cp',str(classes)+os.pathsep+str(jar),'dev.tripoder.hooklab.RuntimeRecoveryIdentityTest'],check=True)
    subprocess.run([str(java/'java'),'-cp',str(classes)+os.pathsep+str(jar),'dev.tripoder.runtime.InstalledManifestAuditTest'],check=True)
    subprocess.run([str(java/'java'),'-cp',str(classes)+os.pathsep+str(jar),'dev.tripoder.hooklab.FileExportsTest'],check=True)
    subprocess.run(['node',str(repo/'agent/schema-fixtures.mjs'),str(root/'schema-cases.json')],check=True)
    subprocess.run([str(java/'java'),'-cp',str(classes)+os.pathsep+str(jar),'dev.tripoder.hooklab.AgentMcpConcurrencyTest',str(root)],check=True)
    server=subprocess.Popen([str(java/'java'),'-cp',str(classes)+os.pathsep+str(jar),'dev.tripoder.hooklab.McpHost',str(root)],stdout=subprocess.PIPE,text=True)
    try:
        if server.stdout.readline().strip()!='READY':raise RuntimeError('JAVA_MCP_SERVER_START_FAILED')
        subprocess.run(['node','--test','agent/agent.test.mjs','agent/audit.test.mjs','agent/schema.test.mjs','agent/file-pull.test.mjs','agent/artifact-verify.test.mjs','agent/recovery.test.mjs'],cwd=repo,env={**os.environ,'TRIPODER_MCP_TEST_CONNECTION':str(root/'connection.json')},check=True)
        python_client=subprocess.run([sys.executable,str(repo/'tools/tpmcp.py'),'tools'],cwd=repo,env={**os.environ,'TRIPODER_CONNECTION':str(root/'connection.json')},capture_output=True,text=True,check=True)
        assert json.loads(python_client.stdout)['tools'], 'Python HTTP client did not discover tools'
        print('Python HTTP initialize/session/catalog passed without a certificate')
        if os.environ.get('SENTINEL_HTTP_ACCEPTANCE_WORKSPACE'):
            workspace=Path(os.environ['SENTINEL_HTTP_ACCEPTANCE_WORKSPACE'])
            subprocess.run(['node','scripts/rust-build.mjs','cargo','test','-p','sentinel-tools','--no-default-features','--lib','connected_tripoder_http_without_certificate','--','--ignored','--nocapture'],cwd=workspace,env={**os.environ,'TRIPODER_MCP_TEST_CONNECTION':str(root/'connection.json')},check=True)
    finally:
        server.terminate();server.wait(timeout=10)
