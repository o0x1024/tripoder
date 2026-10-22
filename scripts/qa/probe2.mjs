// Validate the exact XHS SMS-login sequence: tick -> resend -> fill code -> submit.
import { adb, sleep, waitForCode } from './mcp.mjs';

const parse = (xml) => {
  const nodes = [];
  for (const m of xml.matchAll(/<node ([^>]*?)\/?>/g)) {
    const a = {};
    for (const x of m[1].matchAll(/([\w:-]+)="([^"]*)"/g)) a[x[1]] = x[2];
    const b = (a.bounds || '').match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
    if (!b) continue;
    nodes.push({
      text: a.text || '', desc: a['content-desc'] || '', cls: a.class || '',
      clickable: a.clickable === 'true', enabled: a.enabled === 'true',
      bounds: [+b[1], +b[2], +b[3], +b[4]],
      centre: [Math.round((+b[1] + +b[3]) / 2), Math.round((+b[2] + +b[4]) / 2)],
    });
  }
  return nodes;
};
const tree = async () => {
  await adb(['shell', 'uiautomator dump /sdcard/p.xml']).catch(() => {});
  return parse(await adb(['shell', 'cat /sdcard/p.xml']).catch(() => ''));
};
const tap = async (n) => { await adb(['shell', `input tap ${n.centre[0]} ${n.centre[1]}`]); await sleep(1500); };
const submitButton = (nodes) => nodes.find((n) => n.clickable && n.cls.includes('Button')
  && /登录/.test((nodes.find((t) => !t.clickable && /^登录$/.test(t.text.trim())
    && t.bounds[0] > n.bounds[0] && t.bounds[2] < n.bounds[2] && t.bounds[1] >= n.bounds[1] && t.bounds[3] <= n.bounds[3]) || {}).text || ''));

let nodes = await tree();
console.log('page texts:', nodes.filter((n) => n.text).map((n) => n.text).slice(0, 10).join(' | '));

const checkbox = nodes.find((n) => n.clickable && /未选中，同意|同意$/.test(n.desc));
if (checkbox && /未选中/.test(checkbox.desc)) {
  await tap(checkbox);
  nodes = await tree();
  console.log('checkbox now:', nodes.find((n) => n.clickable && /同意/.test(n.desc))?.desc);
}

const field = nodes.find((n) => n.cls.includes('EditText') && /验证码/.test(n.text + n.desc));
const resend = nodes.find((n) => /重新发送|重新获取/.test(n.text));
if (resend && !/\(\d+s\)/.test(resend.text)) {
  const since = Date.now();
  await tap(resend);
  console.log('resend tapped, waiting for sms');
  const got = await waitForCode({ sinceMs: since - 3000, timeoutMs: 90000 });
  console.log('verification code received:', Boolean(got));
  if (got) {
    await tap(field);
    await adb(['shell', `input text ${got.code}`]);
    await sleep(1200);
    nodes = await tree();
    const btn = nodes.find((n) => n.clickable && n.cls.includes('Button') && n.enabled
      && Math.abs(n.centre[1] - 982) < 80 && (n.bounds[2] - n.bounds[0]) > 800);
    console.log('button:', btn ? JSON.stringify(btn.bounds) + ' enabled=' + btn.enabled : 'none');
    if (btn) {
      await tap(btn);
      await sleep(6000);
      nodes = await tree();
      console.log('after submit:', nodes.filter((n) => n.text || n.desc).map((n) => n.text || n.desc).slice(0, 12).join(' | '));
    }
  }
}
