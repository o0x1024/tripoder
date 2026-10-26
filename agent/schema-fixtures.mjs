// Shared JS / production-Java acceptance cases, including all public tools.
import {writeFile} from 'node:fs/promises';
import {tools,operationRoutes,referenceMigrations} from './tools.mjs';
const target={space:'space-a',package:'owned.app',installGeneration:'install-a',processGeneration:'process-a'};
const values={operationId:'operation-a',uploadId:'upload-a',launchOperationId:'launch-a',target,space:'space-a',package:'owned.app',expectedRevision:'',probeRevision:'',component:'',expectedGeneration:'',expectedSha256:'a'.repeat(64),sourceSha256:'a'.repeat(64),sha256:'a'.repeat(64),name:'base.apk',className:'owned.Counter',methodName:'value',argumentTypes:[],arguments:[],classLoaderId:'loader-a',cursor:'',category:'all',packagePrefix:'',symbolPrefix:'',moduleName:'libowned.so',data:'YWJjZA==',source:'export default {};',configuration:{}};
function example(schema,key){
  if(key==='cursor'&&schema.type==='integer')return 0;
  if(key==='entryId')return 'e0';
  if(key==='target')return Object.fromEntries(Object.keys(schema.properties).map(k=>[k,target[k]]));
  if(key==='source'&&schema.anyOf)return {kind:'path',path:'/tmp/example'};
  if(key==='destination')return '/tmp/download';
  if(key==='action'&&schema.enum)return schema.enum[0];
  if(Object.hasOwn(values,key))return structuredClone(values[key]);
  if(schema.examples)return structuredClone(schema.examples[0]);
  if(schema.anyOf)return example(schema.anyOf[0],key);
  if(schema.enum)return schema.enum[0];
  if(schema.type==='object')return Object.fromEntries((schema.required??[]).map(k=>[k,example(schema.properties[k],k)]));
  if(schema.type==='array')return Array.from({length:schema.minItems??0},()=>example(schema.items,''));
  if(schema.type==='boolean')return false;
  if(schema.type==='number'||schema.type==='integer')return schema.minimum??0;
  return 'example';
}
export const cases=[];
for(const route of operationRoutes){
  const tool={name:route.tool,inputSchema:route.inputSchema};
  const args=example(tool.inputSchema,'');
  cases.push({name:tool.name,args,valid:true});
  cases.push({name:tool.name,args:{...args,unexpected:true},valid:false});
  for(const key of tool.inputSchema.required){const absent={...args};delete absent[key];cases.push({name:tool.name,args:absent,valid:false});}
}
const scoped=(field,value)=>({operationId:'operation-a',target,[field]:value});
for(const [name,args,valid] of [
  ['http_send_request',scoped('request',{}),false],
  ['http_send_request',scoped('request',{method:'GET',url:'https://example.com/',constructor:'unknown field'}),false],
  ['http_send_request',scoped('request',{method:'GET',url:'file:///etc/hosts'}),false],
  ['http_send_request',scoped('request',{method:'POST',url:'https://example.com/',headers:{'Content-Type':'application/json'},body:'{}',timeoutMs:100}),true],
  ['http_send_request',scoped('request',{method:'GET',url:'https://example.com/',timeoutMs:99}),false],
  ['http_send_request',scoped('request',{method:'GET',url:'https://example.com/',headers:{a:3}}),false],
  ['http_send_request',scoped('request',{method:'GET',url:'https://example.com/',headers:Object.fromEntries(Array.from({length:33},(_,i)=>['h'+i,'v']))}),false],
  ['ui_dispatch_action',scoped('action',{}),false],
  ['ui_dispatch_action',scoped('action',{kind:'tap',x:10.5,y:20}),true],
  ['ui_dispatch_action',scoped('action',{type:'clickText',text:'Go'}),true],
  ['ui_dispatch_action',scoped('action',{kind:'tap',x:1}),false],
  ['ui_dispatch_action',scoped('action',{kind:'tap',type:'swipe',x:1,y:2}),false],
  ['ui_dispatch_action',scoped('action',{kind:'swipe',startX:1,startY:2,endX:3,endY:4}),true],
  ['ui_dispatch_action',scoped('action',{kind:'setText',id:'search',text:''}),true],
  ['ui_dispatch_action',scoped('action',{kind:'key',keyCode:4.5}),false],
  ['guest_invoke',scoped('invocation',{}),false],
  ['guest_invoke',scoped('invocation',{className:'owned.Counter',methodName:'value',argumentTypes:['long','[B','java.lang.String'],arguments:['9223372036854775807','ff00',null]}),true],
  ['guest_invoke',scoped('invocation',{className:'owned.Counter',methodName:'value',argumentTypes:['int'],arguments:[]}),false],
  ['guest_invoke',scoped('invocation',{className:'owned.Counter',methodName:'value',argumentTypes:['long'],arguments:[7]}),false],
  ['guest_invoke',scoped('invocation',{className:'owned.Counter',methodName:'value',argumentTypes:['long'],arguments:['9223372036854775808']}),false],
  ['guest_invoke',scoped('invocation',{className:'owned.Counter',methodName:'value',argumentTypes:['long'],arguments:['-9223372036854775808']}),true],
  ['guest_invoke',scoped('invocation',{className:'owned.Counter',methodName:'value',argumentTypes:['byte'],arguments:[128]}),false],
  ['guest_invoke',scoped('invocation',{className:'owned.Counter',methodName:'value',argumentTypes:['[B'],arguments:['abc']}),false],
  ['guest_invoke',scoped('invocation',{className:'owned.Counter',methodName:'value',argumentTypes:['int'],arguments:[null]}),false],
  ['guest_invoke',scoped('invocation',{className:'owned.Counter',methodName:'value',argumentTypes:['java.lang.Object'],arguments:[{}]}),false],
  ['hook_update_params',{...scoped('parameters',{flag:true,count:1.5,text:''}),revision:'revision-a',parameterVersion:1},true],
  ['hook_update_params',{...scoped('parameters',{nested:{}}),revision:'revision-a',parameterVersion:1},false],
  ['probes_configure',{operationId:'probe-a',target:{space:'space-a',package:'owned.app',installGeneration:'install-a'},expectedRevision:'',configuration:{enabled:true,discoverLoaders:false,maxDexBytes:112}},true],
]){
 const migration=referenceMigrations[name];cases.push({name:migration?.tool??name,args:{...args,...(migration?.action?{action:migration.action}:{})},valid});
}


// Objects are Hook references, never external invocation JSON values.
const install={space:'space-a',package:'owned.app',installGeneration:'install-a'};
const startup=(types,enabled=true)=>({action:'configure',operationId:'object-plan',target:install,expectedRevision:'',configuration:{enabled,startupHook:{selectors:[{id:'objects',kind:'java',className:'owned.Probe',methodName:'call',argumentTypes:types}],source:'export default {};'}}});
for(const types of [['java.lang.Thread','java.lang.Throwable'],['android.content.Context'],['[Ljava.lang.Object;','[I'],['a','[La;'],['包.对象','[L包.对象;']])cases.push({name:'probes_control',args:startup(types),valid:true});
for(const types of [['void'],['Ljava/lang/Throwable;'],['java.lang.Throwable[]'],['java.lang.Throwable<script>']])cases.push({name:'probes_control',args:startup(types),valid:false});
cases.push({name:'probes_control',args:startup(['java.lang.Throwable'],false),valid:false});
cases.push({name:'guest_invoke',args:scoped('invocation',{className:'owned.Probe',methodName:'call',argumentTypes:['java.lang.Throwable'],arguments:[null]}),valid:false});
cases.push({name:'application_query',args:{action:'manifest',target:install},valid:true});
cases.push({name:'application_query',args:{action:'manifest',target},valid:false});
cases.push({name:'process_inspect',args:{action:'manifest',target},valid:false});
cases.push({name:'process_inspect',args:{action:'storage',target},valid:false});



for(const url of ['https://example.com/%zz','https://example.com:99999/','https://user@example.com/','https://example.com/#','https://example.com/中文'])cases.push({name:'http_send_request',args:scoped('request',{method:'GET',url}),valid:false});
for(const value of ['bad\r\nvalue','bad\nsecond\n','bad\u0000value'])cases.push({name:'http_send_request',args:scoped('request',{method:'GET',url:'https://example.com/',headers:{'X-Probe':value}}),valid:false});
cases.push({name:'http_send_request',args:scoped('request',{method:'GET',url:'https://example.com/',body:'ignored'}),valid:false});


cases.push({name:'artifact_query',args:{action:'read',processGeneration:'process-a',artifactId:'artifact-a',offset:4294967296,length:1},valid:false});
cases.push({name:'file_query',args:{action:'manifest',exportId:'export-a',cursor:4294967296,limit:16},valid:false});
cases.push({name:'events_query',args:{action:'hook',target,sinceSeq:9007199254740992},valid:false});
if(process.argv[1]&&import.meta.url===new URL(process.argv[1],'file:').href)await writeFile(process.argv[2],JSON.stringify(cases));
