import { build } from "esbuild";
import { parseExpression } from "@babel/parser";
import { mkdir, writeFile, readFile, readdir } from "node:fs/promises";
import { fileURLToPath, pathToFileURL } from "node:url";
import { resolve, dirname, join } from "node:path";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "../../..");
const output = join(here, "build");
await mkdir(output, { recursive: true });
await build({ entryPoints: [join(root, "src/draft.ts")], bundle: true, platform: "node", format: "esm", outfile: join(output, "draft-oracle.mjs") });
const { prepareDraft, draftTemplate } = await import(pathToFileURL(join(output, "draft-oracle.mjs")));
const observed = { id: "owned-host-evidence", source: "fixture", target: { packageName: "owned.fixture", versionCode: "1", apkSha256: "a".repeat(64) },
  processInstanceId: "owned-host:1", abi: "arm64-v8a", capabilities: ["java-hook"], symbols: [{ id: "owned", kind: "java", loaded: true,
    className: "owned.Fixture", methodName: "call", classLoaderId: "owned-loader", argumentTypes: [], returnType: "int" }] };
const expressions = [
  "({ args }) => args[0] + 2", "({ args }) => args[0] * 0.5", "({ args }) => args[0] + 1n",
  "({ args }) => args[0] > 9007199254740992n", "({ args }) => args[0] === 9007199254740993n",
  "({ args }) => args[0] < 9007199254740992", "({ args }) => args[0] > 9007199254740992",
  "({ args }) => [args[0], null, undefined]", "({ args }) => ({ result: args[0], ok: true })",
  "({ args }) => args[0] ? 'yes' : 'no'", "({ args }) => args[0] ?? 'none'", "({ args }) => args[0] || 'none'",
  "({ args }) => args[0] && 2", "({ args }) => typeof args[0]", "({ args }) => -args[0]",
  "({ args }) => String(args[0])", "({ args }) => Number(args[0])", "({ args }) => BigInt(args[0])",
  "({ args }) => Boolean(args[0])", "({ args }) => Math.round(args[0])", "({ args }) => Math.abs(args[0])",
  "({ args }) => Math.min(args[0], 0)", "({ args }) => Math.max(args[0], 0)",
  "({ args }) => args[0].trim().toUpperCase()", "({ args }) => args[0].slice(-2)",
  "({ args }) => args[0].substring(3, 1)", "({ args }) => args[0].includes('a', 1)",
  "({ args }) => args[0].startsWith('a', 1)", "({ args }) => args[0].endsWith('a', 3)",
  "({ args }) => `${args[0]}!`", "({ args, params }) => { const n = args[0] + params.delta; if (n > 5) return n; return 0; }",
  "({ args }) => { const f = x => x + 3; return f(args[0]); }", "({ args }) => args[0] as number",
  "({ args }) => args[0] < args[1]", "({ args }) => args[0] >= args[1]",
  "({ args }) => { const f = () => later; const later = args[0]; return f(); }",
];
const cases = [
  [0, [3]], [1, [3]], [2, [9223372036854775806n]], [3, [9007199254740993n]], [4, [9007199254740993n]],
  [5, [9007199254740993n]], [6, [9007199254740993n]], [7, [1]], [8, [null]],
  [9, [0]], [9, [1]], [10, [null]], [10, [false]], [11, [0]], [12, [true]], [13, [null]], [13, [undefined]],
  [13, [1n]], [14, [-0]], [14, [3n]], [15, [1e23]], [15, [1e-7]], [15, [1e-6]], [15, [1e20]],
  [15, [Number.MIN_VALUE]], [15, [Number.MAX_VALUE]], [15, [1000000000000000100]],
  [16, ["0x10"]], [16, ["\u00a0 4 \ufeff"]], [17, ["9223372036854775807"]], [18, [""]],
  [19, [-0.5]], [19, [-1.5]], [19, [2.5]], [20, [-0]], [21, [-0]], [22, [-0]],
  [23, ["\ufeff ßa \u00a0"]], [24, ["abcd"]], [25, ["abcd"]], [26, ["baac"]], [27, ["baac"]],
  [28, ["baac"]], [29, [9223372036854775807n]], [30, [4]], [31, [4]], [32, [4]],
  [33, [1000000000000000129n, 1000000000000000100]], [33, [1n,"1.5"]], [34, [1n,"1.5"]],
  [33, [undefined,1]], [34, [undefined,1]], [33, [1,"invalid"]], [34,[1,"Infinity"]], [35,[7]],
  [19,[4503599627370495]], [19,[-0]], [19,[0.49999999999999994]],
];
const metadata = new Set(["start", "end", "loc", "extra", "comments", "leadingComments", "trailingComments", "innerComments", "typeAnnotation", "returnType", "typeParameters", "typeArguments"]);
function strip(value) {
  if (Array.isArray(value)) return value.map(strip);
  if (value && typeof value === "object") return Object.fromEntries(Object.entries(value).filter(([k,v]) => !metadata.has(k) && v !== undefined).map(([k,v]) => [k,strip(v)]));
  return value;
}
function java(value) {
  if (value === undefined) return "ScriptValues.UNDEFINED";
  if (value === null) return "null";
  if (typeof value === "bigint") return `new java.math.BigInteger("${value}")`;
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new Error("nonfinite fixture");
    return Object.is(value, -0) ? "-0.0d" : `${value}d`;
  }
  if (typeof value === "string") return JSON.stringify(value).replaceAll("\\u2028", "\\u2028");
  if (typeof value === "boolean") return String(value);
  if (Array.isArray(value)) return `l(new Object[]{${value.map(java).join(",")}})`;
  return `m(${Object.entries(value).flatMap(([k,v]) => [java(k),java(v)]).join(",")})`;
}
let generated = `package dev.tripoder.runtime.script;\nimport java.util.*;\nimport static dev.tripoder.runtime.script.ScriptContractTest.*;\npublic final class OracleFixtures { public static void run() throws Throwable {\n`;
for (const [i, [which, args]] of cases.entries()) {
  const expression = expressions[which];
  const source = `import { definePlan } from '@abox/hook'; export default definePlan({id:'owned',target,params:{},setup(ctx){ctx.java.method('owned.Fixture','call',[]).replace(${expression});}});`;
  const plan = prepareDraft(source, observed);
  const call = { args, params: { delta: 2 } };
  const expected = plan.rules[0].handlers.replace(call);
  const ast = strip(parseExpression(expression, { plugins: ["typescript"] }));
  generated += `{ Object actual=RestrictedAst.compile(${java(ast)},Map.of()).invoke(${java(call)}); check(java.util.Objects.equals(actual,${java(expected)}),"Babel/SDK oracle ${i}"); }\n`;
  generated += `{ DraftCompiler.Plan plan=DraftCompiler.compile(${java(source)},new HookEvidence(${java(observed)})); Object actual=plan.rules().get(0).replace.invoke(${java(call)}); check(java.util.Objects.equals(actual,${java(expected)}),"source compiler oracle ${i}"); check(plan.sourceSha256().equals(${java(createHash("sha256").update(source).digest("hex"))}),"source binding ${i}"); }\n`;
}
const baseSource="import {definePlan} from '@abox/hook'; export default definePlan({id:'owned',target,params:{},setup(ctx){ctx.java.method('owned.Fixture','call',[]).replace(()=>1);}});";
const negativeSources=[
  baseSource.replace("'@abox/hook'","'node:fs'"),baseSource.replace("definePlan}","missing}"),
  baseSource.replace("()=>1","async()=>1"),baseSource.replace("()=>1","()=>{while(true){}return 1;}"),
  baseSource.replace("()=>1","()=>{let x=1;return x;}"),baseSource.replace("()=>1","()=>{const x=1;return x=2;}"),
  baseSource.replace("()=>1","()=>new Object()"),baseSource.replace("()=>1","()=>({get value(){return 1;}})"),
  baseSource.replace("()=>1","()=>({constructor:1})"),baseSource.replace("()=>1","(a,a)=>1"),
  baseSource.replace("()=>1","()=>{const a=1;const a=2;return a;}"),baseSource.replace("()=>1","()=>1 ?? 2 || 3"),
  baseSource.replace("()=>1","()=>{if(true)const a=1;return 1;}"),"return 1;"+baseSource,
  baseSource.replace("replace(()=>1)","replace(()=>1).before(()=>[])"),baseSource.replace("id:'owned'","id:'bad id'"),
  baseSource.replace("'call'","'missing'"),baseSource.replace("setup(ctx){","setup(ctx){fetch('https://invalid.example');"),
];
for(const source of negativeSources){let failed=false;try{prepareDraft(source,observed);}catch{failed=true;}if(!failed)throw new Error("negative oracle accepted: "+source);}
generated += "negativeCases(); completePlan(); numericFormatting(); } private static void negativeCases() {\n";
for(const [i,source] of negativeSources.entries())generated+=`try { DraftCompiler.compile(${java(source)},new HookEvidence(${java(observed)})); throw new AssertionError("negative source accepted ${i}"); } catch(ScriptFault expected) {check(true,"Babel negative source ${i}");}\n`;
const templateEvidence={...observed,capabilities:["java-hook","native-hook"],symbols:[
  {...observed.symbols[0],id:"greet",methodName:"greet",argumentTypes:["java.lang.String"],returnType:"java.lang.String"},
  {id:"delay",kind:"native",loaded:true,moduleName:"libowned.so",symbol:"owned_delay",buildId:"aabb",argumentTypes:["int32"],returnType:"int32",signatureSource:"confirmed"},
]};
const templateSource=draftTemplate(),templatePlan=prepareDraft(templateSource,templateEvidence);
generated += `} private static void completePlan() throws Throwable { DraftCompiler.Plan plan=DraftCompiler.compile(${java(templateSource)},new HookEvidence(${java(templateEvidence)}));\n`;
generated += `check(plan.parameters().equals(${java(templatePlan.parameters)}),"existing SDK template parameters"); check(plan.rules().size()==2,"existing SDK template rule count");\n`;
for(const [i,args] of [[0,["hello"]],[1,[3]]]){
  const expected=templatePlan.rules[i].handlers.replace({args,params:templatePlan.parameters});
  generated+=`check(plan.rules().get(${i}).execute(plan.parameters(),${java(args)},values->{throw new AssertionError("template replace called original");}).equals(${java(expected)}),"SDK complete plan Java/Native ${i}");\n`;
}
generated += "} private static void numericFormatting() {\n";
let seed=0x51c0ffee12345678n;
const numericFormattingCases=256;
for(let i=0;i<numericFormattingCases;i++) {
  seed=BigInt.asUintN(64,seed^seed<<13n);seed=BigInt.asUintN(64,seed^seed>>7n);seed=BigInt.asUintN(64,seed^seed<<17n);
  const bits=seed&~(0x7ffn<<52n)|(seed>>11n&0x7fen)<<52n;
  const buffer=new ArrayBuffer(8),view=new DataView(buffer);view.setBigUint64(0,bits);const n=view.getFloat64(0);
  generated+=`check(ScriptValues.text(Double.longBitsToDouble(Long.parseUnsignedLong("${bits.toString(16)}",16))).equals(${java(String(n))}),"number-format ${i}");\n`;
}
generated += "} }\n";
await writeFile(join(output, "OracleFixtures.java"), generated);
const core = join(root, "android/runtime/src/dev/tripoder/runtime/script");
const sources = (await readdir(core)).filter(n => n.endsWith(".java")).map(n => join(core, n));
sources.push(...["NativeHook.java","NativeModules.java"].map(name=>join(root,"android/self-hook/java/dev/tripoder/selfhook",name)));
const javaHome = process.env.TRIPODER_JAVA_HOME ?? "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home";
let testOutput="";
const tests=(await readdir(here)).filter(n=>n.endsWith(".java")).map(n=>join(here,n));
for (const [command, args] of [[join(javaHome,"bin/javac"), ["--release", "17", "-Xlint:all", "-d", output, ...sources, ...tests, join(output,"OracleFixtures.java")]],
                              [join(javaHome,"bin/java"), ["-ea", "-cp", output, "dev.tripoder.runtime.script.ScriptContractTest"]]]) {
  const result = spawnSync(command, args, { encoding: "utf8" });
  process.stdout.write(result.stdout ?? "");process.stderr.write(result.stderr ?? "");
  if (result.status !== 0) process.exit(result.status ?? 1);
  if(command.endsWith("/java"))testOutput=result.stdout;
}
const hashed = [...sources, ...tests, fileURLToPath(import.meta.url), ...["draft.ts","plan.ts","evidence.ts","parameters.ts"].map(f => join(root,"src",f))];
const sha256 = data => createHash("sha256").update(data).digest("hex");
const sourceHashes = Object.fromEntries(await Promise.all(hashed.map(async f => [f.slice(root.length+1),sha256(await readFile(f))])));
const checks=Number(/SCRIPT_CONTRACT_PASS checks=(\d+)/.exec(testOutput)?.[1]);
if(!Number.isSafeInteger(checks)||checks<=0)throw new Error("missing contract completion");
await writeFile(join(output,"report.json"), JSON.stringify({ status:"passed", checks, scope: "owned-host-contract-only", oracleCases: cases.length, negativeSourceOracleCases:negativeSources.length, numericFormattingCases, sourceHashes,
  babelVersion: JSON.parse(await readFile(join(root,"node_modules/@babel/parser/package.json"),"utf8")).version,
  generatedOracleSha256:sha256(generated),
  javaVersion: spawnSync(join(javaHome,"bin/java"), ["-version"], {encoding:"utf8"}).stderr.trim(), productBackend: "not-connected", deviceTested: false }, null, 2)+"\n");
