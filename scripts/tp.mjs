// Minimal stdio MCP client for Tripoder: node scripts/tp.mjs <tool|--tools> [jsonArgs] [--timeout ms]
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';

const argv = process.argv.slice(2);
const timeoutIdx = argv.indexOf('--timeout');
const timeoutMs = timeoutIdx >= 0 ? Number(argv.splice(timeoutIdx, 2)[1]) : 90000;
const serialIdx = argv.indexOf('--serial');
const serial = serialIdx >= 0 ? argv.splice(serialIdx, 2)[1] : process.env.ANDROID_SERIAL || 'd861a4fa';
const tool = argv[0];
const args = argv[1] ? JSON.parse(argv[1]) : {};

const root = resolve(import.meta.dirname, '..');
const child = spawn(process.execPath, [resolve(root, 'agent/server.mjs'), '--serial', serial], {
  stdio: ['pipe', 'pipe', 'pipe'],
});
let stderr = '';
child.stderr.on('data', (b) => { stderr += b.toString(); });
const pending = new Map();
createInterface({ input: child.stdout }).on('line', (line) => {
  let msg;
  try { msg = JSON.parse(line); } catch { return; }
  const slot = pending.get(msg.id);
  if (slot) { pending.delete(msg.id); slot(msg); }
});
function rpc(method, params) {
  const id = randomUUID();
  const payload = { jsonrpc: '2.0', id, method, params };
  return new Promise((res, rej) => {
    const timer = setTimeout(() => { pending.delete(id); rej(new Error('MCP_TIMEOUT ' + method)); }, timeoutMs);
    pending.set(id, (msg) => { clearTimeout(timer); res(msg); });
    child.stdin.write(JSON.stringify(payload) + '\n');
  });
}
function notify(method, params) { child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method, params }) + '\n'); }

try {
  const init = await rpc('initialize', {
    protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'tp-cli', version: '1' },
  });
  if (init.error) throw new Error('INIT_FAILED ' + JSON.stringify(init.error));
  notify('notifications/initialized', {});
  if (tool === '--tools') {
    const list = await rpc('tools/list', {});
    console.log(JSON.stringify(list.result ?? list.error, null, 2));
  } else {
    const response = await rpc('tools/call', { name: tool, arguments: args });
    if (response.error) { console.error(JSON.stringify(response.error)); process.exitCode = 1; }
    else {
      const result = response.result ?? {};
      const text = (result.content ?? []).map((c) => c.text ?? '').join('\n');
      let parsed;
      try { parsed = JSON.parse(text); } catch { parsed = null; }
      console.log(JSON.stringify(parsed ?? result, null, 2));
      if (result.isError) process.exitCode = 1;
    }
  }
} catch (err) {
  console.error('CLIENT_ERROR', err.message, stderr.slice(-4000));
  process.exitCode = 1;
} finally {
  child.stdin.end();
  setTimeout(() => child.kill('SIGKILL'), 500).unref();
}
