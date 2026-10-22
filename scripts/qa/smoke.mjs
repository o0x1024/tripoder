// Per-application, per-mode smoke run: start, watch the guest main process, capture UI/screenshot/logcat.
// Usage: node scripts/qa/smoke.mjs --mode thin --packages a,b --out artifacts/qa/smoke
import { Mcp, targetOf, adb, sleep, flattenUi, saveShot, ensureDir } from './mcp.mjs';
import { resolve } from 'node:path';
import { readFile, writeFile } from 'node:fs/promises';

function argOf(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : fallback;
}
const mode = argOf('mode', 'thin');
const outDir = resolve(process.cwd(), argOf('out', 'artifacts/qa/smoke'));
await ensureDir(outDir);
const packagesArg = argOf('packages', '');
const onlyPackages = packagesArg ? packagesArg.split(',').map((s) => s.trim()).filter(Boolean) : null;

const mcp = await new Mcp().init();

async function stopAll() {
  const apps = await mcp.call('applications_list');
  for (const i of apps.installations || []) {
    try {
      await mcp.call('application_stop', { operationId: `smoke-stopall-${i.package.replace(/\W/g, '')}-${Date.now()}`, target: targetOf(i) });
    } catch {}
  }
}

async function foreground() {
  try {
    const out = await adb(['shell', 'dumpsys window | grep -E "mCurrentFocus=" | head -2']);
    return out.trim().split('\n').map((l) => l.trim()).filter(Boolean);
  } catch { return []; }
}

async function logcatSlice() {
  try { return await adb(['logcat', '-d', '-v', 'brief']); } catch { return ''; }
}

async function runOne(pkg, installation) {
  const record = { package: pkg, mode, startedAt: new Date().toISOString() };
  const target = targetOf(installation);
  const dir = resolve(outDir, mode, pkg);
  await ensureDir(dir);
  try {
    await mcp.call('application_stop', { operationId: `smoke-stop-${pkg.replace(/\W/g, '')}-${Date.now()}`, target });
    await mcp.call('application_mode_set', { operationId: `smoke-mode-${pkg.replace(/\W/g, '')}-${Date.now()}`, target, mode });
    const probes = await mcp.call('probes_get', { target });
    record.probeRevision = probes.revision ?? '';
    await adb(['logcat', '-c']).catch(() => {});
    const t0 = Date.now();
    const started = await mcp.call('process_start', {
      operationId: `smoke-start-${pkg.replace(/\W/g, '')}-${Date.now()}`,
      target, component: '', probeRevision: record.probeRevision,
    }, { timeoutMs: 120000 });
    record.startMs = Date.now() - t0;
    record.pid = started.pid;
    record.processGeneration = started.processGeneration;

    let firstAlive = false;
    let mainSeen = null;
    const deadline = Date.now() + 24000;
    while (Date.now() < deadline) {
      await sleep(2000);
      const targets = await mcp.call('targets_list');
      const self = (targets.targets || []).find((t) => t.package === pkg && t.processName === pkg);
      if (self) { firstAlive = true; mainSeen = self; }
    }
    record.mainAliveAfter24s = firstAlive;
    const aliveTargets = await mcp.call('targets_list');
    record.processes = (aliveTargets.targets || []).filter((t) => t.package === pkg)
      .map((t) => `${t.processName}#${t.pid}#${t.mode}`);

    if (mainSeen) {
      const full = { ...target, processGeneration: mainSeen.processGeneration };
      for (let attempt = 0; attempt < 3; attempt++) {
        try {
          const ui = await mcp.call('ui_dump_hierarchy', { target: full, includeInvisible: false });
          record.activities = (ui.activities || []).map((a) => a.activity);
          const nodes = flattenUi(ui);
          record.texts = [...new Set(nodes.filter((n) => n.text && n.text.trim()).map((n) => n.text.trim().slice(0, 60)))].slice(0, 40);
          record.editables = nodes.filter((n) => /EditText/.test(n.className)).map((n) => ({ id: n.id, text: n.text, bounds: n.bounds }));
          record.nodeCount = nodes.length;
          break;
        } catch (error) {
          record.uiError = String(error.message).slice(0, 300);
          await sleep(2000);
        }
      }
      try { record.diagnostics = await mcp.call('crash_diagnostics_get', { target: full }); } catch (error) { record.diagnosticsError = String(error.message).slice(0, 200); }
    }

    record.foreground = await foreground();
    record.screenshot = await saveShot(dir, 'main').catch(() => null);
    const log = await logcatSlice();
    const interesting = log.split('\n').filter((l) =>
      /TripoderHook|FATAL EXCEPTION|AndroidRuntime|Process is going to kill|Fatal signal|ANR in|ActivityManager: Crash/.test(l));
    record.logcat = interesting.slice(-40);
    record.crashed = /Process is going to kill itself|FATAL EXCEPTION|Fatal signal/.test(interesting.join('\n')) || !firstAlive;
  } catch (error) {
    record.error = String(error.message).slice(0, 900);
    record.crashed = true;
  } finally {
    try { await mcp.call('application_stop', { operationId: `smoke-clean-${pkg.replace(/\W/g, '')}-${Date.now()}`, target }); } catch {}
  }
  await writeFile(resolve(dir, 'record.json'), JSON.stringify(record, null, 2));
  return record;
}

const apps = await mcp.call('applications_list');
const installations = (apps.installations || []).filter((i) => i.state === 'COMMITTED');
const work = onlyPackages ? installations.filter((i) => onlyPackages.includes(i.package)) : installations;
const results = [];
console.log('smoke', mode, 'apps', work.length);
for (const installation of work) {
  let record;
  try {
    record = await runOne(installation.package, installation);
  } catch (error) {
    record = { package: installation.package, mode, error: String(error.message).slice(0, 400), crashed: true };
  }
  results.push(record);
  console.log(JSON.stringify({ package: record.package, crashed: record.crashed, alive: record.mainAliveAfter24s, activities: (record.activities || []).slice(0, 2), error: record.error }));
  await writeFile(resolve(outDir, `smoke-${mode}.json`), JSON.stringify(results, null, 2));
}
console.log('DONE', mode, results.length, 'crashed', results.filter((r) => r.crashed).length);
await mcp.close();
