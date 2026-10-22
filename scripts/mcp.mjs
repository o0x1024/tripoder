// Tripoder MCP stdio client using the exported connection config (current contract).
// usage:
//   node scripts/mcp.mjs list
//   node scripts/mcp.mjs call <tool> '<json args>'
//   node scripts/mcp.mjs batch '<json [{"tool":..,"args":{}}]>'
//   node scripts/mcp.mjs batch @file.json
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { readFile } from 'node:fs/promises';
import { resolve, isAbsolute } from 'node:path';
import { randomUUID } from 'node:crypto';

const connection = process.env.TRIPODER_CONNECTION || resolve(process.env.HOME, '.tripoder/tripoder-connection.json');
if (!isAbsolute(connection)) throw new Error('CONNECTION_ABSOLUTE_PATH_REQUIRED');
const toolTimeout = Number(process.env.MCP_TIMEOUT || 180000);
const root = resolve(import.meta.dirname, '..');

const child = spawn(process.execPath, [resolve(root, 'agent/server.mjs'), '--connection', connection], {
  stdio: ['pipe', 'pipe', 'pipe'],
});
let stderr = '';
child.stderr.on('data', (b) => { stderr = (stderr + b.toString()).slice(-8192); });
const pending = new Map();
createInterface({ input: child.stdout }).on('line', (line) => {
  let msg; try { msg = JSON.parse(line); } catch { return; }
  const slot = pending.get(msg.id);
  if (slot) { pending.delete(msg.id); slot(msg); }
});
function rpc(method, params) {
  const id = randomUUID();
  return new Promise((res, rej) => {
    const timer = setTimeout(() => { pending.delete(id); rej(new Error('MCP_TIMEOUT ' + method)); }, toolTimeout);
    pending.set(id, (m) => { clearTimeout(timer); res(m); });
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
  });
}
const unwrap = (response) => {
  if (response.error) return { error: response.error };
  const result = response.result ?? {};
  const text = (result.content ?? []).map((c) => c.text ?? '').join('\n');
  let parsed; try { parsed = JSON.parse(text); } catch { parsed = null; }
  return parsed ?? result;
};

const argv = process.argv.slice(2);
const mode = argv[0] || 'list';
let out;
try {
  const init = await rpc('initialize', { protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name: 'tripoder-mcp-cli', version: '1' } });
  if (init.error) throw new Error('INIT_FAILED ' + JSON.stringify(init.error));
  child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized', params: {} }) + '\n');
  if (mode === 'list') {
    const list = unwrap(await rpc('tools/list', {}));
    out = list;
  } else if (mode === 'call') {
    out = unwrap(await rpc('tools/call', { name: argv[1], arguments: argv[2] ? JSON.parse(argv[2]) : {} }));
  } else if (mode === 'batch') {
    let calls;
    if (argv[1]?.startsWith('@')) calls = JSON.parse(await readFile(resolve(argv[1].slice(1)), 'utf8'));
    else calls = JSON.parse(argv[1]);
    out = [];
    for (const call of calls) {
      const started = Date.now();
      let value;
      try { value = unwrap(await rpc('tools/call', { name: call.tool, arguments: call.args ?? {} })); }
      catch (error) { value = { error: error.message }; }
      out.push({ tool: call.tool, ms: Date.now() - started, result: value });
      if (call.stopIf && call.stopIf(value)) break;
    }
  } else {
    throw new Error('Unknown mode ' + mode);
  }
} catch (error) {
  console.log(JSON.stringify({ client_error: error.message, stderr }, null, 2));
  process.exitCode = 1;
} finally {
  child.stdin.end();
  setTimeout(() => child.kill('SIGKILL'), 800).unref();
}
if (out !== undefined) console.log(JSON.stringify(out, null, 2));
