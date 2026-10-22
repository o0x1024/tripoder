import {operationCall,operationSchema} from './tools.mjs';
/** Physical-device acceptance. This program never imports or invokes ADB. */
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {mkdir,writeFile,readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {randomUUID,createHash} from 'node:crypto';
import https from 'node:https';
import assert from 'node:assert/strict';
import {loadConfig,MCP_VERSION} from './transport.mjs';
const options=Object.fromEntries(process.argv.slice(2).reduce((a,k,i,args)=>i%2?a:[...a,[k,args[i+1]]],[]));
if(!options['--connection']||!options['--evidence']||!options['--fixture'])throw Error('Usage: network-acceptance.mjs --connection PATH --evidence DIRECTORY --fixture APK');
const config=await loadConfig(resolve(options['--connection'])),directory=resolve(options['--evidence']);await mkdir(directory,{recursive:true});
const checks=[],events=[];const report={passed:false,scope:'physical-device-stdio-and-native-http-owned-hook',adbUsedForMcp:false,checks};
let sequence=0;
class NativeHttp {
  session=null;
  async rpc(method,params={},notification=false){
    const id=notification?undefined:++sequence,body=Buffer.from(JSON.stringify({jsonrpc:'2.0',...(notification?{}:{id}),method,params}));
    return new Promise((resolve,reject)=>{
      const headers={authorization:'Bearer '+config.token,accept:'application/json, text/event-stream','content-type':'application/json','content-length':body.length,...(this.session?{'mcp-session-id':this.session,'mcp-protocol-version':MCP_VERSION}:{})};
      const req=https.request(config.url,{method:'POST',ca:config.certificatePem,minVersion:'TLSv1.3',headers,agent:false},res=>{
        const chunks=[];res.on('data',c=>chunks.push(c));res.on('aborted',()=>reject(Error('HTTP_INTERRUPTED')));res.on('end',()=>{try{if(notification){assert.equal(res.statusCode,202);resolve(null);return;}assert.equal(res.statusCode,200,'HTTP '+res.statusCode);const value=JSON.parse(Buffer.concat(chunks));assert.equal(value.id,id);if(method==='initialize')this.session=res.headers['mcp-session-id'];if(value.error)throw Error(value.error.message);resolve(value.result);}catch(e){reject(e);}});
      });req.setTimeout(75000,()=>req.destroy(Error('HTTP_TIMEOUT_OUTCOME_UNKNOWN')));req.on('error',reject);req.end(body);
    });
  }
}
class Stdio {
  constructor(){
    this.pending=new Map();this.stderr='';this.child=spawn(process.execPath,[resolve('agent/server.mjs'),'--connection',resolve(options['--connection'])],{env:{...process.env,PATH:'/nonexistent'},stdio:['pipe','pipe','pipe']});
    this.child.stderr.on('data',b=>this.stderr+=b);createInterface({input:this.child.stdout}).on('line',line=>{const value=JSON.parse(line),item=this.pending.get(value.id);if(item){clearTimeout(item.timer);this.pending.delete(value.id);if(value.error)item.reject(Error(value.error.message));else item.resolve(value.result);}});
    this.child.on('exit',()=>{for(const item of this.pending.values()){clearTimeout(item.timer);item.reject(Error('STDIO_EXIT:'+this.stderr));}this.pending.clear();});
  }
  async rpc(method,params={},notification=false){const id=notification?undefined:++sequence;let promise;if(!notification)promise=new Promise((resolve,reject)=>{const timer=setTimeout(()=>{this.pending.delete(id);reject(Error('STDIO_TIMEOUT_OUTCOME_UNKNOWN'));},80000);this.pending.set(id,{resolve,reject,timer});});this.child.stdin.write(JSON.stringify({jsonrpc:'2.0',...(notification?{}:{id}),method,params})+'\n');return promise;}
  close(){this.child.stdin.end();}
}
const http=new NativeHttp(),stdio=new Stdio(),clients={http,stdio};
const op=()=>randomUUID(),check=(name,value)=>{assert.ok(value,name);checks.push({name,passed:true});};
async function tool(mode,name,args={},success=true){const result=await clients[mode].rpc('tools/call',operationCall(name,args)),receipt=JSON.parse(result.content[0].text);events.push({mode,name,ok:receipt.ok,state:receipt.state??receipt.result?.state,operationId:args.operationId,errorCode:receipt.errorCode});if(success)assert.equal(receipt.ok,true,JSON.stringify(receipt));return success?receipt.result:receipt;}
const prefix='net-'+randomUUID().slice(0,8),pkg='dev.tripoder.launcherfixture',spaces={stdio:prefix+'-stdio',http:prefix+'-http'},uploads={};
const selector={id:'counter',kind:'java',className:'dev.tripoder.previewguest.CounterActivity',methodName:'formatCount',argumentTypes:['int']};
const invocation={className:selector.className,methodName:selector.methodName,argumentTypes:['int'],arguments:[7]};
async function probe(headers,ca=config.certificatePem){
  return new Promise((resolve,reject)=>{const req=https.request(config.url,{method:'GET',ca,minVersion:'TLSv1.3',agent:false,headers},res=>{res.resume();res.on('end',()=>resolve(res.statusCode));});req.setTimeout(10000,()=>req.destroy(Error('SECURITY_PROBE_TIMEOUT')));req.on('error',reject);req.end();});
}
try{
  check('phone-rejects-wrong-token',await probe({authorization:'Bearer invalid'})===401);
  check('phone-rejects-untrusted-origin',await probe({authorization:'Bearer '+config.token,origin:'https://attacker.example'})===403);
  await assert.rejects(probe({},[]),e=>e.code==='DEPTH_ZERO_SELF_SIGNED_CERT'||e.code==='SELF_SIGNED_CERT_IN_CHAIN');check('phone-certificate-trust-is-required',true);
  for(const client of Object.values(clients)){await client.rpc('initialize',{protocolVersion:MCP_VERSION,capabilities:{},clientInfo:{name:'tripoder-network-acceptance',version:'1'}});await client.rpc('notifications/initialized',{},true);}
  const httpTools=(await http.rpc('tools/list')).tools,stdioTools=(await stdio.rpc('tools/list')).tools;
  check('http-publishes-shared-upload-tools',httpTools.some(t=>t.name==='application_upload'&&t.inputSchema.properties.action.enum.includes('commit'))&&!httpTools.some(t=>t.name==='application_import'));
  check('stdio-adds-only-local-file-convenience',JSON.stringify(stdioTools.filter(t=>!['application_import','file_pull','file_pull_status'].includes(t.name)))===JSON.stringify(httpTools)&&stdioTools.some(t=>t.name==='application_import'));
  if(options['--read-only']==='true'){
    report.scope='physical-device-stdio-and-native-http-read-only';
    for(const mode of ['stdio','http']){
      const caps=await tool(mode,'runtime.capabilities');check(mode+'-main-runtime-authenticated-and-explicit',caps.explicitTargets===true&&caps.authenticationRequired===true);
      await tool(mode,'spaces.list');check(mode+'-main-spaces-readable',true);
    }
  }else{
  const catalog=await tool('http','applications.list');assert.ok(!catalog.applications.some(a=>a.package===pkg),'use an isolated acceptance installation with no launcher fixture');
  const bytes=await readFile(resolve(options['--fixture']));
  for(const mode of ['stdio','http']){
    const space=spaces[mode],upload=op();uploads[mode]=upload;await tool(mode,'space.create',{operationId:op(),space,name:'Network '+mode});
    let installed;
    if(mode==='stdio')installed=await tool(mode,'application.import',{operationId:upload,space,expectedGeneration:'',apkPaths:[resolve(options['--fixture'])]});
    else{
      await tool(mode,'import.begin',{operationId:upload,space,expectedGeneration:'',files:[{name:'base.apk',size:bytes.length,sha256:createHash('sha256').update(bytes).digest('hex')}]});
      for(let offset=0;offset<bytes.length;offset+=12288)await tool(mode,'import.write',{operationId:op(),uploadId:upload,index:0,offset,data:bytes.subarray(offset,offset+12288).toString('base64')});
      installed=await tool(mode,'import.commit',{operationId:upload});
    }
    check(mode+'-imports-original-apk',installed.package===pkg);const target={space,package:pkg,installGeneration:installed.generation};
    const revision=(await tool(mode,'probes.get',{target})).revision;const started=await tool(mode,'process.start',{operationId:op(),target,component:'',probeRevision:revision});const process={...target,processGeneration:started.processGeneration};
    const other=mode==='stdio'?'http':'stdio';check(mode+'-same-process-visible-from-other-entry',(await tool(other,'process.get',{target:process})).pid===started.pid);
    const before=(await tool(mode,'hook.guestInvoke',{operationId:op(),target:process,invocation})).result;check(mode+'-baseline-original-return',before==='7');
    const source="import {definePlan} from '@abox/hook';export default definePlan({id:'network-"+mode+"',target,params:{},setup(ctx){ctx.java.method('dev.tripoder.previewguest.CounterActivity','formatCount',['int']).replace(({args})=>'"+mode+"-'+args[0]);}});";
    const observed=await tool(mode,'hook.observe',{operationId:op(),target:process,selectors:[selector]});const prepared=await tool(mode,'hook.prepare',{operationId:op(),target:process,evidenceId:observed.evidence.id,source});
    check(mode+'-activates-real-hook',(await tool(mode,'hook.activate',{operationId:op(),target:process,token:prepared.token,sourceSha256:prepared.sourceSha256})).state==='ACTIVE');
    const invokeId=op(),modified=(await tool(mode,'hook.guestInvoke',{operationId:invokeId,target:process,invocation})).result;check(mode+'-actual-modified-return',modified===mode+'-7');
    const status=await tool(other,'hook.status',{target:process});check(mode+'-actual-hits-without-errors',status.hits>=1&&status.errors===0);
    await tool(other,'hook.guestInvoke',{operationId:invokeId,target:process,invocation});const repeated=await tool(mode,'hook.status',{target:process});check(mode+'-cross-entry-operation-deduplicated',repeated.hits===status.hits);
    const stale=await tool(other,'hook.status',{target:{...process,processGeneration:'stale-generation'}},false);check(mode+'-stale-generation-rejected',!stale.ok);
    await tool(mode,'hook.disable',{operationId:op(),target:process,revision:repeated.revision});const restored=(await tool(other,'hook.guestInvoke',{operationId:op(),target:process,invocation})).result;check(mode+'-disable-restores-original',restored===before);
    report[mode]={processGeneration:process.processGeneration,pid:started.pid,before,modified,restored,hits:status.hits,errors:status.errors,nativeCoreSha256:status.runtimeNativeMappings?.coreSha256};
    await tool(mode,'application.stop',{operationId:op(),target});
  }
  for(const mode of ['stdio','http'])await tool(mode,'space.delete',{operationId:op(),space:spaces[mode]});
  await tool('http','application.delete',{operationId:op(),package:pkg,installations:[]});
  for(const mode of ['stdio','http'])await tool(mode,'import.cleanup',{operationId:op(),uploadId:uploads[mode]});
  }
  report.passed=true;console.log(JSON.stringify({passed:true,checks:checks.length,scope:report.scope}));
}catch(error){report.error=String(error);throw error;}
finally{stdio.close();await writeFile(resolve(directory,'network-result.json'),JSON.stringify(report,null,2)+'\n');await writeFile(resolve(directory,'call-metadata.json'),JSON.stringify(events,null,2)+'\n');}
