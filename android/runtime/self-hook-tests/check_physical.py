#!/usr/bin/env python3
"""Read-only physical JNI protocol checks on owned host fixtures, never Android/device acceptance."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home',required=True,type=Path)
    parser.add_argument('--clang',default='/usr/bin/clang',type=Path)
    args=parser.parse_args()
    here=Path(__file__).resolve().parent;repo=here.parents[2]
    core=repo/'android/self-hook';build=here/'build/physical';build.mkdir(parents=True,exist_ok=True)
    report=build/'report.json';report.unlink(missing_ok=True)
    source=core/'native/physical_application.c';java_source=core/'java/dev/tripoder/selfhook/PhysicalApplication.java'
    harness=here/'src/physical/dev/tripoder/selfhook/PhysicalApplicationHostTest.java'
    inputs=[source,java_source,harness,here/'physical_application_mock.c',Path(__file__).resolve()]
    snapshots={str(path.relative_to(repo)):sha(path) for path in inputs}
    def run(*command):
        result=subprocess.run([str(value) for value in command],capture_output=True,text=True)
        if result.returncode:raise RuntimeError(str(command[0])+'\n'+result.stdout+result.stderr)
        if 'WARNING in native method' in result.stdout+result.stderr or 'FATAL ERROR in native method' in result.stdout+result.stderr:
            raise RuntimeError('JNI_CHECK_FAILED\n'+result.stdout+result.stderr)
        return result.stdout.strip()
    includes=['-I'+str(args.java_home/'include'),'-I'+str(args.java_home/'include/darwin')]
    mock=build/'physical-jni-mock'
    run(args.clang,'-std=c11','-Wall','-Wextra','-Werror','-fsanitize=address,undefined','-g','-pthread',*includes,here/'physical_application_mock.c','-o',mock)
    outputs=[run(mock)]
    library=build/'libphysical-host.dylib'
    run(args.clang,'-std=c11','-Wall','-Wextra','-Werror','-O2','-dynamiclib','-pthread',*includes,source,'-o',library)
    generated=build/'generated';generated.mkdir(exist_ok=True)
    stub=generated/'FrameworkAccess.java'
    stub.write_text('package dev.tripoder.selfhook; public final class FrameworkAccess { public static final class Members { public final Class<?>[] owners; public final java.lang.reflect.Field[] fields; public Members(Class<?>[] o,java.lang.reflect.Field[] f){owners=o;fields=f;} } }\n')
    java=args.java_home/'bin/java';javac=args.java_home/'bin/javac'
    variants={'base':['normal','roots','thread','initial','apk','owner','metadata-old','metadata-null','metadata-owner','metadata-name','metadata-class','metadata-loader'],
              'type':['metadata-type'],'static':['metadata-static'],'current-static':['metadata-current-static']}
    for variant,scenarios in variants.items():
        variant_dir=generated/variant;variant_dir.mkdir(exist_ok=True)
        initial_type='Object' if variant=='type' else 'Application';initial_static='static ' if variant=='static' else ''
        current_static='' if variant=='current-static' else 'static '
        sources={
            'Application.java':'package android.app; public class Application { public LoadedApk mLoadedApk; public Object getApplicationContext(){throw new AssertionError("MANAGED_GETTER_TRAP");} }\n',
            'ActivityThread.java':'package android.app; public class ActivityThread { public '+initial_static+initial_type+' mInitialApplication; public Application otherInitial; public '+current_static+'ActivityThread sCurrentActivityThread; public static Application currentApplication(){throw new AssertionError("MANAGED_GETTER_TRAP");} public static ActivityThread currentActivityThread(){throw new AssertionError("MANAGED_GETTER_TRAP");} }\n',
            'LoadedApk.java':'package android.app; public class LoadedApk { public Application mApplication; public Application getApplication(){throw new AssertionError("MANAGED_GETTER_TRAP");} }\n'
        }
        for name,text in sources.items():(variant_dir/name).write_text(text)
        boot=build/variant/'boot';boot.mkdir(parents=True,exist_ok=True)
        classes=build/variant/'classes';classes.mkdir(exist_ok=True)
        run(javac,'--release','17','-d',boot,*[variant_dir/name for name in sources])
        run(javac,'--release','17','-cp',boot,'-d',classes,stub,java_source,harness)
        for scenario in scenarios:outputs.append(run(java,'-Xcheck:jni','-Xbootclasspath/a:'+str(boot),'-cp',classes,'dev.tripoder.selfhook.PhysicalApplicationHostTest',library,scenario))
    if snapshots!={str(path.relative_to(repo)):sha(path) for path in inputs}:raise RuntimeError('PHYSICAL_SOURCES_CHANGED_DURING_TEST')
    result={'schema':1,'outputs':outputs,'sourceSha256':snapshots,'nativeLibrarySha256':sha(library),
            'mockSanitizers':['address','undefined'],'jniCheck':True,'ownedHostFixturesOnly':True,
            'androidExecuted':False,'movingArtGcProven':False,'productAccepted':False}
    report.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'outputs':outputs,'report':str(report),'androidExecuted':False}))


if __name__=='__main__':main()
