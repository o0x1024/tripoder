// Import every third-party device application into the default Tripoder space.
import { Mcp, adb, sleep } from './mcp.mjs';
import { resolve } from 'node:path';
import { readFile, writeFile, mkdir } from 'node:fs/promises';

const OUT = resolve(process.cwd(), 'artifacts/qa');
await mkdir(OUT, { recursive: true });
const LOG = resolve(OUT, 'import-log.json');

const skipPrefixes = ['dev.tripoder.'];
const mcp = await new Mcp().init();
let log = {};
try { log = JSON.parse(await readFile(LOG, 'utf8')); } catch { log = {}; }

const pkgs = (await adb(['shell', 'pm list packages -3']))
  .split('\n').map((l) => l.replace('package:', '').trim())
  .filter((p) => p && !skipPrefixes.some((s) => p.startsWith(s)))
  .sort();
console.log('candidates', pkgs.length);

const apps = await mcp.call('applications_list');
const committed = new Map((apps.installations || []).filter((i) => i.state === 'COMMITTED').map((i) => [i.package, i]));

let ok = 0, failed = 0, skipped = 0, consecutiveTransportFailures = 0;
for (const pkg of pkgs) {
  if (committed.has(pkg)) { log[pkg] = { state: 'ALREADY', generation: committed.get(pkg).generation }; skipped++; continue; }
  let attempt = 0, result = null;
  while (attempt < 3 && !result) {
    attempt++;
    const operationId = `qa-import-${pkg.replace(/[^A-Za-z0-9]/g, '-')}-${Date.now()}`;
    try {
      const r = await mcp.call('application_import_device', { operationId, space: 'default', package: pkg, expectedGeneration: '' }, { timeoutMs: 240000 });
      result = { state: r.state, generation: r.generation, operationId };
      consecutiveTransportFailures = 0;
    } catch (error) {
      const message = String(error.message);
      result = { state: 'FAILED', error: message.slice(0, 600) };
      const transport = /ENGINE_NOT_CONNECTED|DeadObject|TRANSPORT_TIMEOUT|CAPACITY|UNKNOWN|BUSY/.test(message);
      if (!transport) break;
      consecutiveTransportFailures++;
      if (consecutiveTransportFailures >= 4) {
        console.log('ENGINE_UNRESPONSIVE — stopping so the device can be restarted before retrying');
        await writeFile(LOG, JSON.stringify(log, null, 2));
        await mcp.close();
        process.exit(3);
      }
      await sleep(20000);
      result = null;
    }
  }
  log[pkg] = result ?? { state: 'FAILED', error: 'retries exhausted' };
  if (result?.state === 'SUCCEEDED') ok++; else failed++;
  await writeFile(LOG, JSON.stringify(log, null, 2));
  console.log(pkg, result?.state, result?.error ? result.error.slice(0, 120) : '');
}
await writeFile(LOG, JSON.stringify(log, null, 2));
console.log(JSON.stringify({ ok, failed, skipped, total: pkgs.length }));
await mcp.close();
