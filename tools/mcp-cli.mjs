// Minimal stdio MCP client for Tripoder: list tools or call one tool.
// usage: node tools/mcp-cli.mjs list
//        node tools/mcp-cli.mjs call <toolName> '<json args>'
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';

const connection=process.env.TRIPODER_CONNECTION;if(!connection)throw Error('TRIPODER_CONNECTION must be an absolute exported connection file path');
const child = spawn(process.execPath, ['agent/server.mjs', '--connection', connection], {
  cwd: new URL('..', import.meta.url).pathname,
  stdio: ['pipe', 'pipe', 'pipe'],
});
const pending = new Map();
let seq = 0;
createInterface({ input: child.stdout }).on('line', (line) => {
  let msg;
  try { msg = JSON.parse(line); } catch { console.error('bad line', line); return; }
  if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); }
});
child.stderr.on('data', (b) => process.stderr.write('[server] ' + b));

function call(method, params = {}, timeout = 120000) {
  const id = ++seq;
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => { pending.delete(id); reject(new Error(`timeout ${method}`)); }, timeout);
    pending.set(id, (m) => { clearTimeout(t); resolve(m); });
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
  });
}

const mode = process.argv[2] || 'list';
await call('initialize', {
  protocolVersion: '2025-11-25',
  capabilities: {},
  clientInfo: { name: 'tripoder-mcp-cli', version: '0.1.0' },
});
child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');

try {
  if (mode === 'list') {
    const res = await call('tools/list');
    for (const t of res.result.tools) console.log(`${t.name}: ${(t.description || '').split('\n')[0]}`);
  } else {
    const name = process.argv[3];
    const args = process.argv[4] ? JSON.parse(process.argv[4]) : {};
    const res = await call('tools/call', { name, arguments: args }, Number(process.env.MCP_TIMEOUT || 180000));
    console.log(JSON.stringify(res.result ?? res.error, null, 2));
  }
} catch (e) {
  console.error('ERR', e.message);
  process.exitCode = 1;
} finally {
  child.stdin.end();
  setTimeout(() => process.exit(), 300);
}
