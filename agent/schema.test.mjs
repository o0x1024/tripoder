import test from 'node:test';
import assert from 'node:assert/strict';
import {tools,command,operationRoutes,contract} from './tools.mjs';
import {cases} from './schema-fixtures.mjs';
test('all public tool contracts and nested payloads accept valid calls and reject malformed arguments',()=>{
  assert.equal(tools.length,36);
  for(const fixture of cases){
    if(fixture.valid)assert.doesNotThrow(()=>command(fixture.name,fixture.args),JSON.stringify(fixture));
    else assert.throws(()=>command(fixture.name,fixture.args),undefined,JSON.stringify(fixture));
  }
});
test('flat public schemas expose enums while strict action branches reject mixed fields',()=>{
  assert.equal(contract.length,33);
  for(const tool of tools){assert.equal(tool.inputSchema.type,'object');assert.equal(tool.inputSchema.anyOf,undefined);assert.equal(tool.inputSchema.oneOf,undefined);assert.equal(tool._meta['dev.tripoder/contractVersion'],2);}
  assert.throws(()=>command('file_export',{action:'read',operationId:'op',exportId:'id'}),/INVALID_ACTION/);
  assert.throws(()=>command('file_query',{action:'stat',source:{kind:'path',path:'/tmp/a'},exportId:'other'}),/unsupported/);
  assert.throws(()=>command('file_export',{action:'begin',operationId:'op',exportId:'wrong'}),/missing/);
  assert.throws(()=>command('runtime_query',{action:'unknown'}),/INVALID_ACTION/);
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
