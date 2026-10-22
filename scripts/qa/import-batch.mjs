// Robust batch import: small chunks, engine restart between chunks, per-app timeout.
import { Mcp, adb, sleep } from './mcp.mjs';
import { resolve } from 'node:path';
import { readFile, writeFile, mkdir } from 'node:fs/promises';

const OUT = resolve(process.cwd(), 'artifacts/qa');
await mkdir(OUT, { recursive: true });
const LOG = resolve(OUT, 'import-log.json');
const CHUNK = Number(process.env.QA_CHUNK || 5);

const skipPrefixes = ['dev.tripoder.'];
let log = {};
try { log = JSON.parse(await readFile(LOG, 'utf8')); } catch { log = {}; }

const candidates = (await adb(['shell', 'pm list packages -3']))
  .split('\n').map((l) => l.replace('package:', '').trim())
  .filter((p) => p && !skipPrefixes.some((s) => p.startsWith(s)))
  .sort();

let mcp = await new Mcp().init();
async function restartEngine() {
  try { await mcp.close(); } catch {}
  await adb(['shell', 'am force-stop dev.tripoder.engine.globaltest']).catch(() => {});
  await sleep(2000);
  await adb(['shell', 'am start -W -n dev.tripoder.engine.globaltest/dev.tripoder.hooklab.WorkbenchActivity']).catch(() => {});
  await sleep(7000);
  mcp = await new Mcp().init();
  for (let i = 0; i < 6; i++) {
    try { const r = await mcp.call('runtime_status'); if (r.hookReady) return; } catch {}
    await sleep(2500);
  }
}
async function committed() {
  const apps = await mcp.call('applications_list');
  return new Set((apps.installations || []).filter((i) => i.state === 'COMMITTED').map((i) => i.package));
}

let done = await committed();
const permanent = new Set(Object.entries(log)
  .filter(([, v]) => /NameNotFoundException|ABI_OR_LIBRARY_UNSUPPORTED/.test(v.error || ''))
  .map(([pkg]) => pkg));
console.log('remaining', candidates.filter((p) => !done.has(p) && !permanent.has(p)).length, 'committed', done.size, 'permanent-failures', permanent.size);

let index = 0;
const attemptsByPkg = new Map();
const nextRemaining = () => candidates.filter((p) => !done.has(p) && !permanent.has(p));
while (true) {
  const remaining = nextRemaining();
  if (remaining.length === 0) break;
  const chunk = remaining.slice(0, CHUNK);
  for (const pkg of chunk) {
    index++;
    const operationId = `qa-imp-${pkg.replace(/[^A-Za-z0-9]/g, '-')}-${Date.now()}`;
    try {
      const r = await mcp.call('application_import_device', { operationId, space: 'default', package: pkg, expectedGeneration: '' }, { timeoutMs: 90000 });
      log[pkg] = { state: r.state, generation: r.generation, operationId };
      if (r.state === 'SUCCEEDED') done.add(pkg);
      console.log(`[${index}]`, pkg, r.state);
    } catch (error) {
      const message = String(error.message);
      log[pkg] = { state: 'FAILED', error: message.slice(0, 500) };
      console.log(`[${index}]`, pkg, 'FAILED', message.slice(0, 120));
      await writeFile(LOG, JSON.stringify(log, null, 2));
      if (/NameNotFoundException|ABI_OR_LIBRARY_UNSUPPORTED/.test(message)) { permanent.add(pkg); continue; }
      if (/INSTALL_ALREADY_EXISTS/.test(message)) {
        log[pkg] = { state: 'ALREADY' };
        done.add(pkg);
        continue;
      }
      const attempts = (attemptsByPkg.get(pkg) || 0) + 1;
      attemptsByPkg.set(pkg, attempts);
      if (attempts >= 2) { permanent.add(pkg); continue; }
      console.log('restarting engine after transport failure');
      await restartEngine();
      try { done = await committed(); } catch { /* keep previous */ }
      break;
    }
    await writeFile(LOG, JSON.stringify(log, null, 2));
  }
  if (nextRemaining().length > 0) {
    console.log('chunk done, restarting engine to keep control channel healthy');
    await restartEngine();
    done = await committed();
  }
}
await writeFile(LOG, JSON.stringify(log, null, 2));
console.log('IMPORT DONE committed', done.size, 'of', candidates.length);
await mcp.close();
