// Shared schema vocabulary. The APK exports these exact schemas to HTTP clients.
export const object=(properties,required=Object.keys(properties))=>({type:'object',properties,required,additionalProperties:false});
const string=(description,maxLength=32000,minLength=0)=>({type:'string',description,minLength,maxLength});
const number={type:'number',description:'Finite JSON number.'};
export const scalar={description:'Script-defined scalar value (string, number or boolean).',anyOf:[string('String value.'),number,{type:'boolean',description:'Boolean value.'}]};
export const javaTypes=['boolean','byte','short','int','long','char','float','double','java.lang.String','[B','byte[]'];
export const uiAction={description:'One guest view action. Coordinates are pixels relative to its decor view, not device screen coordinates.',anyOf:[
  object({kind:{type:'string',enum:['tap']},x:number,y:number}),
  object({kind:{type:'string',enum:['swipe']},startX:number,startY:number,endX:number,endY:number}),
  object({kind:{type:'string',enum:['clickText']},text:string('Exact visible text to click.',4096,1)}),
  object({kind:{type:'string',enum:['setText']},text:string('Replacement text; empty clears the field.',4096),id:string('Resource entry name returned by ui_dump_hierarchy; omit to match by text/hint.',256),match:string('Exact current text or hint; omit with id, or omit both to select the first editable field.',4096)},['kind','text']),
  object({kind:{type:'string',enum:['key']},keyCode:{type:'integer',minimum:0,maximum:2147483647,description:'Android KeyEvent key code, e.g. 4 for BACK or 66 for ENTER.'}}),
],examples:[{kind:'tap',x:120,y:240},{kind:'setText',id:'search',text:'example'}]};
// The runtime also accepts `type` as the action discriminator. Describe that
// existing form explicitly; reject conflicting kind/type pairs.
uiAction.anyOf.push(...uiAction.anyOf.map(branch=>object({type:branch.properties.kind,...Object.fromEntries(Object.entries(branch.properties).filter(([key])=>key!=='kind'))},branch.required.map(key=>key==='kind'?'type':key))));
export const httpRequest={...object({
  method:{type:'string',enum:['GET','POST','PUT','DELETE','PATCH','HEAD','OPTIONS'],description:'HTTP method, uppercase.'},
  url:{...string('Absolute HTTP(S) URL resolved from the guest network context. Redirects are not followed.',2048,1),pattern:'^[Hh][Tt][Tt][Pp][Ss]?://[^\\s/]+'},
  headers:{type:'object',description:'Header names mapped to string values. At most 32 entries.',maxProperties:32,propertyNames:{type:'string',minLength:1,maxLength:128},additionalProperties:string('Header value.',2048)},
  body:string('Raw request body. For JSON, stringify the JSON and set Content-Type explicitly.',16384),
  timeoutMs:{type:'integer',minimum:100,maximum:10000,default:5000,description:'Connect and read timeout in milliseconds, each; default 5000.'},
},['method','url']),description:'Explicit guest HTTP request. Only method and url are required.',examples:[{method:'GET',url:'https://example.com/',timeoutMs:5000}]};
export const invocation={...object({
  className:string('Fully qualified declared guest class; the class must belong to the guest ClassLoader.',512,1),
  methodName:{...string('Declared method name; constructors are unsupported.',128,1),pattern:'^[^<].*$'},
  argumentTypes:{type:'array',maxItems:16,items:{type:'string',enum:javaTypes},description:'Exact declared Java types in order. Use [] for a zero-argument method.'},
  arguments:{type:'array',maxItems:16,items:{anyOf:[...scalar.anyOf,{type:'null',description:'Null for nullable String or byte[] only.'}]},description:'One value per argumentType in the same order. byte/short/int use bounded JSON integers, boolean uses a JSON boolean, long MUST be a decimal string in signed 64-bit range, byte[] is an even-length hex string (max 4096 bytes), char is one UTF-16 code unit. Only String/byte[] accept null. Objects/arrays are unsupported.'},
}),description:'Invoke a guest method. Instance methods require a no-argument constructor and create a new instance; this does not select an existing instance.',examples:[{className:'com.example.Counter',methodName:'value',argumentTypes:[],arguments:[]}]};
export const parameters={type:'object',description:'Patch script-defined scalar parameters. Read hook_status for current values/revision/version and the project source for parameter declarations; omitted names retain their values. Runtime enforces the compiled plan and an 8192-character total budget.',maxProperties:64,propertyNames:{type:'string',maxLength:8192},additionalProperties:scalar,examples:[{enabled:true,label:'example',count:3}]};

const meanings={
  exportId:'Exact export ID returned by file_export_begin. It also identifies the original export operation.',
  entryId:'Exact file entry ID from file_export_manifest; never a phone or computer path.',
  path:'Absolute phone path for kind=path, or a relative path below the selected guest root.',
  root:'Guest storage root resolved from the exact installation.',
  followSymlinks:'Whether to follow readable source symlinks. False rejects encountered links; cycles and guest root escapes are rejected.',
  operationId:'Persistent ID for this exact operation. Generate a fresh UUID for a new mutation; after a lost receipt query operation_get with the original ID before retrying.',
  target:'Explicit installation/process identity copied from applications_list or targets_list; never infer generations or use a selected UI target.',
  space:'Runtime space ID from spaces_list or the installation record; not its display name.',
  package:'Exact Android package name from applications_list or device_applications_list.',
  installGeneration:'Exact installation generation from applications_list.installations; changes on reinstall/upgrade.',
  processGeneration:'Exact process generation from targets_list or process_start; never substitute a PID.',
  classLoaderId:'Process-scoped ClassLoader ID from classloaders_list.',
  parentClassLoaderId:'Exact parent ClassLoader ID from classloaders_list.',
  dexId:'DEX ID returned by dex_list for this exact process.',
  artifactId:'Immutable artifact ID from artifacts_list; only COMPLETE artifacts can be read.',
  offset:'Zero-based byte offset; not a character or base64 offset.',
  length:'Number of raw artifact bytes to read; response contains base64.',
  sinceSeq:'Exclusive event sequence cursor. Start at 0, then use the returned nextCursor; check gap/dropped counters.',
  cursor:'Opaque pagination cursor; pass an empty string for the first page, then copy nextCursor unchanged.',
  limit:'Maximum number of records in this page; obey the declared bounds and inspect truncation/nextCursor.',
  selectors:'Explicit Java method or confirmed native-export selectors, with unique ids. Names are case-sensitive.',
  id:'Unique selector symbol referenced by the Hook source; not a class or method name.',
  kind:'Discriminator selecting this exact variant.',
  className:'Fully qualified guest Java class name; inspecting it does not initialize the class.',
  methodName:'Exact declared method name; constructors are unsupported.',
  argumentTypes:'Exact declared argument types in order, including [] for no arguments.',
  moduleName:'Exact loaded native library basename, e.g. libexample.so.',
  symbol:'Exact exported ELF symbol from native_exports_search.',
  returnType:'Independently confirmed native return type.',
  buildId:'Build ID of the exact native module whose signature was confirmed.',
  signatureSource:'Must be confirmed; never guess native signatures.',
  evidenceId:'Observation evidence ID returned by hook_observe for this process.',
  source:'Complete TypeScript Hook source, at most 24000 characters. Use project_get/archives for existing source.',
  token:'Preparation token returned by hook_prepare for the exact source SHA.',
  sourceSha256:'Lowercase SHA-256 of the exact prepared source, from hook_prepare.',
  revision:'Exact active Hook revision returned by hook_status.',
  parameterVersion:'Current CAS parameterVersion from hook_status, not the next version.',
  packagePrefix:'Case-sensitive class package prefix; empty string searches all classes within the limit.',
  symbolPrefix:'Case-sensitive exported-symbol prefix; empty string lists exports within the limit.',
  includeInvisible:'Whether to include invisible views in the guest hierarchy.',
  category:'Sandbox storage category to inspect.',
  action:'Guest UI action variant selected by kind (or its type alias).',
  arguments:'A bounded Java scalar argument value; its type must match argumentTypes at the same index.',
  configuration:'Next-process probe configuration. Omitted fields use the documented defaults; existing processes are unchanged.',
  enabled:'Enable the next-process probe plan; default false.',
  discoverLoaders:'Discover ClassLoaders when probes are enabled; default true.',
  captureDexOnLoad:'Automatically snapshot observed DEX loads; default false.',
  processName:'Exact guest process name filter; empty or omitted matches every process in this installation.',
  maxDexBytes:'Maximum bytes per snapshot; default 16777216 (16 MiB).',
  maxTotalBytes:'Total snapshot byte budget; default 67108864 (64 MiB).',
  maxArtifacts:'Maximum snapshot artifacts; default 32.',type:'Action discriminator; accepted alias of kind. Supply exactly one discriminator.',
  startupHook:'Optional exact selectors and Hook source installed before Application construction.',
  expectedRevision:'Current probes_get.revision for compare-and-swap; empty string if no configuration exists.',
  component:'Exact declared launcher component; empty string selects the unique launcher. Use application_launch_targets to discover it.',
  probeRevision:'Exact probes_get.revision; empty string if no probe configuration exists.',
  name:'Display name for this space, 1-24 characters after trimming.',
  mode:'Runtime mode applied to this exact stopped installation.',
  apkPaths:'Absolute readable computer APK paths, base first followed by splits. Available only through the stdio local-import adapter.',
  expectedGeneration:'Current installGeneration for an upgrade; empty string for a new installation.',
  uploadId:'Original application_upload_begin operationId. It also identifies the eventual commit.',
  installations:'Complete installation identities from applications_list for this package; concurrent changes reject deletion.',
  expectedSha256:'Current lowercase SHA-256 from project_get for the source/selectors being replaced; compare-and-swap, not the new content hash.',
  archiveId:'Exact archive ID from project_archives_list.',
  launchOperationId:'Original process_start/launch operation ID from launch_entries_list.',
  files:'APK metadata, base first, then split-1.apk etc.; compute size and SHA-256 from the exact bytes to upload.',
  size:'Exact APK byte length.',sha256:'Lowercase SHA-256 of the entire APK bytes.',
  index:'Zero-based index into the original upload files array.',
  data:'Base64 of the next raw APK chunk, at most 12288 bytes; offset advances by decoded bytes.',
};
// Descriptions are attached recursively, including shared identity/selector fields.
export function describeSchema(schema,key){
  const copy={...schema};
  if(!copy.description&&meanings[key])copy.description=meanings[key];
  if(copy.type==='string'&&copy.maxLength===undefined)copy.maxLength=32000;
  if(copy.type==='string'&&key==='package')copy.maxLength=256;
  if(copy.type==='string'&&['space','installGeneration','processGeneration','parentClassLoaderId','dexId','artifactId','archiveId','evidenceId','token','revision'].includes(key))copy.maxLength=80;
  if(copy.properties)copy.properties=Object.fromEntries(Object.entries(copy.properties).map(([k,v])=>[k,describeSchema(v,k)]));
  if(copy.anyOf)copy.anyOf=copy.anyOf.map(s=>describeSchema(s,key));
  if(copy.items)copy.items=describeSchema(copy.items,key);
  if(copy.additionalProperties&&typeof copy.additionalProperties==='object')copy.additionalProperties=describeSchema(copy.additionalProperties,key);
  return copy;
}

export function validate(value,schema,path){
  const reject=reason=>{throw Error('TOOL_ARGUMENT_TYPE:'+path+': '+reason);};
  if(schema.anyOf){
    for(const branch of schema.anyOf){try{validate(value,branch,path);return;}catch{}}
    reject('does not match any supported variant; inspect required fields and discriminator');
  }
  if(schema.enum&&!schema.enum.includes(value))reject('expected '+schema.enum.join(' | '));
  switch(schema.type){
    case 'null':if(value!==null)reject('expected null');break;
    case 'string':if(typeof value!=='string'||value.length<(schema.minLength??0)||value.length>(schema.maxLength??Infinity)||schema.pattern&&!new RegExp(schema.pattern).test(value))reject('invalid string');break;
    case 'number':case 'integer':if(typeof value!=='number'||!Number.isFinite(value)||schema.type==='integer'&&!Number.isSafeInteger(value)||value<(schema.minimum??-Infinity)||value>(schema.maximum??Infinity))reject('number outside supported range');break;
    case 'boolean':if(typeof value!=='boolean')reject('expected boolean');break;
    case 'object':{
      if(!value||typeof value!=='object'||Array.isArray(value))reject('expected object');
      const keys=Object.keys(value),missing=(schema.required??[]).filter(key=>!Object.hasOwn(value,key));
      if(missing.length)reject('missing '+missing.join(', '));
      if(keys.length>(schema.maxProperties??Infinity))reject('too many properties');
      for(const key of keys){
        if(schema.propertyNames)validate(key,schema.propertyNames,path+' key');
        const child=Object.hasOwn(schema.properties??{},key)?schema.properties[key]:undefined;
        if(child)validate(value[key],child,path+'.'+key);
        else if(schema.additionalProperties===false)reject('unsupported '+key);
        else if(typeof schema.additionalProperties==='object')validate(value[key],schema.additionalProperties,path+'.'+key);
      }
      break;
    }
    case 'array':if(!Array.isArray(value)||value.length<(schema.minItems??0)||value.length>(schema.maxItems??Infinity))reject('invalid array length');value.forEach((item,index)=>validate(item,schema.items,path+'['+index+']'));break;
  }
}

export function validateInvocation(call){
  if(call.argumentTypes.length!==call.arguments.length)throw Error('TOOL_ARGUMENT_TYPE:invocation.arguments: count must match argumentTypes');
  call.argumentTypes.forEach((type,index)=>{
    const value=call.arguments[index];let valid=false;
    if(type==='java.lang.String')valid=value===null||typeof value==='string';
    else if(type==='[B'||type==='byte[]')valid=value===null||typeof value==='string'&&value.length<=8192&&value.length%2===0&&/^[0-9a-fA-F]*$/.test(value);
    else if(type==='char')valid=typeof value==='string'&&value.length===1;
    else if(type==='boolean')valid=typeof value==='boolean';
    else if(type==='long')valid=typeof value==='string'&&/^-?[0-9]{1,20}$/.test(value)&&BigInt(value)>=-(1n<<63n)&&BigInt(value)<(1n<<63n);
    else if(type==='float'||type==='double')valid=typeof value==='number'&&Number.isFinite(value)&&(type==='double'||Number.isFinite(Math.fround(value)));
    else {const bounds={byte:[-128,127],short:[-32768,32767],int:[-2147483648,2147483647]}[type];valid=!!bounds&&Number.isInteger(value)&&value>=bounds[0]&&value<=bounds[1];}
    if(!valid)throw Error('TOOL_ARGUMENT_TYPE:invocation.arguments['+index+']: invalid '+type+' value');
  });
}
