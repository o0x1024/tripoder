#!/usr/bin/env python3
"""Same owned test APK against two explicitly selected frozen QA engines. No data clearing."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,zipfile
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--evidence',type=Path,required=True)
parser.add_argument('--before',type=Path,required=True)
parser.add_argument('--after',type=Path,required=True)
parser.add_argument('--serial',default='d861a4fa')
args=parser.parse_args();repo=Path(__file__).resolve().parents[2];out=args.evidence.resolve();out.mkdir(parents=True,exist_ok=False)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
java=Path('/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home');sdk=Path('/opt/homebrew/share/android-commandlinetools');tools=sdk/'build-tools/37.0.0';platform=sdk/'platforms/android-37.0/android.jar'
env=dict(os.environ,JAVA_HOME=str(java));env['PATH']=str(java/'bin')+os.pathsep+env.get('PATH','')
def run(*command,timeout=120):
    p=subprocess.run(list(map(str,command)),capture_output=True,text=True,env=env,timeout=timeout)
    if p.returncode:raise RuntimeError(p.stdout+p.stderr)
    return p.stdout
snapshots=[]
for stage,directory in [('before',args.before.resolve()),('after',args.after.resolve())]:
    receipt=json.loads((directory/'product-receipt.json').read_text());engine=receipt['artifacts']['engine'];apk=directory/'engine.apk'
    if not engine['package'].startswith('dev.tripoder.compat') or sha(apk)!=engine['sha256']:raise ValueError('FROZEN_QA_IMAGE_REQUIRED')
    snapshots.append((stage,directory,receipt,engine,apk))
if snapshots[0][3]['package']!=snapshots[1][3]['package']:raise ValueError('SAME_QA_PACKAGE_REQUIRED')
package=snapshots[1][3]['package'];source=out/'RejectedRestoreIntegration.java';shutil.copy2(repo/'android/compatibility-test/RejectedRestoreIntegration.java',source)
classes=out/'classes';dex=out/'dex';classes.mkdir();dex.mkdir()
product_classes=Path(snapshots[1][3]['path']).with_suffix('')/'classes'
run(java/'bin/javac','--release','17','-cp',str(platform)+os.pathsep+str(product_classes),'-d',classes,source)
run(tools/'d8','--release','--min-api','36','--lib',platform,'--classpath',product_classes,'--output',dex,*classes.rglob('*.class'))
manifest=out/'AndroidManifest.xml';manifest.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}.tests"><uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36"/><instrumentation android:name="dev.tripoder.runtime.RejectedRestoreIntegration" android:targetPackage="{package}"/><application/></manifest>')
unsigned=out/'unsigned.apk';aligned=out/'aligned.apk';test=out/'test.apk'
run(tools/'aapt2','link','-I',platform,'--manifest',manifest,'-o',unsigned)
with zipfile.ZipFile(unsigned,'a') as z:
    for p in dex.glob('*.dex'):z.write(p,p.name)
run(tools/'zipalign','-f','4',unsigned,aligned)
run(tools/'apksigner','sign','--ks',repo/'android/runtime-probe/build/acceptance-only.p12','--ks-pass','pass:tripoder-test-only','--ks-key-alias','p0','--out',test,aligned)
adb=['adb','-s',args.serial];results=[]
for stage,directory,receipt,engine,apk in snapshots:
    run(*adb,'install','--no-incremental','-r',apk);run(*adb,'install','--no-incremental','-r',test)
    raw=run(*adb,'shell','am','instrument','--no-hidden-api-checks','-w',package+'.tests/dev.tripoder.runtime.RejectedRestoreIntegration')
    (out/(stage+'-instrumentation.txt')).write_text(raw)
    payload=next((line.split('report=',1)[1] for line in raw.splitlines() if line.startswith('INSTRUMENTATION_RESULT: report=')),None)
    result=json.loads(payload) if payload else {'passed':False,'error':'OWNED_REPORT_MISSING'}
    result.update(stage=stage,engineSha256=engine['sha256'],testApkSha256=sha(test),sourceSha256=sha(source))
    (out/(stage+'-result.json')).write_text(json.dumps(result,indent=2)+'\n');results.append(result);print(stage,result.get('passed'),result.get('error','').splitlines()[:1],flush=True)
(out/'result.json').write_text(json.dumps({'sameTestApk':True,'results':results,'dataCleared':False,'originalEngineModified':False},indent=2)+'\n')
if results[0]['passed'] or not results[1]['passed']:raise SystemExit(1)
