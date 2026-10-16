#!/usr/bin/env python3
"""Independent binary DEX declared-method oracle; no production or Java reflection calls."""
import hashlib, json, re, struct, subprocess, zipfile, zlib
from pathlib import Path
ROOT = Path('/Users/like/code/tripoder')
OUT = Path('/private/tmp/tripoder-framework-gc-oracle')
DEXDUMP = Path('/opt/homebrew/share/android-commandlinetools/build-tools/37.0.0/dexdump')
SOURCES = [
 ('/private/tmp/tripoder-framework.jar','/system/framework/framework.jar'),
 ('/private/tmp/tripoder-api37-framework-connectivity.jar','/apex/com.android.tethering/javalib/framework-connectivity.jar'),
 ('/private/tmp/tripoder-api37-framework-connectivity-b.jar','/apex/com.android.tethering/javalib/framework-connectivity-b.jar'),
 ('/private/tmp/tripoder-api37-framework-connectivity-t.jar','/apex/com.android.tethering/javalib/framework-connectivity-t.jar'),
 ('/private/tmp/tripoder-api37-framework-wifi.jar','/apex/com.android.wifi/javalib/framework-wifi.jar'),
 ('/private/tmp/tripoder-framework-scheduling.jar','/apex/com.android.scheduling/javalib/framework-scheduling.jar'),
 ('/private/tmp/tripoder-framework-gc-oracle/core-oj.jar','/apex/com.android.art/javalib/core-oj.jar'),
 ('/private/tmp/tripoder-framework-gc-oracle/core-libart.jar','/apex/com.android.art/javalib/core-libart.jar'),
]
def sha(data): return hashlib.sha256(data).hexdigest()
class Dex:
 def __init__(self,data):
  self.d=data
  assert data[:8] == b'dex\n039\x00', 'DEX_VERSION_UNSUPPORTED'
  assert self.u32(32)==len(data) and self.u32(36)==112 and self.u32(40)==0x12345678
  assert data[12:32]==hashlib.sha1(data[32:]).digest(), 'DEX_SIGNATURE'
  assert self.u32(8)==zlib.adler32(data[12:])&0xffffffff, 'DEX_CHECKSUM'
  self.strings=[]
  for i in range(self.u32(56)):
   p=self.u32(self.u32(60)+4*i);_,p=self.uleb(p);end=data.index(b'\0',p)
   self.strings.append(data[p:end].replace(b'\xc0\x80',b'\0').decode('utf-8','surrogatepass'))
  self.types=[self.strings[self.u32(self.u32(68)+4*i)] for i in range(self.u32(64))]
  self.protos=[]
  for i in range(self.u32(72)):
   p=self.u32(76)+12*i; ret=self.types[self.u32(p+4)];args=self.u32(p+8)
   params='' if not args else ''.join(self.types[self.u16(args+4+2*j)] for j in range(self.u32(args)))
   self.protos.append('('+params+')'+ret)
  self.methodids=[]
  for i in range(self.u32(88)):
   p=self.u32(92)+8*i
   self.methodids.append((self.types[self.u16(p)],self.strings[self.u32(p+4)],self.protos[self.u16(p+2)]))
 def u16(self,p):return struct.unpack_from('<H',self.d,p)[0]
 def u32(self,p):return struct.unpack_from('<I',self.d,p)[0]
 def uleb(self,p):
  v=0
  for shift in range(0,35,7):
   b=self.d[p];p+=1;v|=(b&127)<<shift
   if b<128:return v,p
  raise ValueError('ULEB_OVERFLOW')
 def wanted(self,roots):
  result={}
  for i in range(self.u32(96)):
   off=self.u32(100)+32*i;desc=self.types[self.u32(off)]
   if desc not in roots:continue
   p=self.u32(off+24);methods=[];clinit=0
   if p:
    nums=[]
    for _ in range(4):n,p=self.uleb(p);nums.append(n)
    for _ in range(nums[0]+nums[1]):_,p=self.uleb(p);_,p=self.uleb(p)
    for kind,count in [('direct',nums[2]),('virtual',nums[3])]:
     idx=0
     for _ in range(count):
      diff,p=self.uleb(p);idx+=diff;flags,p=self.uleb(p);code,p=self.uleb(p)
      owner,name,sig=self.methodids[idx];assert owner==desc
      if name=='<clinit>':clinit+=1;continue
      methods.append({'name':name,'descriptor':sig,'signature':name+sig,'dexAccessFlags':flags,'dexMethodIndex':idx,'kind':kind,'static':bool(flags&8),'constructor':name=='<init>'})
   methods.sort(key=lambda m:m['signature'])
   assert len({m['signature'] for m in methods})==len(methods),'DUPLICATE_DECLARATION'
   result[desc]={'methods':methods,'classDefIndex':i,'excludedClinitCount':clinit}
  return result

def from_dexdump(file,roots):
 proc=subprocess.Popen([str(DEXDUMP),str(file)],stdout=subprocess.PIPE,text=True,errors='replace')
 found={};owner=None;inmethods=False;current={}
 def finish():
  if owner in roots and current.get('name') and current.get('descriptor') and current['name']!='<clinit>':found[owner].append(current['name']+current['descriptor'])
 for l in proc.stdout:
  if l.startswith('Class #'):finish();owner=None;inmethods=False;current={}
  if l.startswith('  Class descriptor'):
   owner=l.split("'")[1]
   if owner in roots:found[owner]=[]
  if l.strip() in ('Direct methods    -','Virtual methods   -'):finish();current={};inmethods=True
  if owner in roots and inmethods:
   if re.match(r'    #\d+\s+:',l):finish();current={}
   m=re.match(r"      (name|type)\s+: '(.*)'",l)
   if m:current['name' if m[1]=='name' else 'descriptor']=m[2]
 finish();assert proc.wait()==0
 return {k:sorted(v) for k,v in found.items()}

def main():
 src=ROOT/'android/self-hook/native/art_framework.c';raw=src.read_bytes();text=raw.decode()
 names=re.findall(r'"([^"]+)"',text.split('static const char *names[]={',1)[1].split('};',1)[0]);assert len(names)==55
 roots={'L'+n+';' for n in names};matches={};sources=[];missing_sources=[]
 for local,device in SOURCES:
  path=Path(local)
  if not path.exists():missing_sources.append({'localPath':local,'expectedDevicePath':device});continue
  data=path.read_bytes();jar_sha=sha(data);jar={'localPath':local,'devicePath':device,'sha256':jar_sha,'size':len(data),'deviceShaVerifiedThisRun':False,'dexFiles':[]}
  with zipfile.ZipFile(path) as z:
   for name in z.namelist():
    if not re.fullmatch(r'classes(?:\d+)?\.dex',name):continue
    d=z.read(name);dex=Dex(d);selected=dex.wanted(roots);target=OUT/'dex'/path.stem/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(d)
    sdk=from_dexdump(target,roots);assert set(sdk)==set(selected),(local,name,'CLASS_SET_MISMATCH')
    for desc,value in selected.items():
     sigs=[m['signature'] for m in value['methods']];assert sigs==sdk[desc],(desc,'SDK_DEXDUMP_MISMATCH')
     value['methodMetadata']=value.pop('methods');value['methods']=sigs
     value.update(sourceJar=local,sourceJarSha256=jar_sha,devicePath=device,dexEntry=name,dexSha256=sha(d),sha256=sha(('\n'.join(sigs)+'\n').encode()),sdkDexdumpMatched=True)
     if desc in matches:raise ValueError('DUPLICATE_CLASS_DEFINITION:'+desc)
     matches[desc]=value
    jar['dexFiles'].append({'entry':name,'sha256':sha(d),'size':len(d),'headerSha1':d[12:32].hex(),'headerAdler32':format(dex.u32(8),'08x'),'extractedPath':str(target),'matchedRootCount':len(selected)})
  sources.append(jar)
 result={'schema':1,'independentOracle':'raw DEX class_def/class_data/method_ids/proto_ids plus Android SDK dexdump cross-check','excludes':['<clinit>','inherited methods','ART copied/default/runtime methods'],'reflectionUsed':False,'productionEnumeratorUsed':False,'rootSource':str(src),'rootSourceSha256':sha(raw),'rootNamesSha256':sha(('\n'.join(names)+'\n').encode()),'rootCount':55,'foundRootCount':len(matches),'complete':len(matches)==55,'parserPath':str(Path(__file__)),'parserSha256':sha(Path(__file__).read_bytes()),'dexdumpPath':str(DEXDUMP),'dexdumpSha256':sha(DEXDUMP.read_bytes()),'jars':sources,'missingSources':missing_sources,'missingClasses':[],'roots':[]}
 for index,name in enumerate(names):
  desc='L'+name+';';record={'owner':index,'name':name.replace('/','.'),'descriptor':desc,'found':desc in matches}
  if desc in matches:record.update(matches[desc])
  else:result['missingClasses'].append(record.copy())
  result['roots'].append(record)
 result['totalDeclaredMethods']=sum(len(r.get('methods',[])) for r in result['roots'])
 boot=OUT/'bootclasspath.txt';remote=boot.read_text().strip().split(':');assert all(j['devicePath'] in remote for j in sources),'JAR_NOT_IN_DEVICE_BOOTCLASSPATH'
 result['bootclasspathPath']=str(boot);result['bootclasspathSha256']=sha(boot.read_bytes());result['bootclasspath']=remote
 result['signatureFormat']='method-name + JVM method descriptor; constructors use <init>; one declaration per line, sorted, final newline'
 result['note']='JAR/DEX content hashes are computed from local artifacts. Device files were not accessed in this run; bind these hashes to the tested device before acceptance.'
 out=OUT/'framework-root-dex-oracle.json';out.write_text(json.dumps(result,ensure_ascii=True,separators=(',',':'))+'\n')
 lines=['owner\tclass\tcount\tsignatureSha256\tjar\tdex']
 for r in result['roots']:lines.append('\t'.join(map(str,[r['owner'],r['name'],len(r.get('methods',[])),r.get('sha256','MISSING'),r.get('sourceJar',''),r.get('dexEntry','')])))
 (OUT/'summary.tsv').write_text('\n'.join(lines)+'\n')
 print(json.dumps({'output':str(out),'sha256':sha(out.read_bytes()),'roots':55,'found':len(matches),'methods':result['totalDeclaredMethods'],'missing':result['missingClasses'],'sdkDexdumpMatched':True}))
if __name__=='__main__':main()
