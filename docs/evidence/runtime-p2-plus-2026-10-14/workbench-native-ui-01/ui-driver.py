import subprocess,xml.etree.ElementTree as E,re,json,sys,hashlib,datetime
from pathlib import Path
root=Path('/Users/like/code/tripoder/docs/evidence/runtime-p2-plus-2026-10-14/workbench-native-ui-01')
root.mkdir(exist_ok=True)
adb=['adb','-s','d861a4fa'];log=root/'actions.jsonl'
def call(*a):
 r=subprocess.run(adb+list(a),capture_output=True,check=True);return r.stdout
def record(action,detail):
 with log.open('a') as f:f.write(json.dumps({'time':datetime.datetime.now(datetime.timezone.utc).isoformat(),'action':action,'detail':detail},ensure_ascii=False)+'\n')
def dump():
 call('shell','uiautomator','dump','/sdcard/Download/tripoder-ui.xml')
 raw=call('shell','cat','/sdcard/Download/tripoder-ui.xml');return E.fromstring(raw)
def show(tree):
 for n in tree.iter('node'):
  if n.get('text') or n.get('content-desc'):print(n.get('text'),n.get('content-desc'),n.get('bounds'),n.get('class'))
mode=sys.argv[1]
if mode=='inspect':show(dump())
elif mode=='tap':
 tree=dump();key=sys.argv[2];matches=[n for n in tree.iter('node') if n.get('text')==key or n.get('content-desc')==key]
 if len(matches)!=1:raise RuntimeError('EXPECTED_ONE_VISIBLE_TARGET:'+key+':'+str(len(matches)))
 n=matches[0];b=list(map(int,re.findall(r'\d+',n.get('bounds'))));call('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2));record('tap',{'text':key,'bounds':b})
elif mode=='text':
 tree=dump();ns=[n for n in tree.iter('node') if n.get('class')=='android.widget.EditText']
 if len(ns)!=1:raise RuntimeError('EXPECTED_ONE_EDITOR')
 n=ns[0];b=list(map(int,re.findall(r'\d+',n.get('bounds'))));call('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2));call('shell','input','keyevent','KEYCODE_MOVE_END');call('shell','input','keycombination','113','29');call('shell','input','text',sys.argv[2]);record('text',sys.argv[2])
elif mode=='key':call('shell','input','keyevent',sys.argv[2]);record('key',sys.argv[2])
elif mode=='swipe':call('shell','input','swipe',*sys.argv[2:]);record('swipe',sys.argv[2:])
elif mode=='capture':
 tree=dump();packages={n.get('package') for n in tree.iter('node') if n.get('package')}
 if packages-{'dev.tripoder.workbench','com.android.systemui','com.android.inputmethod.latin'}:raise RuntimeError('SCREEN_SCOPE:'+str(packages))
 raw=call('exec-out','screencap','-p');start=raw.find(bytes.fromhex('89504e470d0a1a0a'))
 if start<0:raise RuntimeError('PNG_MISSING')
 (root/(sys.argv[2]+'.png')).write_bytes(raw[start:]);(root/(sys.argv[2]+'.xml')).write_bytes(E.tostring(tree,encoding='utf-8'));record('capture',{'name':sys.argv[2],'pngSha256':hashlib.sha256(raw[start:]).hexdigest(),'strippedPrefixBytes':start})
else:raise RuntimeError('UNKNOWN_ACTION')
