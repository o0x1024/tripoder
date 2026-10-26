// Computer-side signature scanning (YARA subset) and IOC/report export for authorized analysis.
// Runs in the stdio adapter only. Rulesets are imported assets stored under ~/.tripoder/rulesets.
import {createHash} from 'node:crypto';
import {readFileSync,writeFileSync,mkdirSync,readdirSync,statSync,renameSync,existsSync} from 'node:fs';
import {homedir} from 'node:os';
import {join,isAbsolute,dirname,relative} from 'node:path';
import {inflateRawSync} from 'node:zlib';

const MAX_RULESET_BYTES=1<<20,MAX_RULES=512,MAX_STRINGS=64;
const MAX_TARGET_BYTES=512*1024*1024,MAX_ENTRY_BYTES=128*1024*1024,MAX_HITS=16,MAX_FILES=4096;
export const rulesetRoot=()=>process.env.TRIPODER_RULESET_DIR||join(homedir(),'.tripoder','rulesets');
const fail=(code,detail)=>Object.assign(new Error(detail===undefined?code:code+':'+detail),{code});
const sha256=data=>createHash('sha256').update(data).digest('hex');

/* YARA subset grammar (anything outside it is rejected at import, never silently ignored):
 *   rule NAME [: tags] { [meta: key = "s" | n | true | false] strings: <defs> condition: <expr> }
 *   text   $id = "text" [nocase] [wide] [ascii] [fullword]
 *   hex    $id = { 4D 5A ?? ?0 [2] [1-4] }
 *   regex  $id = /pattern/[is]
 *   expr   and/or/not, ( ), true, false, $id, #id OP n, any|all|N of them, any|all|N of ($a, $b*)
 */
const KEYWORDS=new Set(['rule','meta','strings','condition','and','or','not','any','all','of','them','true','false','nocase','wide','ascii','fullword']);
const UNSUPPORTED=new Set(['import','include','private','global','at','in','filesize','entrypoint','for','xor','base64','base64wide','matches','contains','startswith','endswith','icontains','iequals']);

function tokenize(src){
  const out=[];let i=0;
  const prevIsAssign=()=>{const p=out[out.length-1];return p&&p.t==='op'&&p.v==='=';};
  while(i<src.length){
    const c=src[i],rest=src.slice(i);let m;
    if(/\s/.test(c)){i++;continue;}
    if(rest.startsWith('//')){while(i<src.length&&src[i]!=='\n')i++;continue;}
    if(rest.startsWith('/*')){const e=src.indexOf('*/',i+2);if(e<0)throw fail('YARA_UNTERMINATED_COMMENT');i=e+2;continue;}
    if(c==='"'){
      let j=i+1,s='';
      while(j<src.length&&src[j]!=='"'){
        if(src[j]!=='\\'){s+=src[j++];continue;}
        const n=src[j+1];
        if(n==='x'){s+=String.fromCharCode(parseInt(src.substr(j+2,2),16));j+=4;continue;}
        s+=n==='n'?'\n':n==='t'?'\t':n==='r'?'\r':n;j+=2;
      }
      if(j>=src.length)throw fail('YARA_UNTERMINATED_STRING');
      out.push({t:'str',v:s});i=j+1;continue;
    }
    if(c==='{'&&prevIsAssign()){const e=src.indexOf('}',i);if(e<0)throw fail('YARA_UNTERMINATED_HEX');out.push({t:'hex',v:src.slice(i+1,e)});i=e+1;continue;}
    if(c==='/'&&prevIsAssign()){
      let j=i+1,s='';
      while(j<src.length&&src[j]!=='/'){if(src[j]==='\\'){s+=src[j]+src[j+1];j+=2;}else s+=src[j++];}
      if(j>=src.length)throw fail('YARA_UNTERMINATED_REGEX');
      j++;let flags='';while(/[is]/.test(src[j]||''))flags+=src[j++];
      out.push({t:'re',v:s,f:flags});i=j;continue;
    }
    if((m=/^[$#][A-Za-z0-9_]*\*?/.exec(rest))){out.push({t:m[0][0]==='$'?'sid':'cnt',v:m[0].slice(1)});i+=m[0].length;continue;}
    if((m=/^(0x[0-9a-fA-F]+|\d+)(KB|MB)?/.exec(rest))){let n=Number(m[1]);if(m[2]==='KB')n*=1024;if(m[2]==='MB')n*=1048576;out.push({t:'num',v:n});i+=m[0].length;continue;}
    if((m=/^[A-Za-z_][A-Za-z0-9_.]*/.exec(rest))){
      const w=m[0];if(UNSUPPORTED.has(w)||w.includes('.'))throw fail('YARA_UNSUPPORTED_SYNTAX',w);
      out.push({t:KEYWORDS.has(w)?'kw':'id',v:w});i+=w.length;continue;
    }
    if((m=/^(==|!=|<=|>=|[{}():=,<>])/.exec(rest))){out.push({t:'op',v:m[0]});i+=m[0].length;continue;}
    throw fail('YARA_UNEXPECTED_CHARACTER',JSON.stringify(c));
  }
  return out;
}

// Hex string -> list of segments; each segment is a byte sequence of {value,mask} followed by an optional jump {min,max}.
function compileHex(body){
  const text=body.replace(/\s+/g,'');
  if(/[|()~]/.test(text))throw fail('YARA_UNSUPPORTED_SYNTAX','hex alternation or negation');
  const segments=[];let seq=[];let i=0;
  while(i<text.length){
    if(text[i]==='['){
      const e=text.indexOf(']',i);if(e<0)throw fail('YARA_BAD_HEX');
      const range=text.slice(i+1,e).split('-');const min=Number(range[0]),max=range.length>1?Number(range[1]):min;
      if(!Number.isInteger(min)||!Number.isInteger(max)||min<0||max<min||max>256||!seq.length)throw fail('YARA_BAD_HEX_JUMP');
      segments.push({seq,jump:{min,max}});seq=[];i=e+1;continue;
    }
    const hi=text[i],lo=text[i+1];
    if(lo===undefined||!/[0-9a-fA-F?]/.test(hi)||!/[0-9a-fA-F?]/.test(lo))throw fail('YARA_BAD_HEX');
    const nib=ch=>ch==='?'?[0,0]:[parseInt(ch,16),15];
    const [hv,hm]=nib(hi),[lv,lm]=nib(lo);
    seq.push({value:(hv<<4)|lv,mask:(hm<<4)|lm});i+=2;
  }
  if(!seq.length)throw fail('YARA_BAD_HEX');
  segments.push({seq,jump:null});
  if(segments[0].seq.every(b=>b.mask===0))throw fail('YARA_BAD_HEX','must start with a concrete byte');
  return segments;
}

function compileString(tok,modifiers){
  if(tok.t==='hex')return {kind:'hex',segments:compileHex(tok.v)};
  if(tok.t==='re'){
    try{return {kind:'regex',source:tok.v,flags:tok.f};}finally{new RegExp(tok.v,'g'+(tok.f.includes('i')?'i':'')+(tok.f.includes('s')?'s':''));}
  }
  if(!tok.v.length)throw fail('YARA_EMPTY_STRING');
  const ascii=modifiers.has('ascii')||!modifiers.has('wide');
  return {kind:'text',text:tok.v,nocase:modifiers.has('nocase'),wide:modifiers.has('wide'),ascii,fullword:modifiers.has('fullword')};
}

export function parseRuleset(source){
  if(Buffer.byteLength(source)>MAX_RULESET_BYTES)throw fail('YARA_RULESET_TOO_LARGE');
  const toks=tokenize(source);let p=0;
  const peek=()=>toks[p];
  const next=()=>{const t=toks[p++];if(!t)throw fail('YARA_UNEXPECTED_END');return t;};
  const expect=(t,v)=>{const k=next();if(k.t!==t||(v!==undefined&&k.v!==v))throw fail('YARA_EXPECTED',v??t);return k;};
  const is=(t,v)=>{const k=peek();return k&&k.t===t&&(v===undefined||k.v===v);};
  const rules=[];const names=new Set();
  while(p<toks.length){
    expect('kw','rule');const name=expect('id').v;
    if(names.has(name))throw fail('YARA_DUPLICATE_RULE',name);names.add(name);
    const tags=[];if(is('op',':')){next();while(is('id'))tags.push(next().v);}
    expect('op','{');
    const meta={},strings=[];let condition=null;
    if(is('kw','meta')){next();expect('op',':');
      while(is('id')){const key=next().v;expect('op','=');const v=next();
        if(v.t==='str'||v.t==='num')meta[key]=v.v;else if(v.t==='kw'&&(v.v==='true'||v.v==='false'))meta[key]=v.v==='true';else throw fail('YARA_BAD_META',key);}
    }
    if(is('kw','strings')){next();expect('op',':');
      while(is('sid')){
        const id=next().v;if(!id||id.endsWith('*'))throw fail('YARA_BAD_STRING_ID');
        if(strings.some(s=>s.id===id))throw fail('YARA_DUPLICATE_STRING',name+'.$'+id);
        expect('op','=');const value=next();if(!['str','hex','re'].includes(value.t))throw fail('YARA_BAD_STRING',id);
        const mods=new Set();while(is('kw')&&['nocase','wide','ascii','fullword'].includes(peek().v))mods.add(next().v);
        if(value.t!=='str'&&mods.size)throw fail('YARA_UNSUPPORTED_SYNTAX','modifiers on non-text string');
        strings.push({id,...compileString(value,mods)});
        if(strings.length>MAX_STRINGS)throw fail('YARA_TOO_MANY_STRINGS',name);
      }
    }
    expect('kw','condition');expect('op',':');
    condition=parseExpr();expect('op','}');
    for(const ref of refs(condition))if(!ref.endsWith('*')&&!strings.some(s=>s.id===ref))throw fail('YARA_UNDEFINED_STRING',name+'.$'+ref);
    rules.push({name,tags,meta,strings,condition});
    if(rules.length>MAX_RULES)throw fail('YARA_TOO_MANY_RULES');
  }
  if(!rules.length)throw fail('YARA_NO_RULES');
  return rules;

  function parseExpr(){let left=parseAnd();while(is('kw','or')){next();left={op:'or',a:left,b:parseAnd()};}return left;}
  function parseAnd(){let left=parseNot();while(is('kw','and')){next();left={op:'and',a:left,b:parseNot()};}return left;}
  function parseNot(){if(is('kw','not')){next();return {op:'not',a:parseNot()};}return parseAtom();}
  function parseAtom(){
    const t=next();
    if(t.t==='op'&&t.v==='('){const e=parseExpr();expect('op',')');return e;}
    if(t.t==='kw'&&(t.v==='true'||t.v==='false'))return {op:'const',v:t.v==='true'};
    if(t.t==='sid'){if(!t.v||t.v.endsWith('*'))throw fail('YARA_BAD_CONDITION');return {op:'str',id:t.v};}
    if(t.t==='cnt'){const cmp=next();if(cmp.t!=='op'||!['==','!=','<','<=','>','>='].includes(cmp.v))throw fail('YARA_BAD_CONDITION');return {op:'count',id:t.v,cmp:cmp.v,n:expect('num').v};}
    if((t.t==='kw'&&(t.v==='any'||t.v==='all'))||t.t==='num'){
      expect('kw','of');let set;
      if(is('kw','them')){next();set=['*'];}
      else{expect('op','(');set=[];do{set.push(expect('sid').v);}while(is('op',',')&&next());expect('op',')');}
      return {op:'of',q:t.t==='num'?t.v:t.v,set};
    }
    throw fail('YARA_BAD_CONDITION',t.v);
  }
}
function refs(node){
  if(!node)return [];
  if(node.op==='str'||node.op==='count')return [node.id];
  if(node.op==='of')return node.set.filter(s=>s!=='*');
  return [...refs(node.a),...refs(node.b)];
}

// ---------------------------------------------------------------- matching
const isWordByte=b=>(b>=48&&b<=57)||(b>=65&&b<=90)||(b>=97&&b<=122)||b===95;
function textNeedles(s){
  const variants=[];
  const enc=Buffer.from(s.text,'latin1');
  if(s.ascii)variants.push(enc);
  if(s.wide){const w=Buffer.alloc(enc.length*2);for(let i=0;i<enc.length;i++)w[i*2]=enc[i];variants.push(w);}
  return variants;
}
function findText(buf,s,limit){
  const hits=[];let total=0;
  for(const needle of textNeedles(s)){
    const hay=s.nocase?lower(buf):buf,pat=s.nocase?lower(needle):needle;
    let at=hay.indexOf(pat);
    while(at>=0){
      const end=at+pat.length;
      if(!s.fullword||((at===0||!isWordByte(buf[at-1]))&&(end>=buf.length||!isWordByte(buf[end])))){total++;if(hits.length<limit)hits.push({offset:at,length:pat.length});}
      at=hay.indexOf(pat,at+1);
    }
  }
  return {total,hits};
}
const lowerCache=new WeakMap();
function lower(buf){
  let v=lowerCache.get(buf);if(v)return v;
  v=Buffer.from(buf);for(let i=0;i<v.length;i++){const b=v[i];if(b>=65&&b<=90)v[i]=b+32;}
  lowerCache.set(buf,v);return v;
}
function matchSeq(buf,at,seq){if(at+seq.length>buf.length)return false;for(let i=0;i<seq.length;i++)if((buf[at+i]&seq[i].mask)!==seq[i].value)return false;return true;}
function matchSegments(buf,at,segments,k){
  const seg=segments[k];if(!matchSeq(buf,at,seg.seq))return -1;
  const end=at+seg.seq.length;if(!seg.jump)return end;
  for(let gap=seg.jump.min;gap<=seg.jump.max;gap++){const r=matchSegments(buf,end+gap,segments,k+1);if(r>=0)return r;}
  return -1;
}
function findHex(buf,s,limit){
  const hits=[];let total=0;const first=s.segments[0].seq;
  // Anchor on the first fully concrete byte of the first segment.
  const anchorIndex=first.findIndex(b=>b.mask===255);
  const anchor=anchorIndex>=0?first[anchorIndex].value:-1;
  for(let pos=anchorIndex>=0?buf.indexOf(anchor,anchorIndex):0;pos>=0&&pos<buf.length;pos=anchorIndex>=0?buf.indexOf(anchor,pos+1):pos+1){
    const start=pos-(anchorIndex>=0?anchorIndex:0);if(start<0)continue;
    const end=matchSegments(buf,start,s.segments,0);
    if(end>=0){total++;if(hits.length<limit)hits.push({offset:start,length:end-start});}
  }
  return {total,hits};
}
function findRegex(buf,s,limit){
  const hits=[];let total=0;
  const re=new RegExp(s.source,'g'+(s.flags.includes('i')?'i':'')+(s.flags.includes('s')?'s':''));
  const text=buf.toString('latin1');let m;
  while((m=re.exec(text))){total++;if(hits.length<limit)hits.push({offset:m.index,length:m[0].length});if(m[0].length===0)re.lastIndex++;if(total>100000)break;}
  return {total,hits};
}
function evaluate(node,found,ids){
  switch(node.op){
    case 'const':return node.v;
    case 'and':return evaluate(node.a,found,ids)&&evaluate(node.b,found,ids);
    case 'or':return evaluate(node.a,found,ids)||evaluate(node.b,found,ids);
    case 'not':return !evaluate(node.a,found,ids);
    case 'str':return (found[node.id]?.total??0)>0;
    case 'count':{const c=found[node.id]?.total??0,n=node.n;return {'==':c===n,'!=':c!==n,'<':c<n,'<=':c<=n,'>':c>n,'>=':c>=n}[node.cmp];}
    case 'of':{
      const members=new Set();
      for(const pat of node.set){if(pat==='*')ids.forEach(i=>members.add(i));else if(pat.endsWith('*'))ids.filter(i=>i.startsWith(pat.slice(0,-1))).forEach(i=>members.add(i));else members.add(pat);}
      const hit=[...members].filter(i=>(found[i]?.total??0)>0).length;
      return node.q==='any'?hit>=1:node.q==='all'?hit===members.size&&members.size>0:hit>=node.q;
    }
  }
  throw fail('YARA_BAD_CONDITION');
}
export function scanBuffer(rules,buf){
  const out=[];
  for(const rule of rules){
    const found={};
    for(const s of rule.strings)found[s.id]=s.kind==='text'?findText(buf,s,MAX_HITS):s.kind==='hex'?findHex(buf,s,MAX_HITS):findRegex(buf,s,MAX_HITS);
    if(evaluate(rule.condition,found,rule.strings.map(s=>s.id)))
      out.push({rule:rule.name,tags:rule.tags,meta:rule.meta,strings:rule.strings.filter(s=>found[s.id].total>0).map(s=>({id:'$'+s.id,count:found[s.id].total,matches:found[s.id].hits}))});
  }
  return out;
}

// ---------------------------------------------------------------- APK / ZIP entries
// Reads the central directory and yields stored/deflated entries; entry offsets in results are offsets within the
// decompressed entry, and the raw archive itself is scanned as entry "" with absolute file offsets.
function zipEntries(buf){
  let eocd=-1;for(let i=buf.length-22;i>=Math.max(0,buf.length-65557);i--)if(buf.readUInt32LE(i)===0x06054b50){eocd=i;break;}
  if(eocd<0)return null;
  const count=buf.readUInt16LE(eocd+10),cdOffset=buf.readUInt32LE(eocd+16);
  const entries=[];let p=cdOffset;
  for(let n=0;n<count&&p+46<=buf.length;n++){
    if(buf.readUInt32LE(p)!==0x02014b50)break;
    const method=buf.readUInt16LE(p+10),csize=buf.readUInt32LE(p+20),usize=buf.readUInt32LE(p+24);
    const nameLen=buf.readUInt16LE(p+28),extraLen=buf.readUInt16LE(p+30),commentLen=buf.readUInt16LE(p+32),local=buf.readUInt32LE(p+42);
    const name=buf.toString('utf8',p+46,p+46+nameLen);p+=46+nameLen+extraLen+commentLen;
    if(name.endsWith('/'))continue;
    entries.push({name,method,csize,usize,local});
  }
  return entries;
}
function readEntry(buf,e){
  if(e.local+30>buf.length||buf.readUInt32LE(e.local)!==0x04034b50)throw fail('ZIP_BAD_LOCAL_HEADER',e.name);
  const start=e.local+30+buf.readUInt16LE(e.local+26)+buf.readUInt16LE(e.local+28);
  const raw=buf.subarray(start,start+e.csize);
  if(e.usize>MAX_ENTRY_BYTES)throw fail('SCAN_ENTRY_TOO_LARGE',e.name);
  if(e.method===0)return raw;
  if(e.method===8)return inflateRawSync(raw,{maxOutputLength:MAX_ENTRY_BYTES});
  throw fail('ZIP_UNSUPPORTED_METHOD',e.name+':'+e.method);
}
function listFiles(path){
  const st=statSync(path);if(st.isFile())return [path];
  const out=[];const walk=dir=>{for(const name of readdirSync(dir).sort()){const full=join(dir,name),s=statSync(full);if(s.isDirectory())walk(full);else if(s.isFile()){out.push(full);if(out.length>MAX_FILES)throw fail('SCAN_TOO_MANY_FILES',MAX_FILES);}}};
  walk(path);return out;
}

// ---------------------------------------------------------------- ruleset store and public operations
const RULESET_NAME=/^[A-Za-z0-9_.-]{1,64}$/;
function storePath(name){if(!RULESET_NAME.test(name))throw fail('RULESET_NAME_INVALID');return join(rulesetRoot(),name+'.json');}
function atomicWrite(path,text){mkdirSync(dirname(path),{recursive:true});const tmp=path+'.tmp-'+process.pid;writeFileSync(tmp,text);renameSync(tmp,path);}

export function importRuleset({name,path}){
  if(!isAbsolute(path))throw fail('ABSOLUTE_PATH_REQUIRED');
  const source=readFileSync(path,'utf8');const rules=parseRuleset(source);
  const record={name,sourcePath:path,sourceSha256:sha256(source),importedAt:new Date().toISOString(),source,
    rules:rules.map(r=>({name:r.name,tags:r.tags,meta:r.meta,strings:r.strings.length}))};
  atomicWrite(storePath(name),JSON.stringify(record));
  return {name,sourceSha256:record.sourceSha256,ruleCount:rules.length,rules:record.rules};
}
export function listRulesets(){
  const root=rulesetRoot();if(!existsSync(root))return {rulesets:[]};
  const rulesets=readdirSync(root).filter(f=>f.endsWith('.json')).sort().map(f=>{const r=JSON.parse(readFileSync(join(root,f),'utf8'));return {name:r.name,sourceSha256:r.sourceSha256,importedAt:r.importedAt,ruleCount:r.rules.length,rules:r.rules.map(x=>x.name)};});
  return {rulesets};
}
function loadRuleset(name){
  const path=storePath(name);if(!existsSync(path))throw fail('RULESET_NOT_FOUND',name);
  const record=JSON.parse(readFileSync(path,'utf8'));
  if(sha256(record.source)!==record.sourceSha256)throw fail('RULESET_INTEGRITY_MISMATCH',name);
  return {record,rules:parseRuleset(record.source)};
}

/** Scan a computer-side file or directory. ZIP/APK files are scanned as raw bytes and per decompressed entry. */
export function scanTarget({ruleset,path,maxMatches=200}){
  if(!isAbsolute(path))throw fail('ABSOLUTE_PATH_REQUIRED');
  const {record,rules}=loadRuleset(ruleset);
  const matches=[],files=[];let scannedBytes=0,truncated=false;
  for(const file of listFiles(path)){
    const size=statSync(file).size;scannedBytes+=size;if(scannedBytes>MAX_TARGET_BYTES)throw fail('SCAN_TARGET_TOO_LARGE',MAX_TARGET_BYTES);
    const buf=readFileSync(file);const rel=relative(path,file)||'';
    const units=[{entry:'',data:buf}];const entries=zipEntries(buf);const skipped=[];
    if(entries)for(const e of entries){try{units.push({entry:e.name,data:readEntry(buf,e)});}catch(error){skipped.push({entry:e.name,reason:error.code??String(error.message)});}}
    files.push({file:rel,sha256:sha256(buf),bytes:size,archiveEntries:entries?entries.length:0,skippedEntries:skipped});
    for(const unit of units)for(const hit of scanBuffer(rules,unit.data)){
      if(matches.length>=maxMatches){truncated=true;break;}
      matches.push({file:rel,entry:unit.entry,...hit});
    }
  }
  return {ruleset:{name:record.name,sourceSha256:record.sourceSha256},target:path,files,matchCount:matches.length,truncated,matches};
}

// ---------------------------------------------------------------- evidence / IOC export
// Reads JSON evidence the agent already holds (a saved tool result, HAR, scan result ...), selects the record array
// at recordsPath, projects each record through the caller's field mapping and optionally extracts indicator values.
const EXTRACTORS={
  url:/\bhttps?:\/\/[^\s"'<>\\]{3,2048}/g,
  domain:/\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+(?:[a-z]{2,24})\b/gi,
  ipv4:/\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b/g,
  email:/\b[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\.[A-Za-z]{2,24}\b/g,
  sha256:/\b[a-fA-F0-9]{64}\b/g,
  md5:/\b[a-fA-F0-9]{32}\b/g,
};
function pick(value,path){
  if(path===''||path==='$')return value;
  let cur=value;
  for(const part of path.split('.')){
    if(cur==null)return undefined;
    if(part==='*'){if(!Array.isArray(cur))return undefined;continue;}
    if(Array.isArray(cur)&&!/^\d+$/.test(part))cur=cur.map(v=>v?.[part]).filter(v=>v!==undefined).flat();
    else cur=cur[part];
  }
  return cur;
}
function strings(value,out=[]){
  if(typeof value==='string')out.push(value);
  else if(Array.isArray(value))value.forEach(v=>strings(v,out));
  else if(value&&typeof value==='object')Object.values(value).forEach(v=>strings(v,out));
  return out;
}
function csvCell(v){const s=v==null?'':typeof v==='string'?v:JSON.stringify(v);return /[",\n\r]/.test(s)?'"'+s.replace(/"/g,'""')+'"':s;}

export function exportEvidence({sourcePath,recordsPath='',mapping,extract=[],format='json',destination,title=''}){
  if(!isAbsolute(sourcePath)||!isAbsolute(destination))throw fail('ABSOLUTE_PATH_REQUIRED');
  if(existsSync(destination))throw fail('DESTINATION_EXISTS');
  const source=readFileSync(sourcePath,'utf8');let parsed;
  try{parsed=JSON.parse(source);}catch{throw fail('EVIDENCE_SOURCE_NOT_JSON');}
  let records=pick(parsed,recordsPath);
  if(records===undefined)throw fail('EVIDENCE_RECORDS_PATH_NOT_FOUND',recordsPath);
  if(!Array.isArray(records))records=[records];
  const fields=Object.keys(mapping);if(!fields.length)throw fail('EVIDENCE_MAPPING_EMPTY');
  const rows=records.map(r=>Object.fromEntries(fields.map(f=>[f,pick(r,mapping[f])??null])));
  const indicators=[];
  for(const kind of extract){
    const re=EXTRACTORS[kind];if(!re)throw fail('EVIDENCE_EXTRACTOR_UNKNOWN',kind);
    const seen=new Set();
    for(const text of strings(records))for(const m of text.matchAll(re)){const v=kind==='domain'||kind==='email'?m[0].toLowerCase():m[0];if(!seen.has(v)){seen.add(v);indicators.push({type:kind,value:v});}}
  }
  const sourceSha256=sha256(source);
  let body;
  if(format==='csv')body=[fields.map(csvCell).join(','),...rows.map(r=>fields.map(f=>csvCell(r[f])).join(','))].join('\n')+'\n';
  else body=JSON.stringify({title,generatedAt:new Date().toISOString(),source:{path:sourcePath,sha256:sourceSha256,recordsPath},fields,records:rows,indicators},null,2)+'\n';
  atomicWrite(destination,body);
  return {destination,format,sha256:sha256(body),bytes:Buffer.byteLength(body),recordCount:rows.length,fields,indicatorCount:indicators.length,
    preview:{records:rows.slice(0,5),indicators:indicators.slice(0,20)},source:{sha256:sourceSha256}};
}
