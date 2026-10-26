// Admission diagnostics use JSON pointers; never echo request bodies or source.
const pointer=(path,key)=>path+'/'+String(key).replaceAll('~','~0').replaceAll('/','~1');
export class ArgumentError extends Error {
  constructor(issues){super(issues.map(i=>`${i.path||'/'}: ${i.code}${i.expected?' (expected '+i.expected+')':''}`).join('; '));this.errorCode='INVALID_TOOL_ARGUMENTS';this.issues=issues.slice(0,16);this.dispatched=false;}
}
export const argumentError=(path,code,expected)=>new ArgumentError([{path,code,...(expected?{expected}:{})}]);
export function validate(value,schema,path=''){
  // The old caller label is not part of the JSON document.
  if(path==='arguments')path='';
  const reject=(code,expected,at=path)=>{throw argumentError(at,code,expected);};
  if(schema.anyOf){
    let branches=schema.anyOf;
    if(value&&typeof value==='object'&&!Array.isArray(value)){
      const keys=[...new Set(branches.flatMap(b=>Object.entries(b.properties??{}).filter(([,s])=>s.enum?.length===1).map(([k])=>k)))];
      for(const key of keys)if(Object.hasOwn(value,key)){
        const declaring=branches.filter(b=>b.properties?.[key]?.enum?.length===1);
        if(declaring.length){const selected=declaring.filter(b=>b.properties[key].enum.includes(value[key]));if(!selected.length)reject('INVALID_DISCRIMINATOR',declaring.map(b=>b.properties[key].enum[0]).join(', '),pointer(path,key));branches=selected;break;}
      }
    }
    const errors=[];for(const branch of branches){try{validate(value,branch,path);return;}catch(error){if(!(error instanceof ArgumentError))throw error;errors.push(...error.issues);}}
    const unique=[...new Map(errors.map(e=>[JSON.stringify(e),e])).values()];throw new ArgumentError(unique.length?unique:[{path,code:'NO_MATCHING_VARIANT'}]);
  }
  if(schema.enum&&!schema.enum.includes(value))reject('ENUM_VALUE_INVALID',schema.enum.join(', '));
  switch(schema.type){
    case 'string':if(typeof value!=='string')reject('TYPE_MISMATCH','string');if(value.length<(schema.minLength??0)||value.length>(schema.maxLength??Infinity))reject('STRING_LENGTH_INVALID',`${schema.minLength??0}..${schema.maxLength??'unbounded'}`);if(schema.pattern&&!new RegExp(schema.pattern).test(value))reject('STRING_PATTERN_INVALID',schema.description??schema.pattern);break;
    case 'null':if(value!==null)reject('TYPE_MISMATCH','null');break;
    case 'boolean':if(typeof value!=='boolean')reject('TYPE_MISMATCH','boolean');break;
    case 'number':case 'integer':if(typeof value!=='number'||!Number.isFinite(value)||schema.type==='integer'&&!Number.isSafeInteger(value))reject('TYPE_MISMATCH',schema.type);if(value<(schema.minimum??-Infinity)||value>(schema.maximum??Infinity))reject('NUMBER_RANGE_INVALID',`${schema.minimum??'-unbounded'}..${schema.maximum??'unbounded'}`);break;
    case 'object':{
      if(!value||typeof value!=='object'||Array.isArray(value))reject('TYPE_MISMATCH','object');
      if(Object.keys(value).length>(schema.maxProperties??Infinity))reject('OBJECT_SIZE_INVALID');
      const missing=(schema.required??[]).filter(k=>!Object.hasOwn(value,k));if(missing.length)throw new ArgumentError(missing.map(k=>({path:pointer(path,k),code:'REQUIRED_PROPERTY_MISSING',...(k==='processGeneration'?{source:'application_query(action=targets)'}:{})})));
      for(const [key,item] of Object.entries(value)){const at=pointer(path,key);if(schema.propertyNames)validate(key,schema.propertyNames,at);if(Object.hasOwn(schema.properties??{},key))validate(item,schema.properties[key],at);else if(schema.additionalProperties===false)reject('UNKNOWN_PROPERTY',undefined,at);else if(typeof schema.additionalProperties==='object')validate(item,schema.additionalProperties,at);}break;
    }
    case 'array':if(!Array.isArray(value))reject('TYPE_MISMATCH','array');if(value.length<(schema.minItems??0)||value.length>(schema.maxItems??Infinity))reject('ARRAY_LENGTH_INVALID',`${schema.minItems??0}..${schema.maxItems??'unbounded'}`);value.forEach((item,index)=>validate(item,schema.items,pointer(path,index)));break;
  }
}
