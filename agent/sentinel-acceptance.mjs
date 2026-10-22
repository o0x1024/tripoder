import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import {loadConfig} from './transport.mjs';
const [connection,fixture,evidence]=process.argv.slice(2);
if(!connection||!fixture||!evidence)throw Error('Usage: sentinel-acceptance.mjs CONNECTION_FILE FIXTURE_JSON EVIDENCE_DIRECTORY');
await loadConfig(resolve(connection));
const data=JSON.parse(await readFile(fixture,'utf8'));
await mkdir(evidence,{recursive:true});
const importFile=resolve(evidence,'sentinel-mcp.json'),audit=resolve(evidence,'sentinel-mcp-audit.jsonl');
await writeFile(importFile,JSON.stringify({mcpServers:{tripoder:{type:'stdio',command:process.execPath,args:[resolve('agent/server.mjs'),'--connection',resolve(connection),'--audit',audit]}}},null,2)+'\n');
const prompt=`通过 tripoder MCP 验证当前客体连接，无需配对或手机审批：
1. application_query(action=targets)，确认 project=${data.project}、processGeneration=${data.processGeneration}、pid=${data.pid}。
2. 从匹配行取得 space、package、installGeneration、processGeneration，组成 target；目标不匹配则停止。
3. application_query(action=process) 和 process_inspect(action=hook_status) 每次显式传入上述 target，报告实际目标、状态、命中和错误数。
只读验证，不修改脚本或应用。读取工具内容 JSON 的 ok 字段；工具外层成功不等于业务成功。
`;
await writeFile(resolve(evidence,'sentinel-task.md'),prompt);
console.log(JSON.stringify({importFile,promptFile:resolve(evidence,'sentinel-task.md'),auditFile:audit}));
