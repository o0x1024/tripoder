#!/usr/bin/env node
// Explicit, bounded diagnosis: stop proof -> THICK -> one new launch. No replay.
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {randomUUID} from 'node:crypto';
import {loadConfig,Transport,MCP_VERSION} from '../agent/transport.mjs';
import {pullFiles} from '../agent/file-pull.mjs';
import {command} from '../agent/tools.mjs';
const [connection,output,execute,targetJson]=process.argv.slice(2);
if(!connection||!output||execute!=='--execute'||!targetJson)throw Error('Usage: investigate-thick-start.mjs CONNECTION OUTPUT_DIRECTORY --execute EXACT_TARGET_JSON');
const root=resolve(output);await mkdir(root,{recursive:true});
const t=new Transport(await loadConfig(resolve(connection)));
const target=JSON.parse(targetJson);
command('application_query',{action:'manifest',target,cursor:0,limit:1});
if(target.package!=='cmb.pb')throw Error('THIS_DIAGNOSIS_IS_CMB_ONLY');
const run='thick-diagnostic-'+randomUUID(),evidence={run,target,scope:'one deliberate THICK start after explicit stop proof; no business actions',calls:[]};
const save=()=>writeFile(resolve(root,'thick-start.json'),JSON.stringify(evidence,null,2)+'\n');
const call=async(name,args)=>{const result=await t.tool(name,args);evidence.calls.push({tool:name,arguments:args,receipt:result});await save();return result;};
const required=result=>{if(!result.ok)throw Error('DIAGNOSIS_STOPPED:'+String(result.errorCode??result.error??result.state));return result.result;};
try{
 await t.rpc({jsonrpc:'2.0',id:1,method:'initialize',params:{protocolVersion:MCP_VERSION}});await t.rpc({jsonrpc:'2.0',method:'notifications/initialized'});
 for(const operationId of ['cmb-start-1','cmb-start-2']){
  required(await call('operation_get',{operationId}));
  if(target.space==='p6-20261014'&&target.installGeneration==='9cd40a21-29b0-4cdc-b763-063c1f38711e')required(await call('application_query',{action:'launch_recovery',operationId,target}));
  else evidence.originalRecoveryDeferred='ORIGINAL_INSTALLATION_REPLACED: new target is an independent diagnosis, not replay or reconciliation of old launches';
 }
 const manifest=required(await call('application_query',{action:'manifest',target,cursor:0,limit:1}));
 if(manifest.parts?.length!==1||manifest.parts[0].sha256!=='d01869749503636d6c776fe6c1c3f5eac945bbdaf38e43167cfa87305c1cfe1d')throw Error('CMB_APK_IDENTITY_CHANGED');
 const before=required(await call('application_query',{action:'process_history',target,cursor:0,limit:64}));
 const stop=required(await call('application_stop',{operationId:run+'-stop',target}));evidence.stop=stop;
 // A successful explicit stop receipt is required; targets=[] alone is insufficient.
 const stopReceipt=required(await call('operation_get',{operationId:run+'-stop'}));
 if(stopReceipt.state!=='SUCCEEDED')throw Error('STOP_OUTCOME_NOT_CONFIRMED');
 const launch=required(await call('application_query',{action:'launch_targets',target}));
 if(launch.mode!=='thick')required(await call('application_manage',{action:'set_mode',operationId:run+'-mode',target,mode:'thick'}));
 const probes=required(await call('probes_query',{action:'configuration',target}));
 evidence.launchOperationId=run+'-launch';
 const start=await call('process_start',{operationId:evidence.launchOperationId,target,component:'cmb.pb.app.mainframe.container.PBMainActivity',probeRevision:probes.revision});
 evidence.startSucceeded=start.ok===true;
 required(await call('operation_get',{operationId:evidence.launchOperationId}));
 if(!start.ok)required(await call('application_query',{action:'launch_recovery',operationId:evidence.launchOperationId,target}));
 const after=required(await call('application_query',{action:'process_history',target,cursor:0,limit:64}));
 const known=new Set(before.processes.map(row=>row.processGeneration));
 evidence.newProcesses=after.processes.filter(row=>!known.has(row.processGeneration));
 evidence.nativeFaults=[];
 for(const row of evidence.newProcesses){
  const source={kind:'path',path:'/sdcard/Android/data/dev.tripoder.cmbqa/files/native-exit-'+row.processGeneration+'.txt'};
  try{
   const destination=resolve(root,'native-exit-'+row.processGeneration+'.txt');
   await pullFiles(t,{operationId:run+'-trace-'+row.processGeneration.slice(0,8),source,destination,followSymlinks:false});
   const text=await readFile(destination,'utf8'),fault=text.match(/FATAL signal=(\d+) code=(-?\d+) address=(\S+)/),registers=text.match(/fatal status=\d+ pid=(\d+) tid=(\d+) pc=([a-f0-9]+) lr=([a-f0-9]+)/);
   const summary={processGeneration:row.processGeneration,...(fault?{signal:Number(fault[1]),code:Number(fault[2]),address:fault[3]}:{}),...(registers?{pid:Number(registers[1]),tid:Number(registers[2]),pc:registers[3],lr:registers[4]}:{})};
   if(registers){const pc=BigInt('0x'+registers[3]);for(const line of text.split('\n')){
    const map=line.match(/^([a-f0-9]+)-([a-f0-9]+)\s+\S+\s+([a-f0-9]+)\s+\S+\s+\d+\s+(.*)$/);if(map&&pc>=BigInt('0x'+map[1])&&pc<BigInt('0x'+map[2]))summary.module={name:map[4].split('/').at(-1),fileOffset:'0x'+(pc-BigInt('0x'+map[1])+BigInt('0x'+map[3])).toString(16)};
   }}
   evidence.nativeFaults.push(summary);
  }catch(error){evidence.nativeFaults.push({processGeneration:row.processGeneration,state:'UNAVAILABLE',reason:String(error.message).slice(0,180)});}
 }
 for(const operationId of ['cmb-start-1','cmb-start-2'])required(await call('operation_get',{operationId}));
 evidence.state=start.ok?'THICK_STARTED_NOT_BUSINESS_ACCEPTED':'THICK_START_FAILED';await save();
 console.log(JSON.stringify({state:evidence.state,launchOperationId:evidence.launchOperationId,newProcesses:evidence.newProcesses,nativeFaults:evidence.nativeFaults}));
}catch(error){evidence.state='DIAGNOSIS_STOPPED';evidence.error=String(error.message);await save();throw error;}
finally{await t.close();}
