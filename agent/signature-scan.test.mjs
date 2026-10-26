import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,readFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {parseRuleset,scanBuffer,importRuleset,listRulesets,scanTarget,exportEvidence} from './signature-scan.mjs';

const RULES=`
rule Text_And_Hex : demo { meta: author = "qa" severity = 3
  strings: $a = "hello" nocase  $b = { 4D 5A ?? 00 [1-2] FF }  $w = "hi" wide
  condition: $a and ($b or $w) }
rule Count_Rule { strings: $x = "ab" condition: #x >= 3 }
rule Of_Rule { strings: $p1 = "foo" $p2 = "bar" $q = /ba[rz]+/i condition: 2 of ($p*) and not $q }
rule Word { strings: $k = "key" fullword condition: any of them }
`;

test('YARA subset: text/nocase/wide, hex wildcards and jumps, counts, of-sets, fullword',()=>{
  const rules=parseRuleset(RULES);
  assert.deepEqual(rules.map(r=>r.name),['Text_And_Hex','Count_Rule','Of_Rule','Word']);
  const buf=Buffer.concat([Buffer.from('xxHeLLo-'),Buffer.from([0x4d,0x5a,0x01,0x00,0x07,0xff]),Buffer.from('abab-ab key keys')]);
  const hits=Object.fromEntries(scanBuffer(rules,buf).map(h=>[h.rule,h]));
  assert.equal(hits.Text_And_Hex.strings.find(s=>s.id==='$a').matches[0].offset,2);
  assert.deepEqual(hits.Text_And_Hex.strings.find(s=>s.id==='$b').matches[0],{offset:8,length:6});
  assert.equal(hits.Count_Rule.strings[0].count,3);
  assert.equal(hits.Word.strings[0].count,1);
  assert.equal(hits.Of_Rule,undefined);
  const wide=scanBuffer(rules,Buffer.concat([Buffer.from('hello '),Buffer.from('hi','utf16le')]));
  assert.equal(wide[0].rule,'Text_And_Hex');
});

test('unsupported or malformed syntax is rejected instead of ignored',()=>{
  for(const [src,code] of [
    ['import "pe" rule a { condition: true }','YARA_UNSUPPORTED_SYNTAX'],
    ['rule a { strings: $a = "x" condition: $a at 0 }','YARA_UNSUPPORTED_SYNTAX'],
    ['rule a { condition: filesize < 10 }','YARA_UNSUPPORTED_SYNTAX'],
    ['rule a { condition: pe.is_dll() }','YARA_UNSUPPORTED_SYNTAX'],
    ['rule a { strings: $a = { ?? ?? [2] 01 } condition: $a }','YARA_BAD_HEX'],
    ['rule a { condition: $missing }','YARA_UNDEFINED_STRING'],
    ['rule a { condition: true } rule a { condition: true }','YARA_DUPLICATE_RULE'],
  ])assert.throws(()=>parseRuleset(src),e=>e.code===code,src);
});

test('ruleset import/list/scan of a ZIP and IOC export follow the caller mapping',()=>{
  const dir=mkdtempSync(join(tmpdir(),'sig-'));process.env.TRIPODER_RULESET_DIR=join(dir,'rulesets');
  writeFileSync(join(dir,'r.yar'),'rule Marker { meta: id = "R-1" strings: $m = "SECRET_MARKER" condition: $m }');
  assert.equal(importRuleset({name:'demo',path:join(dir,'r.yar')}).ruleCount,1);
  assert.equal(listRulesets().rulesets[0].name,'demo');
  // Minimal stored-method ZIP with one entry containing the marker at offset 4.
  const name=Buffer.from('a.txt'),data=Buffer.from('xxxxSECRET_MARKER see https://evil.example/c2');
  const local=Buffer.alloc(30);local.writeUInt32LE(0x04034b50,0);local.writeUInt32LE(data.length,18);local.writeUInt32LE(data.length,22);local.writeUInt16LE(name.length,26);
  const central=Buffer.alloc(46);central.writeUInt32LE(0x02014b50,0);central.writeUInt32LE(data.length,20);central.writeUInt32LE(data.length,24);central.writeUInt16LE(name.length,28);
  const cdOffset=30+name.length+data.length;
  const end=Buffer.alloc(22);end.writeUInt32LE(0x06054b50,0);end.writeUInt16LE(1,8);end.writeUInt16LE(1,10);end.writeUInt32LE(46+name.length,12);end.writeUInt32LE(cdOffset,16);
  writeFileSync(join(dir,'t.apk'),Buffer.concat([local,name,data,central,name,end]));
  const result=scanTarget({ruleset:'demo',path:join(dir,'t.apk'),maxMatches:10});
  const entry=result.matches.find(m=>m.entry==='a.txt');
  assert.equal(entry.rule,'Marker');assert.equal(entry.strings[0].matches[0].offset,4);
  assert.ok(result.matches.some(m=>m.entry===''));
  writeFileSync(join(dir,'scan.json'),JSON.stringify({ok:true,result}));
  const out=exportEvidence({sourcePath:join(dir,'scan.json'),recordsPath:'result.matches',mapping:{rule_id:'meta.id',name:'rule',where:'entry',offset:'strings.0.matches.0.offset'},extract:['url','domain'],format:'json',title:'t',destination:join(dir,'ioc.json')});
  const report=JSON.parse(readFileSync(join(dir,'ioc.json'),'utf8'));
  assert.deepEqual(report.fields,['rule_id','name','where','offset']);
  assert.deepEqual(report.records.find(r=>r.where==='a.txt'),{rule_id:'R-1',name:'Marker',where:'a.txt',offset:4});
  assert.equal(out.recordCount,report.records.length);
  assert.throws(()=>exportEvidence({sourcePath:join(dir,'scan.json'),recordsPath:'',mapping:{a:'b'},extract:[],format:'csv',title:'',destination:join(dir,'ioc.json')}),e=>e.code==='DESTINATION_EXISTS');
});
