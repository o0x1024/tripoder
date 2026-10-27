// Deep functional pass per app: start -> login (phone/SMS) -> browse main sections -> record evidence.
// Usage: node scripts/qa/deep.mjs [--packages a,b] [--out artifacts/qa/deep] [--resume]
import { Mcp, adb, sleep, ensureDir, flattenUi } from './mcp.mjs';
import { Flow, PHONES } from './flow.mjs';
import { resolve } from 'node:path';
import { readFile, writeFile } from 'node:fs/promises';

function argOf(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : fallback;
}

if(process.argv.includes('--mode'))throw Error('Runtime mode selection has been removed');
const outRoot = resolve(process.cwd(), argOf('out', 'artifacts/qa/deep'));
await ensureDir(outRoot);
const only = argOf('packages', '') ? argOf('packages', '').split(',').map((s) => s.trim()).filter(Boolean) : null;
const resume = process.argv.includes('--resume');
const budgetMs = Number(argOf('budget', '300000'));

const profiles = JSON.parse(await readFile(resolve(process.cwd(), 'scripts/qa/profiles.json'), 'utf8').catch(() => '{}'));
const checkpointPath = resolve(outRoot, `deep-runtime.json`);
let done = {};
if (resume) { try { done = JSON.parse(await readFile(checkpointPath, 'utf8')); } catch { done = {}; } }

const mcp = await new Mcp().init();
const flow = new Flow(mcp, { artifacts: outRoot });
const results = { ...done };

async function logcatTail() {
  const log = await adb(['logcat', '-d', '-v', 'brief']).catch(() => '');
  return log.split('\n').filter((l) => /TripoderHook|FATAL EXCEPTION|Process is going to kill|Fatal signal/.test(l)).slice(-25);
}

const apps = await mcp.call('applications_list');
let work = (apps.installations || []).filter((i) => i.state === 'COMMITTED');
if (only) work = work.filter((i) => only.includes(i.package));
work = work.filter((i) => !['dev.tripoder.controltest', 'dev.tripoder.jobsprobe'].includes(i.package));
console.log('deep',  'apps', work.length, 'resume-done', Object.keys(done).length);

for (const installation of work) {
  const pkg = installation.package;
  if (resume && results[pkg] && results[pkg].state && results[pkg].state !== 'ERROR') continue;
  const startedAt = Date.now();
  const profile = profiles[pkg] || {};
  const record = { package: pkg,  startedAt: new Date().toISOString(), trail: [] };
  try {
    await adb(['logcat', '-c']).catch(() => {});
    await flow.start(pkg, { settleMs: profile.settleMs ?? 9000 });
    const alive = await flow.live(pkg);
    record.mainAliveAfterStart = !!alive;
    if (!alive) {
      record.state = 'CRASH_ON_START';
      record.logcat = await logcatTail();
      record.screenshot = await flow.shot(`${pkg}-runtime-crash`).catch(() => null);
      results[pkg] = record;
      await writeFile(checkpointPath, JSON.stringify(results, null, 2));
      console.log(pkg, record.state);
      continue;
    }
    // pre-entry taps from a profile (e.g. open login page or skip onboarding)
    for (const entry of profile.entries || []) {
      if (entry.text) await flow.tapText(entry.text).catch(() => {});
      if (entry.xy) await flow.tapAt(entry.xy[0], entry.xy[1]).catch(() => {});
      await sleep(2500);
    }
    const before = await flow.tree();
    record.initialTexts = flow.texts(before.nodes).slice(0, 15);
    record.screenshotBeforeLogin = await flow.shot(`${pkg}-runtime-before`).catch(() => null);
    const needsLogin = flow.loginMarkers(before.nodes);
    record.loginScreen = needsLogin;
    record.credentialOnly = needsLogin ? flow.credentialOnly(before.nodes) : false;
    if (profile.skipLogin) {
      record.login = { state: 'SKIPPED_BY_PROFILE' };
    } else if (needsLogin && record.credentialOnly) {
      record.login = { state: 'CREDENTIAL_ONLY_NO_PHONE_LOGIN', detail: record.initialTexts };
    } else if (needsLogin) {
      record.login = await flow.login(pkg, { phone: profile.phone || PHONES[1], maxRounds: profile.maxRounds || 10 });
    } else {
      record.login = { state: 'NO_LOGIN_SCREEN' };
    }
    const loginOk = ['LOGGED_IN', 'NO_LOGIN_SCREEN', 'SKIPPED_BY_PROFILE'].includes(record.login.state);
    record.browse = await flow.browse(pkg, { stops: profile.stops ?? 3 });
    if (!record.browse.crashed) {
      record.state = loginOk ? 'PASS' : record.login.state === 'LOGIN_BLOCKED' ? 'LOGIN_BLOCKED' : record.login.state;
    } else {
      record.state = loginOk ? 'CRASH_DURING_BROWSE' : 'CRASH_DURING_BROWSE_BEFORE_LOGIN';
    }
    record.aliveAtEnd = !!(await flow.live(pkg));
    record.logcat = await logcatTail();
    if (!record.aliveAtEnd) record.state = record.state === 'PASS' ? 'CRASH_AFTER_BROWSE' : record.state;
  } catch (error) {
    record.state = 'ERROR';
    record.error = String(error.message).slice(0, 700);
    record.logcat = await logcatTail();
  } finally {
    record.elapsedMs = Date.now() - startedAt;
    record.log = flow.log.slice(-60);
    flow.log = [];
    await flow.stop(pkg).catch(() => {});
    results[pkg] = record;
    await writeFile(checkpointPath, JSON.stringify(results, null, 2));
    console.log(JSON.stringify({ pkg, state: record.state, login: record.login?.state, elapsed: record.elapsedMs }));
  }
  if (Date.now() - startedAt > 0 && budgetMs && Date.now() - startedAt > budgetMs) {
    console.log('per-app budget exceeded', pkg);
  }
}
console.log('DONE deep',  'passed', Object.values(results).filter((r) => r.state === 'PASS').length, 'total', Object.keys(results).length);
await mcp.close();
