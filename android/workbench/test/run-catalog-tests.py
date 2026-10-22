#!/usr/bin/env python3
"""Exercise the production catalog's mode selection with synthetic authority snapshots."""
from pathlib import Path
import os
import subprocess
import tempfile
import urllib.request

repo = Path(__file__).resolve().parents[3]
cache = Path(tempfile.gettempdir()) / "tripoder-source-test-deps"
cache.mkdir(exist_ok=True)
jar = cache / "json-20250517.jar"
if not jar.exists():
    urllib.request.urlretrieve("https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar", jar)
java_home = Path(os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"))
with tempfile.TemporaryDirectory(prefix="tripoder-catalog-mode-") as temporary:
    root = Path(temporary)
    bundle = root / "Bundle.java"
    bundle.write_text('''package android.os;
public final class Bundle extends java.util.HashMap<String,String>{
 public void putString(String key,String value){put(key,value);}
 public String getString(String key){return get(key);}public String getString(String key,String fallback){return getOrDefault(key,fallback);}
}''')
    app = root / "WorkbenchApplication.java"
    app.write_text('''package dev.tripoder.hooklab;
final class WorkbenchApplication {
 final Projects projects=new Projects();final Presentations presentations=new Presentations();final Spaces spaces=new Spaces();
 static final class Projects {void reconcile(org.json.JSONArray values){}}
 static final class Presentations {android.os.Bundle get(String generation){return null;}}
 static final class Spaces {String name(String space){return space;}}
}''')
    classes = root / "classes"
    classes.mkdir()
    subprocess.run([str(java_home / "bin/javac"), "--release", "17", "-cp", str(jar), "-d", str(classes), str(bundle), str(app), str(repo / "android/workbench/src/dev/tripoder/hooklab/WorkbenchCatalog.java"), str(repo / "android/workbench/test/CatalogModeTest.java")], check=True)
    subprocess.run([str(java_home / "bin/java"), "-cp", str(classes) + os.pathsep + str(jar), "dev.tripoder.hooklab.CatalogModeTest"], check=True)
