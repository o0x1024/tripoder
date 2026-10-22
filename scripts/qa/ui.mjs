// Interactive UI helper for a running (or newly started) guest.
//   node scripts/qa/ui.mjs start <pkg> [mode]
//   node scripts/qa/ui.mjs dump <pkg> [--all]
//   node scripts/qa/ui.mjs shot <pkg> <name>
//   node scripts/qa/ui.mjs tap <pkg> <index>
//   node scripts/qa/ui.mjs click <pkg> <text>
//   node scripts/qa/ui.mjs text <pkg> <index|hint> <value>
//   node scripts/qa/ui.mjs swipe <pkg> <x0,y0,x1,y1>
//   node scripts/qa/ui.mjs adbtap <pkg> <x> <y>
//   node scripts/qa/ui.mjs stop <pkg>
import { Mcp, adb, sleep, flattenUi, saveShot, targetOf, ensureDir } from './mcp.mjs';
import { resolve } from 'node:path';
import { writeFile, readFile } from 'node:fs/promises';

const [command, pkg, ...rest] = process.argv.slice(2);
const STATE = `/tmp/qa-ui-${pkg}.json`;
const mcp = await new Mcp().init();

async function installation(pkgName) {
  const apps = await mcp.call('applications_list');
  const found = (apps.installations || []).find((i) => i.package === pkgName && i.state === 'COMMITTED');
  if (!found) throw new Error('NOT_INSTALLED ' + pkgName);
  return found;
}
async function liveTarget(pkgName) {
  const t = await mcp.call('targets_list');
  const self = (t.targets || []).find((x) => x.package === pkgName && x.processName === pkgName);
  if (!self) throw new Error('NOT_RUNNING ' + pkgName);
  return { space: self.space, package: self.package, installGeneration: self.installGeneration, processGeneration: self.processGeneration };
}
async function dump() {
  const target = await liveTarget(pkg);
  const ui = await mcp.call('ui_dump_hierarchy', { target, includeInvisible: process.argv.includes('--all') });
  const nodes = flattenUi(ui).filter((n) => (process.argv.includes('--all') ? true : n.visible));
  const rows = nodes.map((n, i) => ({ i, ...n }));
  await writeFile(STATE, JSON.stringify({ target, activities: (ui.activities || []).map((a) => ({ activity: a.activity, taskId: a.taskId, count: a.nodes.length })), rows }, null, 2));
  return { target, activities: (ui.activities || []).map((a) => a.activity), rows };
}
function centre(bounds) { return [Math.round((bounds[0] + bounds[2]) / 2), Math.round((bounds[1] + bounds[3]) / 2)]; }

try {
  if (command === 'start') {
    const mode = rest[0] || 'thin';
    const inst = await installation(pkg);
    const target = targetOf(inst);
    await mcp.call('application_stop', { operationId: `ui-stop-${Date.now()}`, target }).catch(() => {});
    await mcp.call('application_mode_set', { operationId: `ui-mode-${Date.now()}`, target, mode });
    const probes = await mcp.call('probes_get', { target });
    const started = await mcp.call('process_start', { operationId: `ui-start-${Date.now()}`, target, component: '', probeRevision: probes.revision ?? '' }, { timeoutMs: 120000 });
    console.log('started', JSON.stringify({ pid: started.pid, generation: started.processGeneration, mode }));
    await sleep(Number(process.env.QA_SETTLE_MS ?? 8000));
  } else if (command === 'dump') {
    const { activities, rows } = await dump();
    console.log('ACTIVITIES', activities.join(' | '));
    for (const r of rows) {
      const cls = r.className.split('.').pop();
      const flags = `${r.clickable ? 'C' : '-'}${r.enabled ? 'E' : '-'}`;
      const txt = (r.text || '').replace(/\s+/g, ' ').slice(0, 70);
      const desc = (r.contentDescription || '').replace(/\s+/g, ' ').slice(0, 40);
      console.log(`${String(r.i).padStart(3)} ${flags} ${cls.padEnd(14)} ${String(r.id).padEnd(24)} ${txt}${desc ? ' desc=' + desc : ''} @${r.bounds.map((b) => Math.round(b)).join(',')}`);
    }
  } else if (command === 'tap' || command === 'click' || command === 'text') {
    const { target } = await dump();
    const state = JSON.parse(await readFile(STATE, 'utf8'));
    if (command === 'tap') {
      const row = state.rows.find((r) => r.i === Number(pkg === undefined ? rest[0] : rest[0]));
      if (!row) throw new Error('NO_INDEX');
      const [x, y] = centre(row.bounds);
      await mcp.call('ui_dispatch_action', { target, operationId: `ui-tap-${Date.now()}`, action: { kind: 'tap', x, y } });
      console.log('tapped', row.text || row.id, x, y);
    } else if (command === 'click') {
      await mcp.call('ui_dispatch_action', { target, operationId: `ui-click-${Date.now()}`, action: { kind: 'clickText', text: rest[0] } });
      console.log('clicked', rest[0]);
    } else {
      const [, which, value] = rest;
      // `-` means "first editable view"; otherwise match by hint/current text.
      const action = which === '-' || which === undefined
        ? { kind: 'setText', text: value }
        : { kind: 'setText', text: value, match: which };
      await mcp.call('ui_dispatch_action', { target, operationId: `ui-text-${Date.now()}`, action });
      console.log('text set', which, value);
    }
    await sleep(1500);
  } else if (command === 'adbtap') {
    const [x, y] = rest;
    await adb(['shell', `input tap ${Number(x)} ${Number(y)}`]);
    console.log('adb tapped', x, y);
    await sleep(1500);
  } else if (command === 'swipe') {
    const [coords] = rest;
    const [x0, y0, x1, y1] = coords.split(',').map(Number);
    await adb(['shell', `input swipe ${x0} ${y0} ${x1} ${y1} 250`]);
    console.log('swiped', coords);
    await sleep(1200);
  } else if (command === 'shot') {
    const dir = resolve(process.cwd(), 'artifacts/qa/shots');
    await ensureDir(dir);
    console.log(await saveShot(dir, `${pkg}-${rest[0] || Date.now()}`));
  } else if (command === 'stop') {
    const inst = await installation(pkg);
    const r = await mcp.call('application_stop', { operationId: `ui-stop-${Date.now()}`, target: targetOf(inst) });
    console.log('stopped', r.stoppedProcesses);
  } else {
    console.log('unknown command');
  }
} finally {
  await mcp.close();
}
