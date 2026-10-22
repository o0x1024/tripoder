// THIN/THICK compatibility + functional smoke campaign.
// Launch -> consent/permission sweep -> phone+SMS login -> browse tabs/detail/search -> crash verdict.
// Usage:
//   node scripts/qa/compat.mjs --mode thin [--limit 10] [--from 0] [--pkgs a,b]
//                               [--out artifacts/qa/campaign] [--resume]
import { Mcp, adb, sleep, ensureDir, targetOf, waitForCode } from './mcp.mjs';
import { Flow, PHONES } from './flow.mjs';
import { resolve } from 'node:path';
import { readFile, writeFile, mkdir } from 'node:fs/promises';

const arg = (name, fallback = '') => {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : fallback;
};
const MODE = arg('mode', 'thin');
const OUT = resolve(process.cwd(), arg('out', 'artifacts/qa/campaign'));
const STATE_PATH = resolve(OUT, `state-${MODE}.json`);
const limit = Number(arg('limit', '999'));
const from = Number(arg('from', '0'));
const only = arg('pkgs', '') ? arg('pkgs', '').split(',').map((s) => s.trim()).filter(Boolean) : null;
const resume = process.argv.includes('--resume');
const appBudgetMs = Number(arg('budget', '240000'));
const HOST = process.env.TP_HOST_PACKAGE || 'dev.tripoder.engine.globaltest';
const NO_RESET = process.argv.includes('--no-reset');
const opid = (label) => `qa-compat-${label}-${Date.now()}`.replace(/[^A-Za-z0-9_-]/g, '_').slice(0, 80);
await ensureDir(OUT);
await adb(['shell', 'settings put system accelerometer_rotation 0']).catch(() => {});
await adb(['shell', 'settings put system user_rotation 0']).catch(() => {});

const mcp = await new Mcp({ timeoutMs: 150000 }).init();

// Reclaim slot processes left by an interrupted earlier pass (8 slots only).
const preTargets = await mcp.call('targets_list').catch(() => null);
for (const t of preTargets?.targets ?? []) {
  await mcp.call('application_stop', {
    operationId: opid(`clean-${t.package}`),
    target: { space: t.space, package: t.package, installGeneration: t.installGeneration },
  }).catch(() => {});
}
if ((preTargets?.targets ?? []).length) console.log(`[compat] reclaimed ${preTargets.targets.length} leftover guest process(es)`);
const flow = new Flow(mcp, { artifacts: OUT, verbose: true });
const state = resume ? JSON.parse(await readFile(STATE_PATH, 'utf8').catch(() => '{}')) : {};
const save = () => writeFile(STATE_PATH, JSON.stringify(state, null, 2));

const POSITIVE = /^(同意并继续|同意并授权|同意|我已阅读并同意|已阅读并同意|接受|允许|始终允许|仅在使用中允许|仅在使用该应用时允许|使用应用时允许|允许一次|我知道了|知道了|好的|继续|开始使用|进入应用|立即体验|跳过|跳过广告|关闭|以后再说|暂不|暂不需要|下次再说|下次吧|取消)$/;
const NEVER = /不同意|拒绝|退出|注销|删除|卸载|支付|付款|购买|下单|充值|转账|发红包/;
const MAIN_TABS = /^(首页|推荐|发现|关注|消息|我的|购物车|分类|视频|直播|同城|附近|书架|频道|工作台|通讯录|钱包|娱乐|热点|精选|首页推荐|个人中心|商城|服务|政务|学习|社区|论坛|音乐|电台|排行)$/;
const LOGIN_WORDS = /(手机号|手机号码|验证码|一键登录|本机号码|密码登录|账号登录|登录\/注册|登录注册|立即登录|请输入手机|其他登录方式)/;
const CRASH_RE = /FATAL EXCEPTION|Fatal signal|Process: |ANR in |Process .* has died|Force finishing/;

async function targets() {
  for (let i = 0; i < 3; i++) {
    try { return await mcp.call('targets_list'); }
    catch (error) { if (i === 2) { console.log('[compat] targets_list unavailable:', String(error.message).slice(0, 120)); return null; } await sleep(4000); }
  }
  return null;
}

async function alive(pkg, processGeneration = '') {
  const t = await targets();
  const hit = (t?.targets || []).find((x) => x.package === pkg);
  if (hit) {
    if (processGeneration && hit.processGeneration && hit.processGeneration !== processGeneration) return null;
    return hit;
  }
  // The target list can lag a live guest; confirm with the kernel before calling it a crash.
  const pid = await adb(['shell', `pidof ${pkg}`]).then((out) => String(out).trim()).catch(() => '');
  if (/^\d+/.test(pid)) return { package: pkg, processName: pkg, processGeneration, pid: Number(pid.split(/\s+/)[0]) };
  if (!t) return 'UNKNOWN';
  return null;
}

async function crashLog(pkg) {
  const crash = await adb(['logcat', '-d', '-b', 'crash', '-t', '800']).catch(() => '');
  const main = await adb(['logcat', '-d', '-t', '1200']).catch(() => '');
  const all = `${crash}\n${main}`.split('\n');
  const blocks = [];
  let cur = null;
  for (const line of all) {
    if (/FATAL EXCEPTION|Fatal signal|ANR in |Process .* has died/.test(line)) {
      if (cur) blocks.push(cur);
      cur = [line];
    } else if (cur) {
      if (/^--------- beginning/.test(line)) { blocks.push(cur); cur = null; }
      else cur.push(line);
    }
  }
  if (cur) blocks.push(cur);
  const hit = blocks.filter((b) => b.some((l) => l.includes(pkg))).slice(-2);
  if (hit.length) return hit.flat().filter(Boolean).slice(-110);
  return all.filter((l) => (CRASH_RE.test(l) || /FATAL|ANR/.test(l)) && l.includes(pkg)).slice(-24);
}

async function tree(nodes) {
  const t = nodes ? { nodes } : await flow.tree();
  return t.nodes || [];
}
async function stableTree(rounds = 3) {
  let nodes = [];
  for (let i = 0; i < rounds; i++) {
    nodes = await tree();
    if (nodes.length) return nodes;
    await sleep(1500);
  }
  return nodes;
}
function hostScreen(nodes) {
  // The guest is composed inside the host window, so a dump can carry both. Only call it
  // the workbench when nothing on screen belongs to the guest package.
  if ((nodes ?? []).some((n) => flow.pkg && n.rid && n.rid.startsWith(flow.pkg))) return false;
  const all = flow.texts(nodes ?? []).join(' | ');
  const workbench = /Tripoder/.test(all) && /(应用标签|设置标签|连接工具|个应用|运行引擎没有连接)/.test(all);
  return workbench;
}

async function resetHost({ force = false } = {}) {
  if (NO_RESET) return true;
  if (!force) {
    // The engine survives normal app switches; only restart it when the hook died.
    const r = await mcp.call('runtime_status').catch(() => null);
    if (r && r.hookReady) return true;
  }
  await adb(['shell', 'am', 'force-stop', HOST]).catch(() => {});
  await sleep(1500);
  await adb(['shell', 'am', 'start', '-W', '-n', `${HOST}/dev.tripoder.hooklab.WorkbenchActivity`]).catch(() => {});
  for (let i = 0; i < 8; i++) {
    await sleep(2000);
    try { const r = await mcp.call('runtime_status'); if (r.hookReady) { await sleep(2500); return true; } } catch {}
  }
  return false;
}

/** True while the workbench is still loading its app catalog. */
async function hostLoading() {
  const nodes = await stableTree();
  const all = texts(nodes).join(' | ');
  return /正在读取/.test(all);
}

function texts(nodes) { return flow.texts(nodes); }
function signature(nodes) { return texts(nodes).join('|').slice(0, 500); }

async function sweep(max = 7) {
  const acted = [];
  let last = '';
  for (let i = 0; i < max; i++) {
    const nodes = await tree();
    if (!nodes.length) { await sleep(1200); continue; }
    if (await flow.handleAgreement(nodes)) { await sleep(900); continue; }
    const sig = signature(nodes);
    const cands = nodes.filter((n) => {
      const label = (n.text || n.desc || '').trim();
      return label && POSITIVE.test(label) && !NEVER.test(label) && n.enabled !== false;
    });
    if (!cands.length) break;
    // permission/consent positives first, then neutral dismissals
    cands.sort((a, b) => {
      const score = (n) => /允许|同意|我知道了|知道了|开始使用|进入应用/.test(n.text || n.desc) ? 0 : 1;
      return score(a) - score(b);
    });
    const node = cands[0];
    const label = (node.text || node.desc || '').trim();
    flow.note('sweep tap', label);
    await flow.tapNode(node);
    acted.push(label);
    await sleep(1400);
    if (sig === last) {
      // tapping did not visibly change anything; one more try then stop
      if (i > 2) break;
    }
    last = sig;
  }
  return acted;
}

function loginSignals(nodes) {
  const t = texts(nodes);
  const all = t.join(' | ');
  const tabs = t.filter((x) => MAIN_TABS.test(x.trim())).length;
  const words = (all.match(LOGIN_WORDS) || []).length;
  const edits = nodes.filter((n) => /EditText|TextField|SecureEditText/i.test(n.cls || ''));
  const loginButton = nodes.some((n) => /^(登录|登录\/注册|立即登录|登录注册|一键登录)$/.test((n.text || '').trim()));
  // Logged-out apps often keep the main UI visible and show a login CTA / masked SIM number.
  const loginCta = /一键登录|本机号码|登录后查看|登录后|使用\s*\+?86[\d*\s]{6,}登录|立即登录|去登录|点击登录|登录\/注册/.test(all);
  const loginForm = /手机号登录|请输入手机号码|请输入手机号|验证并登录|验证码登录|密码登录|本机号码一键登录|其他登录方式/.test(all);
  const welcome = tabs === 0 && edits.length === 0
    && nodes.some((n) => /^登录$/.test((n.text || '').trim()) && n.clickable)
    && nodes.some((n) => /^注册$/.test((n.text || '').trim()) && n.clickable);
  const gate = loginForm
    || welcome
    || (words >= 2 && tabs < 2 && (edits.length > 0 || loginButton || /一键登录|本机号码/.test(all)))
    || (loginCta && (edits.length > 0 || /一键登录|本机号码|登录后查看|使用\s*\+?86/.test(all)));
  return { login: gate, words, tabs, edits: edits.length, loginButton, loginCta, all };
}

function credentialOnly(nodes) {
  const all = texts(nodes).join(' | ');
  return !/手机号|手机号码|验证码|一键登录|获取验证码|发送验证码/.test(all)
    && /密码|账号|帐户|邮箱|GitHub|Google|Apple|微信登录|QQ登录|企业微信|SSO/.test(all);
}

function mainScore(nodes) {
  const t = texts(nodes);
  const tabs = new Set(t.filter((x) => MAIN_TABS.test(x.trim())));
  const feed = nodes.filter((n) => (n.text || '').trim().length >= 6 && n.centre[1] > 300 && n.centre[1] < 2200).length;
  return tabs.size * 2 + Math.min(feed, 10);
}

async function login(pkg, phone) {
  const notes = [];
  let nodes = await tree();
  // Switch away from a carrier one-click page to an editable phone form when possible.
  for (let i = 0; i < 2; i++) {
    const change = nodes.find((n) => /^(更换|切换|换个手机号|其他手机号|使用其他手机号|其他登录方式|账号密码登录|密码登录)$/.test((n.text || n.desc || '').trim()));
    if (change && !nodes.some((n) => /EditText/.test(n.cls || ''))) {
      notes.push('tap:' + (change.text || change.desc));
      await flow.tapNode(change); await sleep(1500); nodes = await tree();
    } else break;
  }
  let res = await flow.login(pkg, { phone, maxRounds: 12, smsTimeoutMs: 45000 });
  notes.push(...(res.trail || []).map((x) => Array.isArray(x) ? x[0] : x));
  if (res.state === 'LOGGED_IN') {
    await sleep(2500);
    let after = await stableTree();
    if (hostScreen(after)) return { ok: false, state: 'LOGIN_ENGINE_LOST', notes, detail: 'host screen' };
    // A fast transition can briefly hide the form; confirm we actually left the login page.
    if (after.length && loginSignals(after).login) {
      notes.push('login-verify-retry');
      res = await flow.login(pkg, { phone, maxRounds: 10, smsTimeoutMs: 45000 });
      await sleep(2500);
      after = await stableTree();
    }
    if (!after.length) return { ok: false, state: 'LOGIN_UNVERIFIED', notes, detail: 'empty tree after login' };
    if (flow.mainScreen(after)) {
      return { ok: true, state: 'LOGGED_IN', notes, main: mainScore(after), detail: res.detail };
    }
    notes.push('still-login-ui');
    return { ok: false, state: 'LOGIN_UNVERIFIED', notes, detail: loginSignals(after).all.slice(0, 300) };
    return { ok: false, state: 'LOGIN_UNVERIFIED', notes, detail: 'still on login UI' };
  }
  // SMS rejected/rate-limited: retry once with the alternate number.
  if (res.state === 'LOGIN_REJECTED') {
    const alt = phone === PHONES[0] ? PHONES[1] : PHONES[0];
    await flow.back(); await sleep(1200);
    const res2 = await flow.login(pkg, { phone: alt, maxRounds: 8, smsTimeoutMs: 45000 });
    return { ok: res2.state === 'LOGGED_IN', state: res2.state, notes: [...notes, 'alternate-configured-account'], detail: res2.detail, codeUsed: res2.codeUsed };
  }
  return { ok: false, state: res.state, notes, detail: res.detail };
}

const DANGER = Flow.DANGER;
async function deepBrowse(pkg, processGeneration, { maxTabs = 6, maxCards = 2 } = {}) {
  const visited = [];
  const notes = [];
  let crashed = false;
  const record = async (name) => {
    const nodes = await stableTree();
    if (hostScreen(nodes)) { crashed = true; notes.push('host-screen:' + name); }
    const shot = await flow.shot(`${pkg}-${MODE}-${name}`).catch(() => null);
    visited.push({ name, texts: texts(nodes).slice(0, 14), screenshot: shot });
    return nodes;
  };
  let nodes = await record('home');
  const homeSig = signature(nodes);

  // Bottom navigation: unique tab labels, tap each and scroll its first screen.
  const tabs = [];
  const seen = new Set();
  for (const n of nodes) {
    const label = (n.text || n.desc || '').trim();
    if (!label || label.length > 6 || seen.has(label)) continue;
    if (!(MAIN_TABS.test(label) || (n.clickable && n.centre[1] > 2150))) continue;
    if (DANGER.test(label)) continue;
    seen.add(label); tabs.push(n);
  }
  for (const [i, tab] of tabs.slice(0, maxTabs).entries()) {
    if (!(await alive(pkg, processGeneration))) { crashed = true; notes.push('gone-before-tab:' + (tab.text || tab.desc)); break; }
    await flow.tapNode(tab); await sleep(1800);
    await sweep(2);
    const label = (tab.text || tab.desc || `tab${i}`).trim();
    nodes = await record(`tab-${i}-${label}`);
    await flow.swipeUp(); await sleep(900);
    notes.push('tab:' + label);
  }

  // Content details from the home feed (first screen).
  if (!crashed) {
    for (let i = 0; i < maxCards; i++) {
      if (!(await alive(pkg, processGeneration))) { crashed = true; notes.push('gone-before-card'); break; }
      const ns = await tree();
      const cards = ns.filter((n) => n.clickable && n.centre[1] > 500 && n.centre[1] < 2100
        && (n.text || '').trim().length >= 6 && !DANGER.test(n.text || '') && !MAIN_TABS.test((n.text || '').trim())
        && !/搜索|登录|注册|广告|打开|下载|更新/.test(n.text || ''));
      if (!cards.length) {
        if (i === 0) {
          // Image/feed cards often expose no text; tap the feed centre to open a detail page.
          notes.push('card-blind-centre');
          await flow.tapAt(600, 1500); await sleep(2400);
          await sweep(2);
          await record('detail-blind');
          await flow.back(); await sleep(1200);
          if (!(await alive(pkg, processGeneration))) { crashed = true; notes.push('gone-after-blind-back'); }
        } else notes.push('no-card');
        break;
      }
      const card = cards[Math.min(i * 2, cards.length - 1)];
      notes.push('card:' + (card.text || '').slice(0, 24));
      await flow.tapNode(card); await sleep(2200);
      await sweep(2);
      await record(`detail-${i}`);
      await flow.back(); await sleep(1200);
      if (!(await alive(pkg, processGeneration))) { crashed = true; notes.push('gone-after-back'); break; }
    }
  }

  // Search entry: use a hot suggestion when available, otherwise an ASCII query.
  if (!crashed) {
    const ns = await tree();
    const search = ns.find((n) => /搜索|搜一搜|查找/.test((n.text || n.desc || '')) && n.centre[1] < 900 && !DANGER.test(n.text || ''));
    if (search) {
      notes.push('search-entry');
      await flow.tapNode(search); await sleep(1800);
      await sweep(1);
      const sn = await tree();
      const hot = sn.find((n) => /^(热搜|热门|猜你想搜|大家都在搜|搜索发现)/.test((n.text || '').trim()))
        || sn.find((n) => n.clickable && n.centre[1] > 400 && n.centre[1] < 2000 && (n.text || '').trim().length > 3 && !/搜索|取消|返回/.test(n.text || ''));
      if (hot) { notes.push('search-hot:' + (hot.text || hot.desc || '').slice(0, 20)); await flow.tapNode(hot); }
      else { await flow.type('test'); await flow.key(66); }
      await sleep(2500);
      await record('search-result');
      await flow.back(); await sleep(1000);
      await flow.back(); await sleep(1000);
    } else notes.push('no-search');
  }

  if (!crashed) await record('final');
  return { crashed, visited, notes, homeSig };
}

async function ensureImported(pkg) {
  let apps = null;
  for (let i = 0; i < 3 && !apps; i++) {
    try { apps = await mcp.call('applications_list'); }
    catch (error) { console.log('[compat] applications_list retry:', String(error.message).slice(0, 140)); await sleep(5000); }
  }
  if (!apps) throw new Error('applications_list unavailable');
  const hit = (apps.installations || []).find((i) => i.package === pkg && i.state === 'COMMITTED');
  if (hit) return hit;
  const operationId = opid(`import-${pkg}`);
  const r = await mcp.call('application_import_device', { operationId, space: 'default', package: pkg, expectedGeneration: '' }, { timeoutMs: 240000 });
  return { space: 'default', package: pkg, installGeneration: r.generation, generation: r.generation, state: 'COMMITTED' };
}

async function run(pkg) {
  const startedAt = Date.now();
  const mark = (label) => console.log(`[step +${Math.round((Date.now() - startedAt) / 100) / 10}s] ${pkg} ${label}`);
  mark('begin');
  await adb(['shell', 'settings put system accelerometer_rotation 0']).catch(() => {});
  await adb(['shell', 'settings put system user_rotation 0']).catch(() => {});
  const rec = { package: pkg, mode: MODE, startedAt: new Date().toISOString(), steps: [], screens: [] };
  const hostReady = await resetHost();
  rec.steps.push('host-reset=' + hostReady);
  mark('host-reset=' + hostReady);
  const install = await ensureImported(pkg);
  mark('imported=' + (install.installGeneration || install.generation));
  const gen = install.installGeneration || install.generation;
  rec.installGeneration = gen;
  const target = { space: 'default', package: pkg, installGeneration: gen };
  flow.pkg = pkg; // the UI driver must resolve this guest's process, not the host screen
  flow._target = null;
  flow._legacyDump = false;
  for (let i = 0; i < 2 && (await hostLoading()); i++) await sleep(1500);
  mark('host-idle');

  // Launch with up to three attempts: an engine restart mid-run can steal focus
  // back to the workbench, which must not be mistaken for an app failure.
  let pre = [];
  let started = null;
  for (let attempt = 0; attempt < 3; attempt++) {
    if (attempt) {
      await mcp.call('application_stop', { operationId: opid(`stop-retry-${pkg}`), target }).catch(() => {});
      await resetHost({ force: true });
      for (let i = 0; i < 2 && (await hostLoading()); i++) await sleep(1500);
    }
    const running = await alive(pkg);
    if (running) await mcp.call('application_stop', { operationId: opid(`stop-${pkg}`), target }).catch(() => {});
    await mcp.call('application_mode_set', { operationId: opid(`mode-${pkg}-${MODE}`), target, mode: MODE });
    rec.steps.push('mode=' + MODE + (attempt ? `#retry${attempt}` : ''));
    mark('mode=' + MODE + ' -> starting');
    await adb(['logcat', '-b', 'crash', '-c']).catch(() => {});
    started = await mcp.call('process_start', {
      operationId: opid(`start-${pkg}-${MODE}`),
      target, component: '', probeRevision: '',
    }, { timeoutMs: 150000 });
    rec.processGeneration = started.processGeneration;
    rec.pid = started.pid;
    rec.steps.push('started pid=' + started.pid);
    mark('started pid=' + started.pid);
    await sleep((MODE === 'thick' ? 12000 : 8000) + attempt * 4000);
    pre = await stableTree();
    mark('pre-tree nodes=' + pre.length);
    // A live guest whose window is not composited yet still counts as started; only a
    // workbench screen with a dead process is a real launch failure.
    if (!hostScreen(pre) || (await alive(pkg, rec.processGeneration))) break;
    rec.steps.push('host-screen-after-start#' + attempt);
  }
  if (hostScreen(pre)) {
    rec.status = 'ENGINE_LOST_ON_START';
    rec.crash = await crashLog(pkg);
    rec.screens.push({ name: 'engine-lost', screenshot: await flow.shot(`${pkg}-${MODE}-engine-lost`).catch(() => null) });
    return rec;
  }
  if (!(await alive(pkg, rec.processGeneration))) {
    rec.status = 'CRASH_ON_LAUNCH';
    rec.crash = await crashLog(pkg);
    rec.screens.push({ name: 'launch-crash', screenshot: await flow.shot(`${pkg}-${MODE}-launch-crash`).catch(() => null) });
    return rec;
  }
  mark('alive-after-start -> sweep');
  const swept = await sweep(7);
  mark('sweep done: ' + (swept.join(',') || 'none'));
  if (swept.length) rec.steps.push('overlays:' + swept.join(','));
  rec.screens.push({ name: 'after-consent', screenshot: await flow.shot(`${pkg}-${MODE}-after-consent`).catch(() => null) });
  if (!(await alive(pkg, rec.processGeneration))) {
    rec.status = 'CRASH_ON_CONSENT'; rec.crash = await crashLog(pkg); return rec;
  }

  let nodes = await stableTree();
  const ls = loginSignals(nodes);
  rec.initialTexts = texts(nodes).slice(0, 18);
  rec.loginScreen = ls.login;
  rec.steps.push(`loginScreen=${ls.login} words=${ls.words} tabs=${ls.tabs} edits=${ls.edits}`);

  if (ls.login) {
    if (credentialOnly(nodes)) {
      rec.login = { ok: false, state: 'CREDENTIAL_ONLY', notes: ['no-phone-login'] };
    } else {
      mark('login-start');
      rec.login = await login(pkg, PHONES[0]);
      mark('login=' + rec.login.state);
      rec.steps.push('login:' + rec.login.state);
      if (rec.login.state === 'HOST_LOST') {
        rec.status = 'ENGINE_LOST_DURING_LOGIN';
        rec.crash = await crashLog(pkg);
        return rec;
      }
    }
  } else {
    rec.login = { ok: true, state: 'NO_LOGIN_REQUIRED' };
  }
  await sleep(1500);
  const postLoginSweep = await sweep(3);
  if (postLoginSweep.length) rec.steps.push('post-login-overlays:' + postLoginSweep.join(','));
  if (!(await alive(pkg, rec.processGeneration))) {
    rec.status = 'CRASH_DURING_LOGIN'; rec.crash = await crashLog(pkg);
    rec.screens.push({ name: 'login-crash', screenshot: await flow.shot(`${pkg}-${MODE}-login-crash`).catch(() => null) });
    return rec;
  }

  // Even when login is unavailable, exercise whatever public UI is reachable.
  mark('browse-start');
  const browse = await deepBrowse(pkg, rec.processGeneration, { maxTabs: ls.login && !rec.login.ok ? 2 : 4, maxCards: 1 });
  mark('browse done: ' + browse.visited.length + ' screens');
  rec.browse = browse;
  rec.screens.push(...browse.visited);
  rec.steps.push('screens=' + browse.visited.length);
  if (browse.crashed) {
    rec.status = rec.login.ok ? 'CRASH_DURING_BROWSE' : 'CRASH_DURING_BROWSE_BEFORE_LOGIN';
    rec.crash = await crashLog(pkg);
    return rec;
  }
  rec.crash = await crashLog(pkg);
  rec.aliveAtEnd = !!(await alive(pkg, rec.processGeneration));
  if (!rec.aliveAtEnd) { rec.status = 'CRASH_AT_END'; return rec; }
  if (!rec.login.ok) rec.status = rec.login.state === 'CREDENTIAL_ONLY' ? 'LIMITED_CREDENTIAL_ONLY' : 'LOGIN_FAILED';
  else if (browse.visited.length >= 4) rec.status = 'PASS';
  else rec.status = 'PASS_SHALLOW';
  return rec;
}

let apps = null;
for (let i = 0; i < 5 && !apps; i++) {
  try { apps = await mcp.call('applications_list'); }
  catch (error) { console.log('[compat] applications_list retry:', String(error.message).slice(0, 140)); await sleep(6000); }
}
if (!apps) throw new Error('applications_list unavailable');
let work = (apps.installations || []).filter((i) => i.state === 'COMMITTED'
  && !i.package.startsWith('dev.tripoder.')).map((i) => i.package).sort();
if (only) work = only;
work = work.slice(from, from + limit);
console.log(`[compat] mode=${MODE} apps=${work.length} resume=${resume ? Object.keys(state).length : 0}`);

for (const pkg of work) {
  if (resume && state[pkg] && state[pkg].status && !['ERROR', 'UNKNOWN'].includes(state[pkg].status)) continue;
  console.log(`\n=== ${pkg} (${MODE}) ===`);
  const t0 = Date.now();
  let rec;
  const WATCHDOG = Number(arg('watchdog', '210000'));
  try {
    rec = await Promise.race([
      run(pkg),
      new Promise((resolve) => setTimeout(() => resolve({
        package: pkg, mode: MODE, status: 'APP_TIMEOUT', screens: [],
        error: `watchdog ${WATCHDOG}ms exceeded`,
      }), WATCHDOG).unref()),
    ]);
  } catch (error) {
    rec = { package: pkg, mode: MODE, status: 'ERROR', error: String(error.message || error).slice(0, 900), screens: [] };
  }
  rec.elapsedMs = Date.now() - t0;
  rec.log = flow.log.slice(-80);
  flow.log = [];
  state[pkg] = rec;
  await save();
  await ensureDir(resolve(OUT, MODE, pkg));
  await writeFile(resolve(OUT, MODE, pkg, 'result.json'), JSON.stringify(rec, null, 2));
  console.log(`[${rec.status}] ${pkg} login=${rec.login?.state || '-'} screens=${rec.screens?.length || 0} ${rec.elapsedMs}ms ${rec.error || ''}`);
  await mcp.call('application_stop', { operationId: opid(`stopfin-${pkg}`), target: { space: 'default', package: pkg, installGeneration: rec.installGeneration } }).catch(() => {});
  await sleep(700);
  if (rec.elapsedMs > appBudgetMs) console.log('[compat] app exceeded budget', pkg, rec.elapsedMs);
}
console.log('[compat] DONE', MODE, Object.values(state).filter((r) => r.status === 'PASS').length, '/', Object.keys(state).length);
await mcp.close();
