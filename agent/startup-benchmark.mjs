/** Process-cold launch measurements through the real MCP transport and Android foreground task. */
import {spawn, execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {createInterface} from 'node:readline';
import {readFile, writeFile, mkdir} from 'node:fs/promises';
import {resolve, join} from 'node:path';
import {randomUUID, createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const options=Object.fromEntries(process.argv.slice(2).reduce((out,key,i,args)=>i%2?out:[...out,[key,args[i+1]]],[]));
const config=JSON.parse(await readFile(options['--input'],'utf8')), directory=resolve(options['--evidence']);
const serial=options['--serial'], host=options['--host-package'], adb=options['--adb']||'adb', rounds=Number(options['--rounds']||3);
assert.match(serial,/^[-A-Za-z0-9_.:]+$/);assert.match(host,/^[A-Za-z0-9_.]+$/);assert.ok(rounds>=1&&rounds<=20);
await mkdir(directory,{recursive:true});
const shell=promisify(execFile), pending=new Map(), log=[], samples=[];let sequence=0, stderr='';
const child=spawn(process.execPath,[resolve('agent/server.mjs'),'--serial',serial,'--device-port',options['--device-port']||'29732'],{stdio:['pipe','pipe','pipe']});
child.stderr.on('data',b=>stderr=(stderr+b).slice(-16384));
createInterface({input:child.stdout}).on('line',line=>{const value=JSON.parse(line),item=pending.get(value.id);if(item){clearTimeout(item.timer);pending.delete(value.id);item.resolve(value);}});
child.on('exit',code=>{for(const item of pending.values())item.reject(Error('MCP_EXIT:'+code+':'+stderr));pending.clear();});
async function rpc(method,params={}){const id=++sequence;const p=new Promise((resolve,reject)=>{const timer=setTimeout(()=>{pending.delete(id);reject(Error('MCP_TIMEOUT:'+method));},params.name==='process_start'?75000:35000);pending.set(id,{resolve,reject,timer});});child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');const response=await p;if(response.error)throw Error(JSON.stringify(response.error));return response.result;}
async function tool(name,args={},required=true){const response=await rpc('tools/call',{name,arguments:args}),receipt=JSON.parse(response.content[0].text);if(required&&!receipt.ok)throw Object.assign(Error(name+':'+receipt.error),{receipt});return required?receipt.result:receipt;}
const mutate=(name,args)=>tool(name,{operationId:randomUUID(),...args});
const command=(...args)=>shell(adb,['-s',serial,...args],{maxBuffer:4*1024*1024});
const install={space:config.space,package:config.package,installGeneration:config.installGeneration};
const activities=config.expectedActivities||[config.expectedActivity];
const capture=spawn(adb,['-s',serial,'logcat','-v','epoch','-T','1','TripoderBoot:I','TripoderSelfHook:I','TripoderStartup:E','TripoderHook:I','TripoderBenchmark:I','ActivityTaskManager:I','Choreographer:I','*:S'],{stdio:['ignore','pipe','ignore']});
createInterface({input:capture.stdout}).on('line',line=>{
 if(/TripoderBoot.*(?:runtime mode=|\w+ \+\d+ms|SLOT_PREWARMED|APPLICATION_ATTACH|APPLICATION_READY|ApkStore.get slow)/.test(line)
   ||/TripoderSelfHook.*(?:INSTALLED:|READY:|FIXED_PUBLICATION:|EXECUTION_POLICY:|PREPARATION_METRICS:)/.test(line)
   ||line.includes('TripoderHook')&&line.includes('STATUS_TIMING:')
   ||line.includes('TripoderBenchmark')||line.includes('Choreographer')||line.includes('ActivityTaskManager')&&line.includes('Displayed '+host+'/'))log.push(line);
});
const report={schema:1,label:options['--label'],serial,hostPackage:host,installation:install,mode:'thick',authorityCold:options['--authority-cold']==='true',clock:'host performance.now; device epoch for stage durations',samples};
try{
 await rpc('initialize',{protocolVersion:'2025-03-26',capabilities:{},clientInfo:{name:'process-cold-startup-benchmark',version:'1'}});
 child.stdin.write(JSON.stringify({jsonrpc:'2.0',method:'notifications/initialized'})+'\n');
 for(let attempt=0;;attempt++){const status=await tool('runtime_status',{},false);if(status.ok)break;if(attempt===9)throw Error('ENDPOINT_NOT_READY:'+status.error);await new Promise(resolve=>setTimeout(resolve,500));}
 const plan=await tool('probes_get',{target:install});assert.equal(plan.configuration.enabled,false,'benchmark requires probes off');
 const original=(await tool('launch_entries_list',{target:install})).mode;report.originalMode=original;
 await mutate('application_stop',{target:install});if(original!=='thick')await mutate('application_mode_set',{target:install,mode:'thick'});
 const paths=(await command('shell','pm','path',host)).stdout.trim().split('\n');
 const path=paths.find(p=>p.startsWith('package:')&&p.endsWith('/base.apk')).slice(8);
 report.hostApkSha256=(await command('shell','sha256sum',path)).stdout.split(/\s+/)[0];
 report.deviceBuild=(await command('shell','getprop','ro.build.fingerprint')).stdout.trim();
 report.inputSha256=createHash('sha256').update(await readFile(options['--input'])).digest('hex');
 for(let index=0;index<rounds;index++){
  if(report.authorityCold){
   await command('shell','am','force-stop',host);await command('shell','am','start','-W','-n',host+'/dev.tripoder.hooklab.WorkbenchActivity');
   for(let attempt=0;;attempt++){const status=await tool('runtime_status',{},false);if(status.ok)break;if(attempt===9)throw Error('ENDPOINT_NOT_READY:'+status.error);await new Promise(resolve=>setTimeout(resolve,500));}
  }
  await mutate('application_stop',{target:install});
  await command('shell','input','keyevent','KEYCODE_WAKEUP');
  await command('shell','am','start','-W','-n',host+'/dev.tripoder.hooklab.WorkbenchActivity');
  const marker=randomUUID();await command('shell','log','-t','TripoderBenchmark',marker);
  const sample={index,marker,startedAt:new Date().toISOString(),ready:false};samples.push(sample);
  const started=performance.now();
  try{
   const process=await mutate('process_start',{target:install,component:'',probeRevision:plan.revision});
   sample.dispatchMs=Math.round(performance.now()-started);sample.pid=process.pid;sample.processGeneration=process.processGeneration;
   const target={...install,processGeneration:process.processGeneration};
   const deadline=started+90000;
   while(performance.now()<deadline){
    const ui=await tool('ui_dump_hierarchy',{target,includeInvisible:false},false);
    if(!ui.ok){sample.observationError=ui.error;if(ui.operationId)sample.observationReconciled=await tool('operation_get',{operationId:ui.operationId},false);break;}
    const shown=ui.result.activities.filter(a=>activities.includes(a.activity)&&a.nodes.some(n=>n.visible));
    if(shown.length){
     const dump=(await command('shell','dumpsys','activity','activities')).stdout;
     const task=dump.split('\n').find(line=>line.includes('topResumedActivity=')&&line.includes(host+'/dev.tripoder.runtime.')&&shown.some(a=>new RegExp('\\bt'+a.taskId+'\\b').test(line)));
     if(task){sample.foregroundObservedMs=Math.round(performance.now()-started);sample.activities=shown.map(a=>a.activity);sample.ready=true;break;}
    }
    await new Promise(resolve=>setTimeout(resolve,250));
   }
   if(sample.ready){const status=await tool('hook_status',{target},false);if(status.ok){sample.executionPolicy=status.result.executionPolicy;sample.fixedBindings=status.result.executionPolicy.fixedBindings;sample.identity=status.result.identity;}else{sample.statusError=status.error;if(status.operationId)sample.statusReconciled=await tool('operation_get',{operationId:status.operationId},false);}}
  }catch(error){sample.error=String(error);if(error.receipt){sample.receipt=error.receipt;if(error.receipt.operationId)sample.reconciled=await tool('operation_get',{operationId:error.receipt.operationId},false);}}
  sample.elapsedMs=Math.round(performance.now()-started);
  await writeFile(join(directory,'summary.json'),JSON.stringify(report,null,2));await writeFile(join(directory,'stages.log'),log.join('\n')+'\n');
  console.log(JSON.stringify({phase:'sample',label:report.label,index,ready:sample.ready,dispatchMs:sample.dispatchMs,foregroundObservedMs:sample.foregroundObservedMs,error:sample.error||sample.observationError||sample.statusError}));
 }
}catch(error){report.error=String(error);process.exitCode=1;}
finally{
 capture.kill();child.stdin.end();
 await writeFile(join(directory,'summary.json'),JSON.stringify(report,null,2));await writeFile(join(directory,'stages.log'),log.join('\n')+'\n');
 console.log(JSON.stringify({phase:'complete',label:report.label,ready:samples.filter(s=>s.ready).length,rounds:samples.length,error:report.error}));
}
