#!/usr/bin/env python3
"""Host behavioral checks and SDK type checking; never installs or starts an APK."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home',required=True,type=Path)
    parser.add_argument('--android-jar',type=Path)
    parser.add_argument('--apksig-jar',type=Path)
    args=parser.parse_args()
    here=Path(__file__).resolve().parent
    repo=here.parents[2]
    build=here/'build';build.mkdir(exist_ok=True)
    (build/'report.json').unlink(missing_ok=True)
    runtime=repo/'android/runtime/src/dev/tripoder/runtime'
    classes=build/'host-classes';classes.mkdir(exist_ok=True)
    fixture=build/'fixture';fixture.mkdir(exist_ok=True)
    javac=args.java_home/'bin/javac';java=args.java_home/'bin/java'
    def run(*command):
        result=subprocess.run([str(item) for item in command],capture_output=True,text=True)
        if result.returncode:
            raise RuntimeError(str(command[0])+': '+result.stdout+'\n'+result.stderr)
        return result.stdout.strip()
    inputs=[runtime/name for name in ['RuntimeSelfHookBootstrap.java','RuntimeSelfHookLoaders.java',
        'RuntimeSelfHookLifecycle.java','RuntimeSelfHookProviderCalls.java','RuntimeSelfHookSelection.java','RuntimeSelfHookBoundaries.java',
        'RuntimeSelfHookManagerPolicy.java','RuntimeFixedPassthrough.java','RuntimeStartupDeadline.java','RuntimePendingIntentPolicy.java','PreferencesWait.java','ServiceRecoveryPolicy.java']]
    inputs+=sorted((here/'src/dev').rglob('*.java'))+sorted((here/'src/fixture').rglob('*.java'))
    before={str(path.relative_to(repo)):digest(path) for path in inputs}
    run(javac,'--release','17','-d',fixture,here/'src/fixture/OwnedApplication.java')
    run(javac,'--release','17','-d',classes,*[path for path in inputs if path.name!='OwnedApplication.java'])
    outputs=[run(java,'-cp',classes,'dev.tripoder.runtime.BootstrapTest'),
             run(java,'-cp',classes,'dev.tripoder.runtime.LoaderTest',fixture),
             run(java,'-cp',classes,'dev.tripoder.runtime.ComponentTest'),
             run(java,'-cp',classes,'dev.tripoder.runtime.BoundaryTest'),
             run(java,'-cp',classes,'dev.tripoder.runtime.ManagerPolicyTest'),
             run(java,'-cp',classes,'dev.tripoder.runtime.FixedPassthroughTest'),
             run(java,'-cp',classes,'dev.tripoder.runtime.StartupDeadlineTest'),
             run(java,'-cp',classes,'dev.tripoder.runtime.PendingIntentPolicyTest'),
             run(java,'-cp',classes,'dev.tripoder.runtime.PreferencesWaitTest'),
             run(java,'-cp',classes,'dev.tripoder.runtime.ServiceRecoveryPolicyTest')]
    if before!={str(path.relative_to(repo)):digest(path) for path in inputs}:raise RuntimeError('HOST_SOURCES_CHANGED_DURING_CHECK')
    report={'schema':1,'hostOutputs':outputs,'sourceSha256':before,'runnerSha256':digest(Path(__file__).resolve()),
            'deviceExecuted':False,'productAccepted':False}
    if args.android_jar:
        if not args.apksig_jar:raise ValueError('--apksig-jar required with --android-jar')
        sdk_classes=build/'sdk-classes';sdk_classes.mkdir(exist_ok=True)
        sources=sorted(path for path in (repo/'android/runtime/src').rglob('*.java') if path.name!='RuntimeAuthorityService.java')
        sources+=sorted((repo/'android/control/src').rglob('*.java'))+sorted((repo/'android/engine/src').rglob('*.java'))
        sources+=sorted((repo/'android/workbench/src').rglob('*.java'))
        sources += sorted((repo/'android/self-hook/java').rglob('*.java'))
        component_config=build/'RuntimeComponentBuild.java'
        component_config.write_text('package dev.tripoder.runtime; final class RuntimeComponentBuild { static final String ACTIVITY_POOL_PREFIX="TaskP"; }\n')
        sources.append(component_config)
        snapshots={str(path.relative_to(repo)):digest(path) for path in sources}
        run(javac,'--release','17','-classpath',str(args.android_jar)+':'+str(args.apksig_jar),'-d',sdk_classes,*sources)
        if snapshots!={str(path.relative_to(repo)):digest(path) for path in sources}:raise RuntimeError('SDK_SOURCES_CHANGED_DURING_CHECK')
        report['sdkCompile']={'sources':len(sources),'sourceSha256':snapshots,'androidJarSha256':digest(args.android_jar),
                              'apksigSha256':digest(args.apksig_jar),'signedOrPackaged':False}
        typed_classes=build/'manager-typed';typed_classes.mkdir(exist_ok=True)
        typed_sources=sorted((here/'src/manager-fixture').rglob('*.java'))
        typed_before={str(path.relative_to(repo)):digest(path) for path in typed_sources}
        run(javac,'--release','17','-classpath',str(sdk_classes)+':'+str(args.android_jar),'-d',typed_classes,*typed_sources)
        outputs.append(run(java,'-cp',str(typed_classes)+':'+str(sdk_classes)+':'+str(args.android_jar),'dev.tripoder.runtime.ManagerTypedTest'))
        if typed_before!={str(path.relative_to(repo)):digest(path) for path in typed_sources}:raise RuntimeError('TYPED_FIXTURES_CHANGED_DURING_CHECK')
        if snapshots!={str(path.relative_to(repo)):digest(path) for path in sources}:raise RuntimeError('SDK_SOURCES_CHANGED_DURING_TYPED_CHECK')
        report['managerTypedFixtureSha256']=typed_before
        mapping_classes=build/'mapping-fixture';mapping_classes.mkdir(exist_ok=True)
        mapping_test=here/'src/mapping-fixture/dev/tripoder/runtime/RuntimeNativeMappingEvidenceTest.java'
        mapping_before=digest(mapping_test)
        run(javac,'--release','17','-classpath',str(sdk_classes)+':'+str(args.android_jar),'-d',mapping_classes,mapping_test)
        outputs.append(run(java,'-cp',str(mapping_classes)+':'+str(sdk_classes)+':'+str(args.android_jar),'dev.tripoder.runtime.RuntimeNativeMappingEvidenceTest'))
        if mapping_before!=digest(mapping_test):raise RuntimeError('MAPPING_FIXTURE_CHANGED_DURING_CHECK')
        report['mappingFixtureSha256']={str(mapping_test.relative_to(repo)):mapping_before}
    (build/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'hostOutputs':outputs,'sdkSources':report.get('sdkCompile',{}).get('sources'),
                      'report':str(build/'report.json'),'deviceExecuted':False}))


if __name__=='__main__':main()
