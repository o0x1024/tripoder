import test from 'node:test';
import assert from 'node:assert/strict';
import {tools,command} from './tools.mjs';
import {cases} from './schema-fixtures.mjs';
test('all 70 tool contracts and nested payloads accept valid calls and reject malformed arguments',()=>{
  assert.equal(tools.length,70);
  for(const fixture of cases){
    if(fixture.valid)assert.doesNotThrow(()=>command(fixture.name,fixture.args),JSON.stringify(fixture));
    else assert.throws(()=>command(fixture.name,fixture.args),undefined,JSON.stringify(fixture));
  }
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
