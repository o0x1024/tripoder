// Stepwise manual probe: drive the login UI with explicit taps and dump the tree after each step.
import { Mcp, adb, sleep } from './mcp.mjs';

const out = [];
const dump = async (label) => {
  await adb(['shell', 'uiautomator dump /sdcard/p.xml']).catch(() => {});
  const xml = await adb(['shell', 'cat /sdcard/p.xml']).catch(() => '');
  const rows = [];
  for (const m of xml.matchAll(/<node ([^>]*?)\/?>/g)) {
    const a = {};
    for (const x of m[1].matchAll(/([\w:-]+)="([^"]*)"/g)) a[x[1]] = x[2];
    const t = (a.text || '').trim(); const d = (a['content-desc'] || '').trim();
    if (t || d) rows.push(`${(a.class || '').split('.').pop()} clk=${a.clickable} [${a.bounds}] t=${t.slice(0, 40)} d=${d.slice(0, 30)}`);
  }
  console.log(`\n### ${label}`);
  console.log(rows.slice(0, 24).join('\n'));
  out.push({ label, rows });
  return xml;
};

const tap = async (x, y, note = '') => {
  console.log(`\n>>> tap ${x},${y} ${note}`);
  await adb(['shell', `input tap ${x} ${y}`]);
  await sleep(2500);
};

await dump('start');
await tap(604, 824, 'submit 验证并登录');
await sleep(5000);
await dump('after submit +5s');
await sleep(8000);
await dump('after submit +13s');
