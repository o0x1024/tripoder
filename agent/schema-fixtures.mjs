// Shared JS / production-Java acceptance cases, including all public tools.
import {writeFile} from 'node:fs/promises';
import {tools} from './tools.mjs';
const target={space:'space-a',package:'owned.app',installGeneration:'install-a',processGeneration:'process-a'};
const values={operationId:'operation-a',uploadId:'upload-a',launchOperationId:'launch-a',target,space:'space-a',package:'owned.app',expectedRevision:'',probeRevision:'',component:'',expectedGeneration:'',expectedSha256:'a'.repeat(64),sourceSha256:'a'.repeat(64),sha256:'a'.repeat(64),name:'base.apk',className:'owned.Counter',methodName:'value',argumentTypes:[],arguments:[],classLoaderId:'loader-a',cursor:'',category:'all',packagePrefix:'',symbolPrefix:'',moduleName:'libowned.so',data:'YWJjZA==',source:'export default {};',configuration:{}};
function example(schema,key){
  if(key==='target')return Object.fromEntries(Object.keys(schema.properties).map(k=>[k,target[k]]));
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
for(const tool of tools){
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
])cases.push({name,args,valid});
if(process.argv[1]&&import.meta.url===new URL(process.argv[1],'file:').href)await writeFile(process.argv[2],JSON.stringify(cases));
