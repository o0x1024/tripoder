// Merge import log + deep results into a per-app matrix (markdown + csv).
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const exec = promisify(execFile);
const root = process.cwd();
const read = async (p, fallback = {}) => { try { return JSON.parse(await readFile(resolve(root, p), 'utf8')); } catch { return fallback; } };

const importLog = await read('artifacts/qa/import-log.json');
const thin = await read('artifacts/qa/deep-thin/deep-thin.json');
const thick = await read('artifacts/qa/deep-thick/deep-thick.json');

const { stdout: pmOut } = await exec('adb', ['-s', process.env.ANDROID_SERIAL || 'd861a4fa', 'shell', 'pm list packages -3']);
const devicePkgs = pmOut.split('\n').map((l) => l.replace('package:', '').trim()).filter(Boolean)
  .filter((p) => !p.startsWith('dev.tripoder.'));

// Labels captured once through device_applications_list.
const deviceApps = await read('artifacts/qa/dev-apps.json', {});
const apps = {};
for (const app of deviceApps.applications || []) apps[app.package] = app.name;
const rows = [];
for (const pkg of devicePkgs.sort()) {
  const imported = importLog[pkg]?.state === 'SUCCEEDED' || importLog[pkg]?.state === 'ALREADY';
  const t = thin[pkg];
  const k = thick[pkg];
  rows.push({
    package: pkg,
    name: apps[pkg] || '',
    import: imported ? 'ok' : (importLog[pkg]?.state || 'MISSING'),
    thin: t ? `${t.state}${t.login?.state ? '/' + t.login.state : ''}` : '-',
    thick: k ? `${k.state}${k.login?.state ? '/' + k.login.state : ''}` : '-',
    thinCrash: t?.state?.includes('CRASH') ? (t.logcat || []).find((l) => l.includes('FATAL') || l.includes('TripoderHook')) || 'crash' : '',
    thickCrash: k?.state?.includes('CRASH') ? (k.logcat || []).find((l) => l.includes('FATAL') || l.includes('TripoderHook')) || 'crash' : '',
  });
}
const header = '| # | 应用 | 包名 | 导入 | THIN | THICK | 备注 |\n|---|------|------|------|------|-------|------|\n';
const body = rows.map((r, i) => `| ${i + 1} | ${r.name || '-'} | \`${r.package}\` | ${r.import} | ${r.thin} | ${r.thick} | ${(r.thinCrash || r.thickCrash || '').replace(/\|/g, '/').slice(0, 90)} |`).join('\n');
await writeFile(resolve(root, 'artifacts/qa/matrix.md'), header + body + '\n');
const csv = ['package,import,thin,thick'];
for (const r of rows) csv.push([r.package, r.import, r.thin, r.thick].join(','));
await writeFile(resolve(root, 'artifacts/qa/matrix.csv'), csv.join('\n') + '\n');
const summary = {
  total: rows.length,
  imported: rows.filter((r) => r.import === 'ok').length,
  thinPass: rows.filter((r) => r.thin.startsWith('PASS')).length,
  thickPass: rows.filter((r) => r.thick.startsWith('PASS')).length,
  thinCrash: rows.filter((r) => r.thin.includes('CRASH')).length,
  thickCrash: rows.filter((r) => r.thick.includes('CRASH')).length,
};
console.log(JSON.stringify(summary, null, 2));
