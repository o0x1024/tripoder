// Probe: start one guest and report what the system says is in front, plus the guest dump.
import { Mcp, adb, sleep, targetOf } from './mcp.mjs';

const pkg = process.argv[2] || 'com.tencent.mm';

const mcp = await new Mcp({ timeoutMs: 150000 }).init();
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

async function front() {
  const w = await adb(['shell', 'dumpsys window windows']).catch(() => '');
  const focus = (w.match(/mCurrentFocus=Window\{\S+ \S+ ([^}\s]+)/) || [])[1] || '?';
  const app = (w.match(/mFocusedApp=ActivityRecord\{\S+ \S+ ([^}\s]+)/) || [])[1] || '?';
  const acts = await adb(['shell', 'dumpsys activity activities']).catch(() => '');
  const top = (acts.match(/topResumedActivity=ActivityRecord\{\S+ \S+ (\S+)/) || [])[1] || '?';
  return { focus, app, top };
}

const st = await mcp.call('runtime_status').catch((e) => ({ error: String(e.message) }));
log('runtime', JSON.stringify(st).slice(0, 300));
const apps = await mcp.call('applications_list');
const inst = (apps.installations || []).find((i) => i.package === pkg && i.state === 'COMMITTED');
if (!inst) { log('no installation'); process.exit(1); }
const target = targetOf(inst);
log('stop', JSON.stringify(await mcp.call('application_stop', { operationId: `p-stop-${Date.now()}`, target }).catch((e) => String(e.message))).slice(0, 200));
await sleep(1200);
const probes = await mcp.call('probes_get', { target }).catch(() => ({}));
const started = await mcp.call('process_start', { operationId: `p-start-${Date.now()}`, target, component: '', probeRevision: probes.revision ?? '' }, { timeoutMs: 180000 })
  .catch((e) => ({ error: String(e.message).slice(0, 300) }));
log('start', JSON.stringify(started).slice(0, 300));
for (let i = 0; i < 8; i++) {
  await sleep(2500);
  const f = await front();
  let dump = null;
  try {
    dump = await mcp.call('ui_dump_hierarchy', { target, includeInvisible: true }, { timeoutMs: 8000 });
  } catch (error) { dump = { error: String(error.message).slice(0, 100) }; }
  const acts = (dump?.activities || []).map((a) => `${a.activity}:${(a.nodes || []).length}`);
  log(`t+${(i + 1) * 2.5}s`, JSON.stringify(f), 'dump=', JSON.stringify(acts).slice(0, 220), dump?.error || '');
}
await adb(['shell', 'screencap -p /sdcard/probe.png']).catch(() => {});
await adb(['pull', '/sdcard/probe.png', `artifacts/qa/probe-${pkg}-runtime.png`]).catch(() => {});
await mcp.close();
