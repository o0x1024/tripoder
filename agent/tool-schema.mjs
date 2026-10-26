import {argumentError} from './schema-validation.mjs';
import {invocationJavaTypes} from './java-types.mjs';
export {validate} from './schema-validation.mjs';
// Shared schema vocabulary. The APK exports these exact schemas to HTTP clients.
export const object=(properties,required=Object.keys(properties))=>({type:'object',properties,required,additionalProperties:false});
const string=(description,maxLength=32000,minLength=0)=>({type:'string',description,minLength,maxLength});
const number={type:'number',description:'Finite JSON number.'};
export const scalar={description:'Script-defined scalar value (string, number or boolean).',anyOf:[string('String value.'),number,{type:'boolean',description:'Boolean value.'}]};
export const javaTypes=invocationJavaTypes;
export const uiAction={description:'One guest view action. Coordinates are pixels relative to its decor view, not device screen coordinates.',anyOf:[
  object({kind:{type:'string',enum:['tap']},x:number,y:number}),
  object({kind:{type:'string',enum:['swipe']},startX:number,startY:number,endX:number,endY:number}),
  object({kind:{type:'string',enum:['clickText']},text:string('Exact visible text to click.',4096,1)}),
  object({kind:{type:'string',enum:['clickTextContains']},text:string('Substring of visible text or content description to click; the first matching clickable ancestor is used.',4096,1)}),
  object({kind:{type:'string',enum:['clickById']},id:string('Resource entry name returned by ui_dump_hierarchy, e.g. "login_button".',256,1)}),
  object({kind:{type:'string',enum:['clickByDescription']},description:string('Exact accessibility content description to click.',4096,1)}),
  object({kind:{type:'string',enum:['longPress']},x:number,y:number,durationMs:{type:'integer',minimum:300,maximum:5000,default:600,description:'Press duration before release; default 600ms.'}}),
  object({kind:{type:'string',enum:['doubleTap']},x:number,y:number}),
  object({kind:{type:'string',enum:['scrollIntoView']},text:string('Visible text or content description to scroll a scrollable ancestor towards, in bounded steps.',4096,1)}),
  object({kind:{type:'string',enum:['waitForElement']},text:string('Visible text or content description to wait for.',4096,1),timeoutMs:{type:'integer',minimum:100,maximum:15000,default:5000,description:'Maximum wait before WAIT_TIMEOUT; default 5000ms.'}},['kind','text']),
  object({kind:{type:'string',enum:['setText']},text:string('Replacement text; empty clears the field.',4096),id:string('Resource entry name returned by ui_dump_hierarchy; omit to match by text/hint.',256),match:string('Exact current text or hint; omit with id, or omit both to select the first editable field.',4096)},['kind','text']),
  object({kind:{type:'string',enum:['key']},keyCode:{type:'integer',minimum:0,maximum:2147483647,description:'Android KeyEvent key code, e.g. 4 for BACK or 66 for ENTER.'}}),
],examples:[{kind:'tap',x:120,y:240},{kind:'setText',id:'search',text:'example'},{kind:'clickById',id:'login_button'},{kind:'waitForElement',text:'Welcome'}]};
// The runtime also accepts `type` as the action discriminator. Describe that
// existing form explicitly; reject conflicting kind/type pairs.
uiAction.anyOf.push(...uiAction.anyOf.map(branch=>object({type:branch.properties.kind,...Object.fromEntries(Object.entries(branch.properties).filter(([key])=>key!=='kind'))},branch.required.map(key=>key==='kind'?'type':key))));
export const httpRequest={...object({
  method:{type:'string',enum:['GET','POST','PUT','DELETE','PATCH','HEAD','OPTIONS'],description:'HTTP method, uppercase.'},
  url:{...string('Absolute HTTP(S) URL resolved from the guest network context. Redirects are not followed.',2048,1),pattern:'^[Hh][Tt][Tt][Pp][Ss]?://[^\\s/]+'},
  headers:{type:'object',description:'Header names mapped to string values. At most 32 entries.',maxProperties:32,propertyNames:{type:'string',minLength:1,maxLength:128,pattern:"^[!#$%&'*+.^_`|~0-9A-Za-z-]+$(?![\\s\\S])"},additionalProperties:{...string('Header value; control characters and CR/LF are forbidden.',2048),pattern:'^(?![\\s\\S]*[\\x00-\\x08\\x0a-\\x1f\\x7f])[\\s\\S]*$'}},
  body:string('Raw request body. Omit for GET/HEAD; these methods reject a body. For JSON, stringify and set Content-Type explicitly.',16384),
  timeoutMs:{type:'integer',minimum:100,maximum:10000,default:5000,description:'Connect and read timeout in milliseconds, each; default 5000.'},
},['method','url']),description:'Explicit guest HTTP request. Only method and url are required.',examples:[{method:'GET',url:'https://example.com/',timeoutMs:5000}]};
export const invocation={...object({
  className:string('Fully qualified declared guest class; the class must belong to the guest ClassLoader.',512,1),
  methodName:{...string('Declared method name; constructors are unsupported.',128,1),pattern:'^[^<].*$'},
  argumentTypes:{type:'array',maxItems:16,items:{type:'string',enum:javaTypes},description:'Exact declared Java types in order. Use [] for a zero-argument method.'},
  arguments:{type:'array',maxItems:16,items:{anyOf:[...scalar.anyOf,{type:'null',description:'Null for nullable String or byte[] only.'}]},description:'One value per argumentType in the same order. byte/short/int use bounded JSON integers, boolean uses a JSON boolean, long MUST be a decimal string in signed 64-bit range, byte[] is an even-length hex string (max 4096 bytes), char is one UTF-16 code unit. Only String/byte[] accept null. Objects/arrays are unsupported.'},
}),description:'Invoke a guest method. Instance methods require a no-argument constructor and create a new instance; this does not select an existing instance.',examples:[{className:'com.example.Counter',methodName:'value',argumentTypes:[],arguments:[]}]};
const flatValue={description:'Scalar extra: string, boolean or number. Integers become int (or long when out of int range); other numbers become double.',anyOf:[string('String value.',2048),{type:'boolean',description:'Boolean value.'},number]};
const extrasMap=(description,maxProperties=32)=>({type:'object',description,maxProperties,propertyNames:{type:'string',minLength:1,maxLength:128},additionalProperties:{description:'Scalar, or one nested object of scalars that becomes a Bundle.',anyOf:[...flatValue.anyOf,{type:'object',description:'Nested Bundle of scalar extras (one level only).',maxProperties:32,propertyNames:{type:'string',minLength:1,maxLength:128},additionalProperties:flatValue}]}});
export const componentRequest={...object({
  kind:{type:'string',enum:['activity','service','broadcast'],description:'Component class to deliver to.'},
  component:string('Flattened ComponentName "package/class" (package/.Relative also accepted). Required for activity and service; empty for an implicit broadcast.',512),
  action:string('Intent action; empty for none.',256),
  dataUri:string('Intent data URI, e.g. a deeplink; empty for none.',2048),
  mimeType:string('Intent MIME type; empty for none.',128),
  categories:{type:'array',maxItems:8,items:string('Intent category.',256,1),description:'Intent categories.'},
  flags:{type:'integer',minimum:0,maximum:2147483647,description:'Extra Intent flags; activities always also get FLAG_ACTIVITY_NEW_TASK.'},
  extras:extrasMap('Intent extras keyed by name.'),
},['kind','component']),description:'Explicit Intent delivered as the guest application itself, so the engine routing for its own components applies. Dispatch acceptance does not prove the component ran.',examples:[{kind:'activity',component:'dev.tripoder.fixture/.FixtureActivity',action:'android.intent.action.VIEW',dataUri:'tripoder-fixture://open/x',extras:{who:'agent'}},{kind:'broadcast',component:'',action:'dev.tripoder.fixture.PING',extras:{n:3}}]};
export const providerRequest={...object({
  operation:{type:'string',enum:['query','insert','update','delete','call'],description:'ContentResolver operation. All of them are treated as mutating, including query. openFile is not offered: the engine provider layer does not implement it.'},
  uri:{...string('content:// URI of the target provider.',2048,1),pattern:'^content://[^\\s/]+'},
  projection:{type:'array',maxItems:32,items:string('Column name.',128,1),description:'query: columns to return; omit for all.'},
  selection:string('query/update/delete: SQL selection with ? placeholders; parameterize via selectionArgs.',1024),
  selectionArgs:{type:'array',maxItems:16,items:string('Selection argument.',512),description:'Values for selection placeholders.'},
  sortOrder:string('query: sort order.',128),
  limit:{type:'integer',minimum:1,maximum:100,description:'query: maximum rows returned, default 20; truncated=true when more exist.'},
  values:{type:'object',description:'insert/update: column values (string, boolean, number or null).',maxProperties:32,propertyNames:{type:'string',minLength:1,maxLength:128},additionalProperties:{description:'Column value.',anyOf:[...flatValue.anyOf,{type:'null',description:'SQL NULL.'}]}},
  method:string('call: provider method name.',128),
  arg:string('call: optional string argument.',2048),
  extras:extrasMap('call: extras Bundle.'),
},['operation','uri']),description:'ContentResolver access as the guest application. Cells are bounded; blobs are returned as bounded hex.',examples:[{operation:'query',uri:'content://dev.tripoder.fixture.items/items',limit:10},{operation:'call',uri:'content://dev.tripoder.fixture.items',method:'echo',arg:'x'}]};
export const privacyAttributionEntry={...object({
  packagePrefix:{...string('Java package prefix, e.g. "com.example.adsdk". Matches the class itself or any subpackage.',256,1),pattern:'^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*$'},
  sdkName:string('Human-readable attributed SDK/library name.',128,1),
}),description:'One package-prefix to SDK-name attribution entry.'};
export const parameters={type:'object',description:'Patch script-defined scalar parameters. Read hook_status for current values/revision/version and the project source for parameter declarations; omitted names retain their values. Runtime enforces the compiled plan and an 8192-character total budget.',maxProperties:64,propertyNames:{type:'string',maxLength:8192},additionalProperties:scalar,examples:[{enabled:true,label:'example',count:3}]};

const meanings={
  captureId:'Exact passive capture ID returned by network_control(action=start). A cursor belongs to this capture only.',
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
  if(copy.type==='integer'){copy.maximum=Math.min(copy.maximum??Number.MAX_SAFE_INTEGER,Number.MAX_SAFE_INTEGER);if(key==='cursor')copy.maximum=Math.min(copy.maximum,2147483647);}
  if(copy.type==='string'&&copy.maxLength===undefined)copy.maxLength=32000;
  if(copy.type==='string'&&key==='package')copy.maxLength=256;
  if(copy.type==='string'&&['space','installGeneration','processGeneration','parentClassLoaderId','dexId','artifactId','archiveId','evidenceId','token','revision'].includes(key))copy.maxLength=80;
  if(copy.properties)copy.properties=Object.fromEntries(Object.entries(copy.properties).map(([k,v])=>[k,describeSchema(v,k)]));
  if(copy.anyOf)copy.anyOf=copy.anyOf.map(s=>describeSchema(s,key));
  if(copy.items)copy.items=describeSchema(copy.items,key);
  if(copy.additionalProperties&&typeof copy.additionalProperties==='object')copy.additionalProperties=describeSchema(copy.additionalProperties,key);
  return copy;
}

export function validateInvocation(call){
  if(call.argumentTypes.length!==call.arguments.length)throw argumentError('/invocation/arguments','ARGUMENT_COUNT_MISMATCH','one value per declared argument');
  call.argumentTypes.forEach((type,index)=>{
    const value=call.arguments[index];let valid=false;
    if(type==='java.lang.String')valid=value===null||typeof value==='string';
    else if(type==='[B'||type==='byte[]')valid=value===null||typeof value==='string'&&value.length<=8192&&value.length%2===0&&/^[0-9a-fA-F]*$/.test(value);
    else if(type==='char')valid=typeof value==='string'&&value.length===1;
    else if(type==='boolean')valid=typeof value==='boolean';
    else if(type==='long')valid=typeof value==='string'&&/^-?[0-9]{1,20}$/.test(value)&&BigInt(value)>=-(1n<<63n)&&BigInt(value)<(1n<<63n);
    else if(type==='float'||type==='double')valid=typeof value==='number'&&Number.isFinite(value)&&(type==='double'||Number.isFinite(Math.fround(value)));
    else {const bounds={byte:[-128,127],short:[-32768,32767],int:[-2147483648,2147483647]}[type];valid=!!bounds&&Number.isInteger(value)&&value>=bounds[0]&&value<=bounds[1];}
    if(!valid)throw argumentError('/invocation/arguments/'+index,'INVOCATION_VALUE_INVALID',type);
  });
}
