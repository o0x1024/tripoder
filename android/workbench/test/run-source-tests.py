#!/usr/bin/env python3
"""Run production WorkbenchProjects with host-only Context/AtomicFile adapters.

This checks filesystem recovery/CAS behavior, not Android IPC or native AtomicFile crash semantics.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import urllib.request

repo = Path(__file__).resolve().parents[3]
cache = Path(tempfile.gettempdir()) / "tripoder-source-test-deps"
cache.mkdir(exist_ok=True)
jar = cache / "json-20250517.jar"
if not jar.exists():
    urllib.request.urlretrieve("https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar", jar)
java_home = os.environ.get("JAVA_HOME")
javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
with tempfile.TemporaryDirectory(prefix="tripoder-source-test-") as temporary:
    root = Path(temporary)
    context = root / "Context.java"
    context.write_text('package android.content; public final class Context { private final java.io.File root; public Context(java.io.File root){this.root=root;} public java.io.File getFilesDir(){return root;} }')
    atomic = root / "AtomicFile.java"
    atomic.write_text('''package android.util;
public final class AtomicFile {
 private final java.io.File file,stage;
 public AtomicFile(java.io.File file){this.file=file;stage=new java.io.File(file+".new");}
 public java.io.FileOutputStream startWrite()throws java.io.IOException{return new java.io.FileOutputStream(stage);}
 public void finishWrite(java.io.FileOutputStream output)throws java.io.IOException{output.getFD().sync();output.close();java.nio.file.Files.move(stage.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
 public void failWrite(java.io.FileOutputStream output)throws java.io.IOException{output.close();java.nio.file.Files.deleteIfExists(stage.toPath());}
}''')
    classes = root / "classes"
    classes.mkdir()
    subprocess.run([javac, "--release", "17", "-cp", str(jar), "-d", str(classes), str(context), str(atomic), str(repo / "android/workbench/src/dev/tripoder/hooklab/WorkbenchProjects.java"), str(repo / "android/workbench/test/SingleProjectSourceTest.java")], check=True)
    subprocess.run([java, "-cp", str(classes) + os.pathsep + str(jar), "dev.tripoder.hooklab.SingleProjectSourceTest"], check=True)
