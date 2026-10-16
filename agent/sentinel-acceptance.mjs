import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
const [config,fixture,evidence]=process.argv.slice(2);
const data=JSON.parse(await readFile(fixture,'utf8')),run=data.runId;
const importFile=resolve(evidence,'sentinel-mcp.json'),audit=resolve(evidence,'sentinel-mcp-audit.jsonl');
await writeFile(importFile,JSON.stringify({mcpServers:{tripoder:{type:'stdio',command:process.execPath,args:[resolve('agent/server.mjs'),'--config',resolve(config),'--audit',audit],env:{}}}},null,2)+'\n');
const prompt=`TRIPODER_ACCEPTANCE_RUN_ID=${run}

执行 Tripoder 自研计数器 MCP 验收，标识 ${run}。只使用 tripoder 的 MCP 工具，不访问文件、终端或其他应用数据。手机测试程序会配合审批准确源码、操作计数器和检查原始行为。

顺序：
1. session_get 与 process_get，确认 project=${data.project}、pid=${data.pid}，不匹配或会话不是 OPEN 立即停止。
2. hook_observe，operationId=${run}-observe，selectors=[{"id":"counter","kind":"java","className":"dev.tripoder.previewguest.CounterActivity","methodName":"formatCount","argumentTypes":["int"]}]。
3. 使用返回 evidence.id 调用 hook_prepare，operationId=${run}-prepare，source 必须为下方代码块的准确内容（保留末尾换行）。
4. 查询 session_get，直到 approvedSourceSha256 等于 prepare 返回的 sourceSha256；等待期间每次间隔几秒，最多 20 次。未批准不能 activate。
5. hook_activate，operationId=${run}-activate，使用刚准备的 token 与源码 SHA。必须读回执 ok=true、state=ACTIVE。
6. 查询 hook_status，直到 hits>=2 且 errors=0。手机会实际点击；最多 20 次，不要过早停用。
7. hook_disable，operationId=${run}-disable，revision 必须取当前 hook_status。确认 DISABLED；不要重新启动应用。手机测试程序将实际验证后续调用恢复原始计数，并撤销控制会话。
8. 此时结束工具调用，报告各操作 ID、命中数与停用结果。不要提前调用 session_close，以便手机完成原始行为验证；由手机测试程序执行撤销。工具外层成功不代表业务成功；如 UNKNOWN 只查原 operation_get，不更换 ID 重放。

准确 source（SHA-256 ${createHash('sha256').update(data.source).digest('hex')}）：
\`\`\`typescript
${data.source}\`\`\`
`;
await writeFile(resolve(evidence,'sentinel-task.md'),prompt);
console.log(JSON.stringify({runId:run,importFile,promptFile:resolve(evidence,'sentinel-task.md'),auditFile:audit}));
