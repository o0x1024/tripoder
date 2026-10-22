// Import remaining device packages one at a time with a clean authority process.
// The runtime builds a 16MB descriptor with large intermediate collections; a
// long-lived authority accumulates those peaks and OOMs. A clean restart per
// package keeps the import path within the 256MB heap for typical APKs.
import { Mcp, adb, sleep } from './mcp.mjs';
import { resolve } from 'node:path';
import { readFile, writeFile, mkdir } from 'node:fs/promises';

const OUT = resolve(process.cwd(), 'artifacts/qa');
await mkdir(OUT, { recursive: true });
const LOG = resolve(OUT, 'import-robust.json');
const HOST = process.env.TP_HOST_PACKAGE || 'dev.tripoder.engine.globaltest';
const args = process.argv.slice(2);
const pkgArg = args.find((a) => a.startsWith('--pkgs='));
const limitArg = args.find((a) => a.startsWith('--limit='));
const limit = limitArg ? Number(limitArg.split('=')[1]) : 999;

let log = {};
try { log = JSON.parse(await readFile(LOG, 'utf8')); } catch { log = {}; }

let mcp = null;
async function connect() {
  mcp = await new Mcp({ timeoutMs: 120000 }).init();
  for (let i = 0; i < 8; i++) {
    try { const s = await mcp.call('runtime_status'); if (s.hookReady) return true; } catch {}
    await sleep(2000);
  }
  return false;
}
async function restart() {
  try { await mcp?.close(); } catch {}
  mcp = null;
  await adb(['shell', 'am', 'force-stop', HOST]).catch(() => {});
  await sleep(1500);
  await adb(['shell', 'am', 'start', '-W', '-n', `${HOST}/dev.tripoder.hooklab.WorkbenchActivity`]).catch(() => {});
  await sleep(6000);
  return connect();
}
async function committed() {
  const apps = await mcp.call('applications_list');
  const map = new Map();
  for (const row of apps.installations || []) map.set(row.package, row);
  return map;
}

let installed = new Map();
try { installed = await committed(); } catch { if (!(await restart())) installed = new Map(); }
let candidates;
if (pkgArg) {
  candidates = pkgArg.slice('--pkgs='.length).split(',').map((s) => s.trim()).filter(Boolean);
} else {
  candidates = (await adb(['shell', 'pm list packages -3']))
    .split('\n').map((l) => l.replace('package:', '').trim())
    .filter((p) => p && !p.startsWith('dev.tripoder.')).sort();
}
// Small first: fast coverage and clean failures before the large APKs.
candidates = candidates.filter((p) => !installed.get(p) || installed.get(p).state === 'MISSING')
  .filter((p) => installed.get(p)?.state !== 'CORRUPT')
  .slice(0, limit);

console.log('candidates', candidates.length);
let ok = 0, failed = 0;
for (let i = 0; i < candidates.length; i++) {
  const pkg = candidates[i];
  const clean = await restart();
  if (!clean) { console.log(i, pkg, 'ENGINE_NOT_READY'); log[pkg] = { state: 'ENGINE_NOT_READY', at: Date.now() }; failed++; continue; }
  const operationId = `qa-imp-${pkg.replace(/[^A-Za-z0-9]/g, '-')}-${Date.now()}`;
  let outcome = null;
  try {
    const r = await mcp.call('application_import_device', {
      operationId, space: 'default', package: pkg, expectedGeneration: '',
    }, { timeoutMs: 240000 });
    outcome = { state: r.state, generation: r.generation, operationId };
  } catch (error) {
    const message = String(error.message);
    // A transport timeout is not a verdict: reconcile the exact operation id.
    try {
      const r = await mcp.call('operation_get', { operationId }, { timeoutMs: 60000 });
      const row = r.operation || r;
      if (row && row.state) outcome = { state: row.state === 'RUNNING' ? 'UNKNOWN' : row.state, generation: row.result, operationId, reconciled: true };
    } catch {}
    if (!outcome) outcome = { state: 'FAILED', error: message.slice(0, 600), operationId };
  }
  if (outcome?.state === 'SUCCEEDED' || outcome?.state === 'COMMITTED') { ok++; console.log(i, pkg, 'OK'); }
  else if (/INSTALL_ALREADY_EXISTS/.test(outcome?.error || '')) { ok++; console.log(i, pkg, 'ALREADY'); outcome.state = 'ALREADY'; }
  else { failed++; console.log(i, pkg, outcome?.state || 'FAILED', (outcome?.error || '').slice(0, 160)); }
  log[pkg] = outcome;
  await writeFile(LOG, JSON.stringify(log, null, 2));
}
try { await mcp?.close(); } catch {}
console.log('DONE ok', ok, 'failed', failed, 'remaining', candidates.length - ok - failed);
