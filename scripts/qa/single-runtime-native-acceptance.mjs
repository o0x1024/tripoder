/** Owned APK acceptance for the unified Java/Native runtime; no mutation replay. */
import {Transport,loadConfig,MCP_VERSION} from '../../agent/transport.mjs';
import {randomUUID,createHash} from 'node:crypto';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';

const options=Object.fromEntries(process.argv.slice(2).reduce((out,key,index,args)=>index%2?out:[...out,[key,args[index+1]]],[]));
const directory=resolve(options['--evidence']),apk=resolve(options['--apk']),space=options['--space'];
assert.match(space,/^[A-Za-z0-9_-]{1,80}$/);
const apkSha256=createHash('sha256').update(await readFile(apk)).digest('hex');
assert.equal(apkSha256,'0d71591b4e8c3c974d452236b5df545d74470b16452f2af29e63468dcc8de5a5','this acceptance uses the frozen owned fixture');
await mkdir(directory,{recursive:true});
const transport=new Transport(await loadConfig(resolve(options['--connection'])));
const report={passed:false,deviceExecuted:true,fixture:{package:'dev.tripoder.fixture',apkSha256,buildId:'f9084a5220a78071143eabe127ee08b9c5fcf692'},checks:[],calls:[]};
let install,target,revision;
const save=()=>writeFile(resolve(directory,'summary.json'),JSON.stringify(report,null,2)+'\n');
function check(name,condition,observed){assert.ok(condition,name);report.checks.push({name,passed:true,observed});}
async function call(method,args={}){
  const receipt=await transport.operation(method,args);
  report.calls.push({method,operationId:args.operationId,ok:receipt.ok,state:receipt.state,error:receipt.ok?undefined:receipt.error});
  await save();if(!receipt.ok)throw Object.assign(Error(method+':'+JSON.stringify(receipt)),{receipt});return receipt.result;
}
const mutate=(method,args={})=>call(method,{operationId:randomUUID(),...args});
const invoke=(methodName,argumentTypes,args)=>mutate('hook.guestInvoke',{target,invocation:{className:'dev.tripoder.fixture.GuestProbe',methodName,argumentTypes,arguments:args}});
async function activate(selectors,source){
  const observed=await mutate('hook.observe',{target,selectors});
  const prepared=await mutate('hook.prepare',{target,evidenceId:observed.evidence.id,source});
  const active=await mutate('hook.activate',{target,token:prepared.token,sourceSha256:prepared.sourceSha256});
  revision=active.revision;check('activation-acknowledged',active.state==='ACTIVE',active.state);return observed;
}
async function disable(){const result=await mutate('hook.disable',{target,revision});check('disable-acknowledged',result.state==='DISABLED',result.state);revision=null;}
try{
  const initialized=await transport.rpc({jsonrpc:'2.0',id:randomUUID(),method:'initialize',params:{protocolVersion:MCP_VERSION,capabilities:{},clientInfo:{name:'single-runtime-owned-native',version:'1'}}});
  assert.ok(initialized.result);await transport.rpc({jsonrpc:'2.0',method:'notifications/initialized'});
  const catalog=(await transport.rpc({jsonrpc:'2.0',id:randomUUID(),method:'tools/list',params:{}})).result.tools;
  check('mode-action-absent',!catalog.find(tool=>tool.name==='application_manage')._meta['dev.tripoder/actionSchemas'].set_mode);
  await mutate('space.create',{space,name:'单运行时 Native 验收'});
  const importOperationId=randomUUID();report.importOperationId=importOperationId;await save();
  const imported=await transport.importApks({operationId:importOperationId,space,expectedGeneration:'',apkPaths:[apk]});
  check('original-owned-apk-imported',imported.ok,imported.state);
  const row=(await call('applications.list')).installations.find(row=>row.space===space&&row.package===report.fixture.package);
  assert.ok(row);install={space,package:row.package,installGeneration:row.installGeneration};report.install=install;
  const probes=await call('probes.get',{target:install});assert.equal(probes.configuration.enabled,false);
  const started=await mutate('process.start',{target:install,component:'dev.tripoder.fixture.FixtureActivity',probeRevision:probes.revision});
  target={...install,processGeneration:started.processGeneration};report.target=target;
  check('guest-entry-dispatched',started.entryDispatchRequested===true,started.pid);
  const baseline=(await invoke('nativeDelay',['int'],[3])).result;check('native-original-result',baseline===16,baseline);
  const buildId=(await invoke('nativeBuildId',[],[])).result;check('actual-native-build-id',buildId===report.fixture.buildId,buildId);
  const status=await call('hook.status',{target});report.executionPolicy=status.executionPolicy;
  report.loadedCoreSha256=status.runtimeNativeMappings?.coreSha256;
  if(options['--core-sha256'])check('loaded-native-core-matches-build',report.loadedCoreSha256===options['--core-sha256'],report.loadedCoreSha256);
  check('single-runtime-with-jit-restored',!Object.hasOwn(status.identity,'mode')&&status.executionPolicy.bootstrapComplete&&status.executionPolicy.runtimeStateRestored&&status.executionPolicy.jitEnabled,status.executionPolicy);
  const selector={id:'delay',kind:'native',moduleName:'libguestfixture.so',symbol:'guest_delay_ms',argumentTypes:['int32'],returnType:'int32',buildId,signatureSource:'confirmed'};
  const source=`import {definePlan} from '@abox/hook';export default definePlan({id:'single-runtime-owned-native',target,params:{},setup(ctx){ctx.native.function('libguestfixture.so','guest_delay_ms',{argumentTypes:['int32'],returnType:'int32',buildId:${JSON.stringify(buildId)}}).after(({result})=>result+1000);}});`;
  await activate([selector],source);
  const changed=(await invoke('nativeDelay',['int'],[3])).result;const active=await call('hook.status',{target});
  check('native-trampoline-and-return-change',changed===1016&&active.hits>=1&&active.errors===0,{result:changed,hits:active.hits,errors:active.errors});
  await disable();const restored=(await invoke('nativeDelay',['int'],[3])).result;check('native-disable-restores-original',restored===16,restored);
  const original=(await invoke('greet',['java.lang.String'],['single'])).result;check('java-original-result',original==='guest-original:single',original);
  await activate([{id:'greet',kind:'java',className:'dev.tripoder.fixture.GuestProbe',methodName:'greet',argumentTypes:['java.lang.String']}],"import {definePlan} from '@abox/hook';export default definePlan({id:'single-runtime-owned-java',target,params:{},setup(ctx){ctx.java.method('dev.tripoder.fixture.GuestProbe','greet',['java.lang.String']).replace(({args})=>'unified:'+args[0]);}});");
  const javaChanged=(await invoke('greet',['java.lang.String'],['single'])).result;check('java-real-call-changed',javaChanged==='unified:single',javaChanged);
  await disable();const javaRestored=(await invoke('greet',['java.lang.String'],['single'])).result;check('java-disable-restores-original',javaRestored===original,javaRestored);
  report.passed=true;
}catch(error){report.error=String(error);process.exitCode=1;}
finally{
  if(target&&revision)try{const status=await call('hook.status',{target});if(status.state==='ACTIVE'&&status.revision===revision)await disable();}catch(error){report.cleanupError=String(error);}
  if(install)try{await mutate('application.stop',{target:install});}catch(error){report.stopError=String(error);}
  await save();await transport.close();console.log(JSON.stringify({passed:report.passed,checks:report.checks.length,error:report.error,evidence:directory}));
}
