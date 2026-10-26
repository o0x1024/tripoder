import test from 'node:test';
import assert from 'node:assert/strict';
import {tools,command,operationRoutes,contract} from './tools.mjs';
import {cases} from './schema-fixtures.mjs';
import {validate} from './schema-validation.mjs';
test('all public tool contracts and nested payloads accept valid calls and reject malformed arguments',()=>{
  assert.equal(tools.length,50);
  for(const fixture of cases){
    if(fixture.valid)assert.doesNotThrow(()=>command(fixture.name,fixture.args),JSON.stringify(fixture));
    else assert.throws(()=>command(fixture.name,fixture.args),undefined,JSON.stringify(fixture));
  }
});
test('model and execution share the exact published action branches',()=>{
  assert.equal(contract.length,43);
  for(const tool of tools){assert.equal(tool.inputSchema.type,'object');if(Object.keys(tool._meta['dev.tripoder/actionSchemas']).length)assert.deepEqual(tool.inputSchema.anyOf,Object.values(tool._meta['dev.tripoder/actionSchemas']));assert.equal(tool.inputSchema.oneOf,undefined);assert.equal(tool._meta['dev.tripoder/contractVersion'],3);}
  assert.throws(()=>command('file_export',{action:'read',operationId:'op',exportId:'id'}),/INVALID_DISCRIMINATOR/);
  assert.throws(()=>command('file_query',{action:'stat',source:{kind:'path',path:'/tmp/a'},exportId:'other'}),/UNKNOWN_PROPERTY/);
  assert.throws(()=>command('file_export',{action:'begin',operationId:'op',exportId:'wrong'}),/REQUIRED_PROPERTY_MISSING/);
  assert.throws(()=>command('runtime_query',{action:'unknown'}),/INVALID_DISCRIMINATOR/);
  assert.throws(()=>command('runtime_capabilities',{}),/TOOL_NOT_FOUND/);
  for(const tool of tools){const routes=operationRoutes.filter(r=>r.tool===tool.name);assert.ok(routes.every(r=>r.mutation===!tool.annotations.readOnlyHint));}
});
test('every public field has a description and every object declares its field policy',()=>{
  const visit=(schema,path)=>{
    assert.ok(schema.description,path+' needs a description');
    if(schema.type==='object')assert.notEqual(schema.additionalProperties,undefined,path+' needs an explicit extra-field policy');
    for(const [key,child] of Object.entries(schema.properties??{}))visit(child,path+'.'+key);
    schema.anyOf?.forEach((child,i)=>visit(child,path+' variant '+i));
    if(schema.items)visit(schema.items,path+'[]');
    if(typeof schema.additionalProperties==='object')visit(schema.additionalProperties,path+' map value');
  };
  for(const tool of tools)visit(tool.inputSchema,tool.name);
});

const installation={space:'space-a',package:'owned.app',installGeneration:'install-a'};
test('admission errors select the actual discriminator and retain exact JSON pointers without source',()=>{
  assert.throws(()=>command('process_inspect',{action:'hook_status',target:installation}),e=>e.dispatched===false&&e.issues[0].path==='/target/processGeneration'&&e.issues[0].code==='REQUIRED_PROPERTY_MISSING');
  const args={action:'configure',operationId:'owned-errors',target:installation,expectedRevision:'',configuration:{enabled:true,startupHook:{selectors:[{id:'a',kind:'java',className:'owned.Class',methodName:'call',argumentTypes:['void']}],source:'SENSITIVE_SOURCE_MUST_NOT_APPEAR'}}};
  assert.throws(()=>command('probes_control',args),e=>e.issues[0].path==='/configuration/startupHook/selectors/0/argumentTypes/0'&&e.issues[0].code==='STRING_PATTERN_INVALID'&&!e.message.includes('SENSITIVE_SOURCE_MUST_NOT_APPEAR')&&!e.message.includes('moduleName'));
  assert.equal(command('application_query',{action:'manifest',target:installation}).method,'application.manifest');
});

test('all structural fixtures have identical model-schema and execution-schema admission',()=>{
 for(const fixture of cases){
  const tool=tools.find(t=>t.name===fixture.name);
  const route=operationRoutes.find(r=>r.tool===fixture.name&&(r.action===null||r.action===fixture.args.action));
  if(!tool||!route)continue;
  let model=true,execution=true;
  try{validate(fixture.args,tool.inputSchema);}catch{model=false;}
  try{validate(fixture.args,route.inputSchema);}catch{execution=false;}
  assert.equal(model,execution,JSON.stringify(fixture));
 }
 const target={space:'s',package:'p',installGeneration:'g'};
 for(const args of [{action:'targets',target},{action:'launch_targets',target,limit:20}]){
  assert.throws(()=>validate(args,tools.find(t=>t.name==='application_query').inputSchema),/UNKNOWN_PROPERTY/);
 }
 assert.doesNotThrow(()=>validate({action:'targets'},tools.find(t=>t.name==='application_query').inputSchema));
 assert.doesNotThrow(()=>validate({action:'launch_targets',target},tools.find(t=>t.name==='application_query').inputSchema));
});
