// Persistent stdio MCP client + adb helpers used by the compliance/functional QA harness.
import { spawn, execFile } from 'node:child_process';
import { createInterface } from 'node:readline';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';

const ROOT = resolve(import.meta.dirname, '..', '..');
export const SERIAL = process.env.ANDROID_SERIAL || 'd861a4fa';
export const HOST_PACKAGE = process.env.TP_HOST_PACKAGE || 'dev.tripoder.engine';
export const CONNECTION = process.env.TRIPODER_CONNECTION || resolve(process.env.HOME, '.tripoder/tripoder-connection.json');

export class Mcp {
  constructor({ serial = SERIAL, timeoutMs = 120000 } = {}) {
    this.serial = serial;
    this.timeoutMs = timeoutMs;
    this.pending = new Map();
    this.stderr = '';
    this.spawnChild();
  }
  /** (Re)create the stdio adapter. A phone-side engine restart drops the HTTPS session. */
  spawnChild() {
    if (this.child) { try { this.child.kill('SIGKILL'); } catch {} }
    this.pending.clear();
    this.child = spawn(process.execPath, [resolve(ROOT, 'agent/server.mjs'), '--connection', CONNECTION], {
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    this.child.stderr.on('data', (b) => { this.stderr = (this.stderr + b.toString()).slice(-8000); });
    createInterface({ input: this.child.stdout }).on('line', (line) => {
      let msg; try { msg = JSON.parse(line); } catch { return; }
      const slot = this.pending.get(msg.id);
      if (slot) { this.pending.delete(msg.id); slot(msg); }
    });
  }
  rpc(method, params, timeoutMs = this.timeoutMs) {
    const id = randomUUID();
    return new Promise((res, rej) => {
      const timer = setTimeout(() => { this.pending.delete(id); rej(new Error('MCP_TIMEOUT ' + method)); }, timeoutMs);
      this.pending.set(id, (msg) => { clearTimeout(timer); res(msg); });
      this.child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    });
  }
  async init({ attempts = 5 } = {}) {
    for (let attempt = 1; ; attempt++) {
      try {
        const r = await this.rpc('initialize', { protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name: 'tripoder-qa', version: '1' } }, 30000);
        if (r.error) throw new Error('INIT_FAILED ' + JSON.stringify(r.error));
        this.child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized', params: {} }) + '\n');
        return this;
      } catch (error) {
        if (attempt >= attempts) throw error;
        console.log('[mcp] init retry', attempt, String(error.message).slice(0, 80));
        this.spawnChild();
        await new Promise((r) => setTimeout(r, 5000));
      }
    }
  }
  async call(tool, args = {}, { timeoutMs } = {}) {
    const response = await this.rpc('tools/call', { name: tool, arguments: args }, timeoutMs);
    if (response.error) throw new Error(`${tool} RPC_ERROR ${JSON.stringify(response.error)}`);
    const result = response.result ?? {};
    const text = (result.content ?? []).map((c) => c.text ?? '').join('\n');
    let parsed = null; try { parsed = JSON.parse(text); } catch { parsed = null; }
    if (result.isError) {
      const err = new Error(`${tool} TOOL_ERROR ${text.slice(0, 1200)}`);
      err.payload = parsed;
      throw err;
    }
    if (parsed && typeof parsed === 'object' && 'ok' in parsed) {
      if (parsed.ok === false) {
        const err = new Error(`${tool} NOT_OK ${JSON.stringify(parsed).slice(0, 1200)}`);
        err.payload = parsed;
        throw err;
      }
      return parsed.result ?? parsed;
    }
    return parsed ?? result;
  }
  async close() {
    try { this.child.stdin.end(); } catch {}
    setTimeout(() => this.child.kill('SIGKILL'), 300).unref();
  }
}

export function adb(args, { timeout = 60000, buffer = false } = {}) {
  return new Promise((res, rej) => {
    execFile('adb', ['-s', SERIAL, ...args], { timeout, encoding: buffer ? 'buffer' : 'utf8', maxBuffer: 64 * 1024 * 1024 }, (err, stdout, stderr) => {
      if (err) { err.stdout = stdout; err.stderr = stderr; return rej(err); }
      res(stdout);
    });
  });
}

export async function shell(cmd) { return adb(['shell', cmd]); }
export async function ensureDir(dir) { await mkdir(dir, { recursive: true }); return dir; }
export async function saveShot(dir, name) {
  await ensureDir(dir);
  const remote = `/sdcard/qa-shot-${Date.now()}.png`;
  await adb(['shell', `screencap -p ${remote}`], { timeout: 40000 });
  const local = resolve(dir, name.endsWith('.png') ? name : name + '.png');
  await adb(['pull', remote, local], { timeout: 40000 });
  await adb(['shell', `rm -f ${remote}`]).catch(() => {});
  return local;
}
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Newest SMS bodies (device SMS store), used for login verification codes. */
export async function recentSms(limit = 5) {
  const out = await shell(`content query --uri content://sms/inbox --projection _id,address,date,body --sort "date DESC" 2>/dev/null | head -${limit}`);
  return out.split('\n').filter(Boolean).map((line) => {
    const m = line.match(/^Row: \d+ _id=(\d+), address=([^,]*), date=(\d+), body=(.*)$/s);
    return m ? { id: Number(m[1]), address: m[2], date: Number(m[3]), body: m[4] } : { raw: line };
  });
}

/** Wait for a verification code that arrives after `sinceMs`. */
export async function waitForCode({ sinceMs = Date.now(), needFrom = '', timeoutMs = 90000 } = {}) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const rows = await recentSms(8).catch(() => []);
    for (const row of rows) {
      if (!row.body || row.date < sinceMs - 5000) continue;
      if (needFrom && !row.address.includes(needFrom)) continue;
      const m = row.body.match(/(?:验证码|校验码|动态码|code)[^0-9]{0,12}(\d{4,8})/i)
        || row.body.match(/(\d{4,8})[^0-9]{0,6}(?:为|是)?(?:您的)?(?:登录|验证)?(?:验证码|动态码)/);
      if (m) return { code: m[1], sms: row };
    }
    await sleep(4000);
  }
  return null;
}

export function flattenUi(dump) {
  const nodes = [];
  for (const act of dump?.activities ?? []) {
    for (const n of act.nodes ?? []) nodes.push({ ...n, activity: act.activity, taskId: act.taskId });
  }
  return nodes;
}

export function isAlive(targets, pkg) {
  return (targets.targets ?? []).some((t) => t.package === pkg);
}

export function targetOf(installation) {
  return { space: installation.space, package: installation.package, installGeneration: installation.installGeneration };
}
