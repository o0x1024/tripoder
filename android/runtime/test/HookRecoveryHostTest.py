#!/usr/bin/env python3
"""Exercise production recovery logic on the host; Binder/SQLite are not exercised."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
JAVA = Path(os.environ.get('TRIPODER_JAVA_HOME', '/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home')) / 'bin'
STUBS = {
    'android/os/Bundle.java': '''package android.os;
import java.util.*;
public final class Bundle {
 private final Map<String,Object> values=new HashMap<>();
 public void putString(String key,String value){values.put(key,value);}
 public void putInt(String key,int value){values.put(key,value);}
 public String getString(String key){return (String)values.get(key);}
 public int getInt(String key){return (Integer)values.get(key);}
 public <T> T getParcelable(String key,Class<T> type){return null;}
}''',
    'org/json/JSONObject.java': '''package org.json;
import java.util.*;
import dev.tripoder.runtime.script.HookWire;
public final class JSONObject {
 private final Map<String,Object> values;
 public JSONObject(){values=new LinkedHashMap<>();}
 public JSONObject(String text){values=HookWire.request(text);}
 public JSONObject put(String key,Object value){values.put(key,value instanceof JSONObject?((JSONObject)value).values:value);return this;}
 public Object get(String key){return values.get(key);}
 public int getInt(String key){return ((Number)values.get(key)).intValue();}
 public Iterator<String> keys(){return values.keySet().iterator();}
 public boolean getBoolean(String key){return (Boolean)values.get(key);}
 public String getString(String key){return (String)values.get(key);}
 public String optString(String key){return values.containsKey(key)?String.valueOf(values.get(key)):"";}
 public String optString(String key,String fallback){return values.containsKey(key)?String.valueOf(values.get(key)):fallback;}
 public boolean has(String key){return values.containsKey(key);}
 public JSONObject getJSONObject(String key){return new JSONObject(HookWire.encode(values.get(key)));}
 public Object remove(String key){return values.remove(key);}
 public String toString(){return HookWire.encode(values);}
}''',
    'android/content/Intent.java': '''package android.content;
public final class Intent {public static final int FLAG_ACTIVITY_NEW_TASK=1;public Intent addFlags(int flags){return this;}}''',
    'dev/tripoder/control/ControlWire.java': '''package dev.tripoder.control;
import android.os.Bundle;
public final class ControlWire {public static Bundle request(String command,String...pairs){Bundle out=new Bundle();out.putString("command",command);for(int i=0;i<pairs.length;i+=2)out.putString(pairs[i],pairs[i+1]);return out;}}''',
    'dev/tripoder/hooklab/WorkbenchApplication.java': '''package dev.tripoder.hooklab;
import android.os.Bundle;
final class WorkbenchApplication {
 final EngineConnection engine=new EngineConnection();int uiChanges;void controlChanged(org.json.JSONObject target){uiChanges++;}
 void startActivity(android.content.Intent intent){throw new AssertionError("unexpected launch");}
}
final class EngineConnection {
 interface Result {void done(Bundle value);}
 int calls;String operation;boolean unavailable;
 Bundle executeBlocking(Bundle request){if(unavailable){calls++;throw new IllegalStateException("ENGINE_NOT_CONNECTED");}if(!"agentOperation".equals(request.getString("command")))throw new AssertionError("unexpected mutation");calls++;operation=request.getString("operation");Bundle out=new Bundle();out.putString("result","{\\"operationId\\":\\""+operation+"\\",\\"state\\":\\"UNKNOWN\\"}");return out;}
 void execute(Bundle request,Result result){throw new AssertionError("unexpected shutdown");}
}''',
    'dev/tripoder/runtime/RuntimeCodeArtifacts.java': '''package dev.tripoder.runtime;
public final class RuntimeCodeArtifacts {
 public static org.json.JSONObject list(Object app,String generation){throw new AssertionError("unexpected artifact read");}
 public static org.json.JSONObject read(Object app,String generation,String artifact,int offset,int length){throw new AssertionError("unexpected artifact read");}
}''',
    'dev/tripoder/hooklab/WorkbenchAgentLifecycle.java': '''package dev.tripoder.hooklab;
import org.json.JSONObject;import android.os.Bundle;
final class WorkbenchAgentLifecycle {
 private final WorkbenchApplication app;WorkbenchAgentLifecycle(WorkbenchApplication app){this.app=app;}
 JSONObject receiptIfPresent(String id){return null;}
 void cleanupReceipt(JSONObject receipt){}
 JSONObject call(String method,JSONObject params,String id){if(method.equals("space.delete")){app.engine.executeBlocking(dev.tripoder.control.ControlWire.request("deleteSpace","operation",id,"space",params.getString("space")));}throw new AssertionError("unexpected lifecycle call");}
 Bundle target(JSONObject target,boolean process){throw new AssertionError("unexpected target resolution");}
 interface Mutation{JSONObject run()throws Exception;}
 JSONObject mutate(String id,String method,JSONObject params,Mutation action)throws Exception{throw new AssertionError("unexpected local mutation");}
}
''',
    'dev/tripoder/hooklab/WorkbenchAgentImports.java': '''package dev.tripoder.hooklab;
import org.json.JSONObject;
final class WorkbenchAgentImports {
 WorkbenchAgentImports(WorkbenchApplication app){}
 JSONObject call(String method,JSONObject params,String id){throw new AssertionError("unexpected import");}
}
''',
    'dev/tripoder/runtime/FakeBoundary.java': '''package dev.tripoder.runtime;
import android.os.Bundle;
final class RuntimeBroker {
 final ExecutionContract contract;final Store store=new Store();
 RuntimeBroker(ExecutionContract contract){this.contract=contract;}
 static final class Slot {Object peer=new Object();}
 Slot requiredSlot(String generation){return new Slot();}
 static final class Store {void requireExplicitHookRecovery(String generation,String operation){}}
}
final class RuntimeWire {
 static final int HOOK=1;
 interface Handler {Bundle call(Object peer,int code,Bundle input)throws Exception;}
 static Handler handler;
 static Bundle call(Object peer,int code,Bundle input)throws Exception{return handler.call(peer,code,input);}
}
final class ApkStore {
 static String textDigest(String text)throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
}''',
}

with tempfile.TemporaryDirectory(prefix='tripoder-hook-recovery-') as tmp:
    output = Path(tmp)
    sources = []
    for name, text in STUBS.items():
        source = output / name
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(text)
        sources.append(source)
    production = ROOT / 'android/runtime/src/dev/tripoder/runtime'
    sources += [production / (name + '.java') for name in ('ExecutionContract', 'RuntimeHookClaims', 'RuntimeHookOperations')]
    sources += list((production / 'script').glob('*.java'))
    sources += [ROOT / 'android/self-hook/java/dev/tripoder/selfhook' / name for name in ('NativeHook.java', 'NativeModules.java')]
    sources += [Path(__file__).parent / 'dev/tripoder/runtime/HookRecoveryTest.java']
    sources += [Path(__file__).parent / 'WorkbenchRecoveryTest.java', ROOT / 'android/workbench/src/dev/tripoder/hooklab/WorkbenchAgentControl.java', ROOT / 'android/control/src/dev/tripoder/control/AgentCrypto.java']
    subprocess.run([str(JAVA / 'javac'), '--release', '17', '-d', str(output), *map(str, sources)], check=True)
    subprocess.run([str(JAVA / 'java'), '-ea', '-cp', str(output), 'dev.tripoder.runtime.HookRecoveryTest'], check=True, timeout=30)
    subprocess.run([str(JAVA / 'java'), '-ea', '-cp', str(output), 'dev.tripoder.hooklab.WorkbenchRecoveryTest'], check=True, timeout=30)
