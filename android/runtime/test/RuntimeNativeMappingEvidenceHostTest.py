#!/usr/bin/env python3
"""Exercise real mapping-evidence parsing with Android I/O stubbed; no device claims."""
import argparse
from pathlib import Path
import subprocess
import tempfile
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--java-home',required=True,type=Path);args=parser.parse_args()
repo=Path(__file__).resolve().parents[3]
with tempfile.TemporaryDirectory(prefix='tripoder-native-evidence-') as directory:
    root=Path(directory);sources=[]
    stubs={
      'android/system/StructStat.java':'package android.system;public class StructStat{public long st_size,st_ino,st_dev,st_mtime;}',
      'android/system/Os.java':'package android.system;public class Os{public static StructStat stat(String p)throws java.io.IOException{throw new java.io.IOException("not fixture I/O");}}',
      'android/os/Process.java':'package android.os;public class Process{public static int myPid(){return 0;}public static int myUid(){return 0;}}',
      'dev/tripoder/runtime/RuntimeEngine.java':'package dev.tripoder.runtime;final class RuntimeEngine{static RuntimeEngine current;static boolean adapterReady;}',
      'dev/tripoder/runtime/RuntimeSelfHookStorage.java':'package dev.tripoder.runtime;final class RuntimeSelfHookStorage{static byte[] readPhysicalProc(String p,int l)throws java.io.IOException{throw new java.io.IOException("not fixture I/O");}}'}
    for name,text in stubs.items():
        path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text);sources.append(path)
    sources.extend([repo/'android/runtime/src/dev/tripoder/runtime/RuntimeNativeMappingEvidence.java',repo/'android/runtime/test/dev/tripoder/runtime/RuntimeNativeMappingEvidenceTest.java'])
    subprocess.run([str(args.java_home/'bin/javac'),'--release','17','-d',str(root/'classes'),*map(str,sources)],check=True)
    subprocess.run([str(args.java_home/'bin/java'),'-cp',str(root/'classes'),'dev.tripoder.runtime.RuntimeNativeMappingEvidenceTest'],check=True)
