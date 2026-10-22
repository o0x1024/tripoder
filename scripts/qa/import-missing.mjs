// Import every third-party device app that is not yet COMMITTED, skipping permanent failures.
import { Mcp, adb, sleep } from './mcp.mjs';
import { resolve } from 'node:path';
import { readFile, writeFile, mkdir } from 'node:fs/promises';

const OUT = resolve(process.cwd(), 'artifacts/qa');
await mkdir(OUT, { recursive: true });
const LOG_PATH = resolve(OUT, 'import-log.json');
const log = JSON.parse(await readFile(LOG_PATH, 'utf8').catch(() => '{}'));
const PERMANENT = /ABI_OR_LIBRARY_UNSUPPORTED|NameNotFoundException|INSTALL_FAILED_NO_MATCHING_ABIS|INSTALL_FAILED_INVALID_APK|INSTALL_PARSE_FAILED/;

const mcp = await new Mcp().init();
const apps = await mcp.call('applications_list');
const committed = new Set((apps.installations || []).filter((i) => i.state === 'COMMITTED').map((i) => i.package));
const device = (await adb(['shell', 'pm list packages -3'])).split('\n')
  .map((l) => l.replace('package:', '').trim())
  .filter((p) => p && !p.startsWith('dev.tripoder.'));
const todo = device.filter((p) => !committed.has(p) && !(log[p]?.state === 'FAILED' && PERMANENT.test(log[p]?.error || '')));
console.log('device', device.length, 'committed', committed.size, 'todo', todo.length);

for (const pkg of todo) {
  const operationId = `qa-imp2-${pkg}-${Date.now()}`.replace(/[^A-Za-z0-9_-]/g, '_');
  let rec;
  try {
    const r = await mcp.call('application_import_device', { operationId, space: 'default', package: pkg, expectedGeneration: '' }, { timeoutMs: 260000 });
    rec = { state: r.state, generation: r.generation, operationId, at: new Date().toISOString() };
    console.log(pkg, rec.state);
  } catch (error) {
    const message = String(error.message || error);
    rec = { state: 'FAILED', error: message.slice(0, 700), at: new Date().toISOString() };
    console.log(pkg, 'FAILED', message.slice(0, 140));
    if (/ENGINE_NOT_CONNECTED|TRANSPORT_EOF|DeadObject/.test(message)) {
      log[pkg] = rec;
      await writeFile(LOG_PATH, JSON.stringify(log, null, 2));
      console.log('engine transport failed; stopping for restart');
      break;
    }
  }
  log[pkg] = rec;
  await writeFile(LOG_PATH, JSON.stringify(log, null, 2));
}
const final = await mcp.call('applications_list');
const now = new Set((final.installations || []).filter((i) => i.state === 'COMMITTED').map((i) => i.package));
console.log('committed now', now.size, 'remaining', device.filter((p) => !now.has(p)).length);
await mcp.close();
