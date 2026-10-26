#!/usr/bin/env python3
"""Paired THIN/THICK launches against an already installed frozen QA build. Preserves all data and receipts."""
from pathlib import Path
import subprocess,time,json,re,sys,threading,os,hashlib
from paired_capture import capture
from paired_log import structural_log
root=Path(__file__).resolve().parents[2];base=root/'docs/evidence/compatibility-closure-2026-10-23';base.mkdir(exist_ok=True)
snapshot=Path(sys.argv[1]).resolve();receipt=json.loads((snapshot/'product-receipt.json').read_text());apkSha=receipt['artifacts']['engine']['sha256']
packages=sys.argv[2:] or ['com.openai.chatgpt','com.anthropic.claude','com.alicloud.databox','com.twitter.android','org.videolan.vlc','com.duolingo','com.google.android.youtube']
pkg='dev.tripoder.compat20261023';adb=['adb','-s','d861a4fa']
modes=os.environ.get('TRIPODER_PAIR_MODES','thin,thick').split(',')
if not modes or any(mode not in ('thin','thick') for mode in modes):raise ValueError('PAIR_MODES_INVALID')
local_apks=json.loads(os.environ.get('TRIPODER_PAIR_LOCAL_APKS','{}'))
if not isinstance(local_apks,dict):raise ValueError('PAIR_LOCAL_APKS_INVALID')
components=json.loads(os.environ.get('TRIPODER_PAIR_COMPONENTS','{}'))
if not isinstance(components,dict) or any(k!='dev.tripoder.isolatedfdprobe' or v!=k+'.ProductionActivity' for k,v in components.items()):raise ValueError('PAIR_OWNED_COMPONENT_INVALID')
local_apks.setdefault('dev.tripoder.closurefixture','/sdcard/Android/data/'+pkg+'/files/closure-fixture.apk')
def run(*args,timeout=60):return subprocess.run([*adb,*args],capture_output=True,text=True,timeout=timeout)
if hashlib.sha256((snapshot/'engine.apk').read_bytes()).hexdigest()!=apkSha:
 raise RuntimeError('PAIR_FROZEN_ENGINE_MISMATCH')
paths=run('shell','pm','path',pkg).stdout.splitlines()
if len(paths)!=1 or not re.fullmatch(r'package:/data/app/[A-Za-z0-9/._~+=-]+/base\.apk',paths[0]):
 raise RuntimeError('PAIR_INSTALLED_ENGINE_PATH_INVALID')
installed_digest=run('shell','sha256sum',paths[0].removeprefix('package:')).stdout.split()
if not installed_digest or installed_digest[0]!=apkSha:
 raise RuntimeError('PAIR_INSTALLED_ENGINE_MISMATCH')
for app in packages:
 space='paired-'+re.sub('[^A-Za-z0-9]','-',app)
 if app=='dev.tripoder.providerpeer':space='owned-provider-authority-contract'
 for mode_index,mode in enumerate(modes):
  runid=re.sub('[^A-Za-z0-9]','-',app)+'-'+mode+'-'+str(int(time.time()))
  out=base/runid;out.mkdir()
  start=time.time();owners=set();captured=[]
  device_clock=run('shell','date','+%s').stdout.strip()
  if not re.fullmatch(r'\d{10,12}',device_clock):raise RuntimeError('DEVICE_LOG_CLOCK_UNCONFIRMED')
  device_log_start=int(device_clock)
  logger=subprocess.Popen([*adb,'logcat','-v','epoch','-T','1'],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,errors="replace",bufsize=1)
  def collect():
   for line in logger.stdout:
    m=re.match(r'^\s*(\d+\.\d+)\s+(\d+)\s+',line)
    if not m or float(m[1])<device_log_start:continue
    announced=re.search(r'Start proc (\d+):'+re.escape(pkg),line)
    if announced:owners.add(announced[1])
    if m[2] in owners or app in line or 'Tripoder' in line or pkg in line:
     if 'TripoderPackages' in line and 'metaData=' in line:line=line.split('metaData=',1)[0]+'[metadata values omitted]\n'
     if 'TripoderPackages' in line and ' a=' in line:line=line.split(' a=',1)[0]+' [argument values omitted]\n'
     captured.append(structural_log(line))
  reader=threading.Thread(target=collect,daemon=True);reader.start()
  cmd=[*adb,'shell','am','instrument','-w','-e','package',app,'-e','space',space,'-e','mode',mode,'-e','run',runid,'-e','captureAck','true','-e','windowMs','120000' if app in ('dev.tripoder.closurefixture','dev.tripoder.isolatedservicecontract','dev.tripoder.isolatedfdprobe','dev.tripoder.nativeservicefixture') else os.environ.get('TRIPODER_PAIR_WINDOW_MS','30000'),pkg+'.tests/dev.tripoder.hooklab.PairedLaunchIntegration']
  if app in components:cmd[-1:-1]=['-e','component',components[app]]
  if app=='dev.tripoder.stopcontract':cmd[-1:-1]=['-e','stopStatusAck','true']
  if app in local_apks:cmd[-1:-1]=['-e','localApk',local_apks[app]]
  if app=='bbc.mobile.news.ww':cmd[-1:-1]=['-e','inspectLive','true']
  upgrade_fixture=app in ('dev.tripoder.closurefixture','dev.tripoder.componentrestartfixture','dev.tripoder.launchcontract','dev.tripoder.servicecontract','dev.tripoder.packagecontract','dev.tripoder.factorycontract','dev.tripoder.signalcontract','dev.tripoder.isolatedservicecontract','dev.tripoder.isolatedfdprobe','dev.tripoder.nativeservicefixture','dev.tripoder.perffixture') and os.environ.get('TRIPODER_PAIR_UPGRADE_FIXTURE')=='1' or app=='dev.tripoder.providerpeer' and os.environ.get('TRIPODER_PAIR_UPGRADE_PEER')=='1'
  if mode_index==0 and upgrade_fixture:cmd[-1:-1]=['-e','upgrade','true']
  if app=='dev.tripoder.providerpeer' and mode_index==0 and os.environ.get('TRIPODER_PAIR_UPGRADE_PEER_TARGET')=='1':cmd[-1:-1]=['-e','upgradePeerTarget','true']
  instrument_output=open(out/'instrumentation.txt','wb')
  p=subprocess.Popen(cmd,stdout=instrument_output,stderr=subprocess.STDOUT)
  time.sleep(2);run('shell','input','keyevent','224');run('shell','am','start','-n',pkg+'/dev.tripoder.hooklab.WorkbenchActivity')
  time.sleep(2)
  run('shell','uiautomator','dump','/sdcard/pair-workbench.xml',timeout=15)
  xml=run('shell','cat','/sdcard/pair-workbench.xml').stdout
  button=re.search(r'text="重新连接"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',xml)
  if button:run('shell','input','tap',str((int(button[1])+int(button[3]))//2),str((int(button[2])+int(button[4]))//2))
  signal='/sdcard/Android/data/'+pkg+'/files/paired-'+runid+'.json';snapshot={};last='';observed=False;ui_capture=None
  deadline=time.time()+300;kernel_observations=[];kernel_start=None;stop_status_ack=False
  while p.poll() is None and time.time()<deadline:
   raw=run('shell','cat',signal).stdout
   try:snapshot=json.loads(raw)
   except (ValueError,TypeError):pass
   owners.add(str(snapshot.get('launch',{}).get('pid','')))
   phase=snapshot.get('phase','')
   if phase!=last:print(app,mode,phase,flush=True);last=phase
   if app=='dev.tripoder.stopcontract' and phase in ('observing','capture-ready','stopping','stop-returned'):
    # Independent host observation of this one self-owned fixture only. A
    # missing proc row remains unavailable; it never asserts process death.
    launch=snapshot.get('launch',{});pid=launch.get('pid');uid=launch.get('uid')
    if isinstance(pid,int) and 0<pid<2**31 and isinstance(uid,int):
     status_result=run('shell','cat',f'/proc/{pid}/status',timeout=5)
     stat_result=run('shell','cat',f'/proc/{pid}/stat',timeout=5)
     status={}
     if status_result.returncode==0:
      for line in status_result.stdout.splitlines():
       key,sep,value=line.partition(':')
       if sep and key in ('State','TracerPid','Uid','Threads','Pid'):status[key]=value.strip()
     fields=stat_result.stdout.rsplit(')',1)[-1].split() if stat_result.returncode==0 else []
     ticks=fields[19] if len(fields)>19 and fields[19].isdigit() else None
     uid_exact=status.get('Uid','').split()==[str(uid)]*4 and status.get('Pid')==str(pid)
     if kernel_start is None and uid_exact and ticks is not None:kernel_start=ticks
     kernel_observations.append({'phase':phase,'hostElapsedMs':round((time.time()-start)*1000),'processGeneration':launch.get('generation'),'pid':pid,'status':status,'startTicks':ticks,'sameObservedKernelProcess':uid_exact and ticks is not None and ticks==kernel_start,'controllerStopElapsedMs':snapshot.get('stopElapsedMs')})
     (out/'owned-kernel-observations.json').write_text(json.dumps({'ownedPackage':app,'observations':kernel_observations,'missingProcDoesNotProveDeath':True},indent=2)+'\n')
    if phase=='stop-returned' and not stop_status_ack:
     ack=out/'stop-status-ack.txt';ack.write_text(runid+'\n');ack_path='/sdcard/Android/data/'+pkg+'/files/paired-'+runid+'.stop-status-ack'
     uploaded=run('push',str(ack),ack_path+'.pending',timeout=5)
     published=run('shell','mv','--',ack_path+'.pending',ack_path,timeout=5) if uploaded.returncode==0 else None
     stop_status_ack=published is not None and published.returncode==0
   if phase=='capture-ready' and not observed:
    observed=True;capture_deadline=time.monotonic()+45
    def capture_run(*args,timeout=60):
     remaining=capture_deadline-time.monotonic()
     if remaining<=0:raise TimeoutError('PAIR_CAPTURE_DEADLINE')
     return run(*args,timeout=min(timeout,remaining))
    try:ui_capture=capture(capture_run,out,pkg,runid,app,snapshot.get('launch',{}).get('uid'))
    except (subprocess.TimeoutExpired,TimeoutError) as error:
     ui_capture={'captured':False,'reason':'PAIR_CAPTURE_DEADLINE','errorType':type(error).__name__}
    finally:
     ack=out/'capture-ack.txt';ack.write_text(runid+'\n')
     ack_path='/sdcard/Android/data/'+pkg+'/files/paired-'+runid+'.capture-ack'
     # Upload completion and publication are different events. A same-directory
     # rename prevents the reader observing adb push's empty/partial file.
     uploaded=run('push',str(ack),ack_path+'.pending',timeout=15)
     published=run('shell','mv','--',ack_path+'.pending',ack_path,timeout=15) if uploaded.returncode==0 else None
     (out/'capture-ack-result.json').write_text(json.dumps({'uploaded':uploaded.returncode==0,'acknowledged':published is not None and published.returncode==0})+'\n')
    (out/'capture.json').write_text(json.dumps(ui_capture,ensure_ascii=False,indent=2)+'\n')
   time.sleep(1)
  if p.poll() is None:p.kill()
  p.wait(timeout=5);instrument_output.close();stdout=(out/'instrumentation.txt').read_text(errors='replace')
  try:
   payload=next(x.split('report=',1)[1] for x in stdout.splitlines() if x.startswith('INSTRUMENTATION_RESULT: report='));snapshot=json.loads(payload)
  except (StopIteration,ValueError):snapshot['harnessTimeout']=True
  snapshot['sourceSnapshot']=str(Path(sys.argv[1]).resolve());snapshot['apkSha256']=apkSha;snapshot['installedProductSha256']=installed_digest[0];snapshot['hostStartedEpoch']=start;snapshot['deviceLogStartedEpochLowerBound']=device_log_start
  snapshot['uiCapture']=ui_capture
  (out/'result.json').write_text(json.dumps(snapshot,ensure_ascii=False,indent=2)+'\n')
  logger.terminate();reader.join(timeout=5);logger.wait(timeout=5)
  (out/'logcat.txt').write_text('\n'.join(captured)+'\n')
  print('RESULT',app,mode,snapshot.get('phase'),snapshot.get('error','').splitlines()[:2],out,flush=True)
  if snapshot.get('phase')!='complete' or not snapshot.get('stopConfirmed') or snapshot.get('harnessTimeout'):
   raise RuntimeError('PAIR_RUN_NOT_CONFIRMED: '+runid)
  if mode_index==0 and upgrade_fixture and (not snapshot.get('fixtureInstalledGeneration') or snapshot.get('fixtureInstalledGeneration')!=snapshot.get('installGeneration')):
   raise RuntimeError('OWNED_FIXTURE_UPGRADE_NOT_CONFIRMED: '+runid)
  if app=='dev.tripoder.providerpeer' and mode_index==0 and os.environ.get('TRIPODER_PAIR_UPGRADE_PEER_TARGET')=='1' and (not snapshot.get('peerTargetUpgradedGeneration') or snapshot.get('peerTargetUpgradedGeneration')!=snapshot.get('peerTarget',{}).get('installGeneration')):
   raise RuntimeError('OWNED_PEER_TARGET_UPGRADE_NOT_CONFIRMED: '+runid)
