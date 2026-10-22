// Pilot: start one app in an explicit mode, capture UI + screenshot + diagnostics.
import { Mcp, targetOf, saveShot, sleep, flattenUi, adb } from './mcp.mjs';
import { resolve } from 'node:path';

const pkg = process.argv[2] || 'com.zhihu.android';
const mode = process.argv[3] || 'thin';
const dir = resolve(process.cwd(), 'artifacts/qa-pilot', `${pkg}-${mode}`);

const mcp = await new Mcp().init();
try {
  const apps = await mcp.call('applications_list');
  const installation = (apps.installations || []).find((i) => i.package === pkg);
  if (!installation) throw new Error('NOT_INSTALLED ' + pkg);
  const target = targetOf(installation);
  const stopOp = `pilot-stop-${pkg.replace(/\W/g, '')}-${Date.now()}`;
  console.log('stop', JSON.stringify(await mcp.call('application_stop', { operationId: stopOp, target })));
  const modeOp = `pilot-mode-${pkg.replace(/\W/g, '')}-${Date.now()}`;
  console.log('mode', JSON.stringify(await mcp.call('application_mode_set', { operationId: modeOp, target, mode })));
  const probes = await mcp.call('probes_get', { target });
  const startOp = `pilot-start-${pkg.replace(/\W/g, '')}-${Date.now()}`;
  const t0 = Date.now();
  const started = await mcp.call('process_start', {
    operationId: startOp, target, component: '', probeRevision: probes.revision ?? '',
  }, { timeoutMs: 90000 });
  console.log('start_ms', Date.now() - t0, JSON.stringify(started).slice(0, 900));
  await sleep(12000);
  const targets = await mcp.call('targets_list');
  const processes = (targets.targets || []).filter((t) => t.package === pkg);
  console.log('processes', processes.map((p) => `${p.processName}:${p.pid}:${p.mode}`).join(' '));
  const main = processes.find((p) => p.processName === pkg);
  if (main) {
    const full = { ...target, processGeneration: main.processGeneration };
    const ui = await mcp.call('ui_dump_hierarchy', { target: full, includeInvisible: false });
    const nodes = flattenUi(ui);
    console.log('activities', (ui.activities || []).map((a) => `${a.activity}#${a.taskId}(${a.nodes.length})`).join('\n'));
    console.log('texts', nodes.filter((n) => n.text).slice(0, 60).map((n) => `[${n.className.split('.').pop()}] ${n.text.slice(0, 40)}`).join(' | '));
    console.log('editable', nodes.filter((n) => /EditText/.test(n.className)).map((n) => `${n.id}:${n.text}`).join(','));
    console.log('crash', JSON.stringify(await mcp.call('crash_diagnostics_get', { target: full })).slice(0, 2500));
    console.log('shot', await saveShot(dir, 'main'));
  }
  console.log('focus', (await adb(['shell', 'dumpsys window | grep -E "mCurrentFocus|mFocusedApp" | head -4'])).trim());
} finally {
  await mcp.close();
}
