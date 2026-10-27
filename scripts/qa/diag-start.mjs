// Diagnostic: start one guest app step by step and print every engine response.
import { Mcp, adb, sleep, targetOf } from './mcp.mjs';

const pkg = process.argv[2] || 'com.xingin.xhs';

const mcp = await new Mcp({ timeoutMs: 150000 }).init();
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

const st = await mcp.call('runtime_status').catch((e) => ({ error: String(e.message) }));
log('runtime_status', JSON.stringify(st).slice(0, 400));
const apps = await mcp.call('applications_list');
const inst = (apps.installations || []).find((i) => i.package === pkg && i.state === 'COMMITTED');
log('installation', inst ? JSON.stringify(inst).slice(0, 400) : 'MISSING');
if (!inst) process.exit(1);
const target = targetOf(inst);

log('stop', JSON.stringify(await mcp.call('application_stop', { operationId: `d-stop-${Date.now()}`, target }).catch((e) => String(e.message))).slice(0, 300));
await sleep(1500);
const probes = await mcp.call('probes_get', { target }).catch((e) => ({ error: String(e.message) }));
log('probes_get', JSON.stringify(probes).slice(0, 300));
const started = await mcp.call('process_start', {
  operationId: `d-start-${Date.now()}`, target, component: '', probeRevision: probes.revision ?? '',
}, { timeoutMs: 180000 }).catch((e) => ({ error: String(e.message) }));
log('process_start', JSON.stringify(started).slice(0, 500));

for (let i = 0; i < 6; i++) {
  await sleep(3000);
  const t = await mcp.call('targets_list').catch(() => ({ targets: [] }));
  const hit = (t.targets || []).find((x) => x.package === pkg);
  const top = (await adb(['shell', 'dumpsys activity activities']).catch(() => '')).match(/topResumedActivity=ActivityRecord\{\S+ \S+ (\S+)/);
  log(`t+${(i + 1) * 3}s target=${hit ? hit.processName : 'none'} top=${top ? top[1] : '?'}`);
}
const top = (await adb(['shell', 'dumpsys activity activities']).catch(() => '')).match(/topResumedActivity=ActivityRecord\{\S+ \S+ (\S+)/);
log('final top', top ? top[1] : '?');
await adb(['shell', 'screencap -p /sdcard/qa-diag.png']).catch(() => {});
await adb(['pull', '/sdcard/qa-diag.png', 'artifacts/qa/diag-start.png']).catch(() => {});
await mcp.close();
