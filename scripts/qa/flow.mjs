// UIAutomator-based guest UI driver: real accessibility tree + adb input + screenshots.
// Works for View/Compose/Flutter/WebView apps because the guest window is composed into the host activity.
import { Mcp, adb, sleep, saveShot, ensureDir, targetOf, waitForCode } from './mcp.mjs';
import { resolve } from 'node:path';
import { writeFile, mkdir } from 'node:fs/promises';

// Set TP_QA_PHONES to one or two comma-separated test numbers in the local environment.
const configuredPhones = process.env.TP_QA_PHONES;
if (typeof configuredPhones !== 'string') throw new Error('TP_QA_PHONES_REQUIRED');
export const PHONES = configuredPhones.split(',').map((phone) => phone.trim());
if (PHONES.length < 1 || PHONES.length > 2 || PHONES.some((phone) => !/^1[3-9]\d{9}$/.test(phone))) {
  throw new Error('TP_QA_PHONES_REQUIRES_ONE_OR_TWO_MOBILE_NUMBERS');
}

export class Flow {
  constructor(mcp, { artifacts = resolve(process.cwd(), 'artifacts/qa/deep'), verbose = true } = {}) {
    this.mcp = mcp;
    this.artifacts = artifacts;
    this.verbose = verbose;
    this.log = [];
    this.step = 0;
  }
  note(...args) { const line = args.join(' '); this.log.push(line); if (this.verbose) console.log(line); }

  async installation(pkg) {
    const apps = await this.mcp.call('applications_list');
    const found = (apps.installations || []).find((i) => i.package === pkg && i.state === 'COMMITTED');
    if (!found) throw new Error('NOT_INSTALLED ' + pkg);
    return found;
  }
  async live(pkg) {
    const t = await this.mcp.call('targets_list');
    const self = (t.targets || []).find((x) => x.package === pkg && x.processName === pkg);
    if (!self) return null;
    return { space: self.space, package: self.package, installGeneration: self.installGeneration, processGeneration: self.processGeneration };
  }

  async start(pkg, mode = 'thin', { settleMs = 9000, component = '' } = {}) {
    this.pkg = pkg;
    const inst = await this.installation(pkg);
    const target = targetOf(inst);
    await this.mcp.call('application_stop', { operationId: `f-stop-${Date.now()}`, target }).catch(() => {});
    await this.mcp.call('application_mode_set', { operationId: `f-mode-${Date.now()}`, target, mode });
    const probes = await this.mcp.call('probes_get', { target });
    const t0 = Date.now();
    const started = await this.mcp.call('process_start', {
      operationId: `f-start-${Date.now()}`, target, component, probeRevision: probes.revision ?? '',
    }, { timeoutMs: 120000 });
    this.note(`start ${pkg} ${mode} pid=${started.pid} ${Date.now() - t0}ms`);
    await sleep(settleMs);
    return started;
  }
  async stop(pkg) {
    const inst = await this.installation(pkg);
    return this.mcp.call('application_stop', { operationId: `f-stop2-${Date.now()}`, target: targetOf(inst) }).catch(() => {});
  }

  /** Accessibility tree of the current screen.
   *  Two providers: the in-process guest dump (fast, no accessibility idle wait) and
   *  uiautomator (sees static dialogs the guest dump can miss when its main thread is busy).
   *  The driver prefers whichever produced text for this package last time. */
  async tree() {
    const target = await this.activeTarget().catch(() => null);
    const textCount = (nodes) => nodes.filter((n) => (n.text || n.desc || '').trim()).length;
    let process = null;
    if (target) process = await this.processTree(target);
    if (process && textCount(process.nodes) > 0 && !this._legacyDump) return process;
    const legacy = await this.legacyTree();
    if (legacy.nodes.length && textCount(legacy.nodes) > 0) {
      if (!process || textCount(process.nodes) === 0) this._legacyDump = true;
      return legacy;
    }
    if (process && process.nodes.length) return process;
    if (legacy.nodes.length) return legacy;
    return process ?? legacy;
  }

  /** In-process hierarchy through the guest process (authoritative, never blocks on idle). */
  async processTree(target) {
    let dump = null;
    try { dump = await this.mcp.call('ui_dump_hierarchy', { target, includeInvisible: true }, { timeoutMs: 10000 }); }
    catch (error) { this.note('ui-dump-error:', String(error.message).slice(0, 90)); }
    if (!dump) return null;
    // Several guest activities can be attached at once; the foreground one is the
    // activity that actually exposes visible nodes.
    const score = (activity) => {
      const nodes = activity.nodes ?? [];
      return nodes.filter((n) => n.visible !== false).length * 1000 + nodes.length;
    };
    const activity = (dump.activities ?? [])
      .filter((activity) => (activity.nodes ?? []).length)
      .sort((a, b) => score(b) - score(a))[0];
    if (!activity) return { xml: '', nodes: [], root: null, activity: null };
    const all = activity.nodes ?? [];
    const visible = all.filter((n) => n.visible !== false);
    const source = visible.length ? visible : all;
    const nodes = source.map((n) => {
      const b = n.bounds ?? [0, 0, 0, 0];
      return {
        text: n.text || '', desc: n.contentDescription || '', cls: n.className || '',
        clickable: !!n.clickable, checked: !!n.checked, selected: !!n.selected,
        enabled: n.enabled !== false, focused: !!n.focused,
        bounds: [b[0], b[1], b[2], b[3]],
        centre: [Math.round((b[0] + b[2]) / 2), Math.round((b[1] + b[3]) / 2)],
        pkg: activity.activity || '', rid: n.id || '',
      };
    });
    return { xml: '', nodes, root: nodes[0] || null, activity: activity.activity };
  }

  /** Platform uiautomator snapshot; slower and idle-sensitive, kept as a cross-check. */
  async legacyTree() {
    let xml = '';
    const remote = `/sdcard/qa-ui-${Date.now()}.xml`;
    const out = await adb(['shell', `uiautomator dump ${remote}`], { timeout: 12000 }).catch(() => '');
    if (/dumped to/i.test(String(out))) {
      xml = await adb(['shell', `cat ${remote}`], { timeout: 20000 }).catch(() => '');
      await adb(['shell', `rm -f ${remote}`]).catch(() => {});
    }
    const nodes = [];
    if (xml.includes('<hierarchy')) {
      const re = /<node\b([^>]*?)\/?>/g;
      let m;
      while ((m = re.exec(xml))) {
        const attrs = {};
        const attrRe = /([a-zA-Z:_-]+)="([^"]*)"/g;
        let a;
        while ((a = attrRe.exec(m[1]))) attrs[a[1]] = a[2];
        const b = (attrs.bounds || '').match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
        if (!b) continue;
        nodes.push({
          text: attrs.text || '', desc: attrs['content-desc'] || '', cls: attrs.class || '',
          clickable: attrs.clickable === 'true', checked: attrs.checked === 'true',
          selected: attrs.selected === 'true', enabled: attrs.enabled !== 'false',
          focused: attrs.focused === 'true',
          bounds: [+b[1], +b[2], +b[3], +b[4]],
          centre: [Math.round((+b[1] + +b[3]) / 2), Math.round((+b[2] + +b[4]) / 2)],
          pkg: attrs.package || '',
        });
      }
    }
    return { xml, nodes, root: nodes[0] || null, activity: null };
  }

  texts(nodes = []) { return nodes.filter((n) => n.text || n.desc).map((n) => (n.text || n.desc).replace(/\s+/g, ' ')); }

  find(nodes, pattern, { clickableOnly = false, cls = '' } = {}) {
    const matcher = typeof pattern === 'string' ? (v) => v.includes(pattern) : (v) => pattern.test(v);
    return nodes.find((n) => (!clickableOnly || n.clickable) && (!cls || n.cls.includes(cls))
      && (matcher(n.text) || matcher(n.desc)));
  }
  findAll(nodes, pattern) {
    const matcher = typeof pattern === 'string' ? (v) => v.includes(pattern) : (v) => pattern.test(v);
    return nodes.filter((n) => matcher(n.text) || matcher(n.desc));
  }

  /** Resolve the guest process target, cached briefly to keep taps fast. */
  async activeTarget(pkg = this.pkg) {
    if (!pkg) return null;
    const now = Date.now();
    if (this._target && this._target.pkg === pkg && now - this._target.at < 4000) return this._target.target;
    const found = await this.live(pkg).catch(() => null);
    if (!found) return null;
    this._target = { pkg, at: now, target: found };
    return found;
  }

  /** In-process UI action (performClick/setText) avoids edge-gesture interception by guest UIs. */
  async ui(action, pkg = this.pkg) {
    const target = await this.activeTarget(pkg);
    if (!target) return null;
    const operationId = `f-ui-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
    try { return await this.mcp.call('ui_dispatch_action', { operationId, target, action }); }
    catch (error) { this.note('ui-action failed:', String(error.message).slice(0, 140)); return null; }
  }

  async tapAt(x, y) { await adb(['shell', `input tap ${Math.round(x)} ${Math.round(y)}`]); await sleep(900); }
  async tapNode(node) {
    if (!node) return false;
    const label = (node.text || node.desc || '').trim();
    if (label && label.length <= 40 && !Flow.DANGER.test(label) && !/^\d+$/.test(label) && this.pkg) {
      const result = await this.ui({ type: 'clickText', text: label });
      if (result && result.clicked) { this._target = null; await sleep(650); return true; }
    }
    await this.tapAt(node.centre[0], node.centre[1]);
    this._target = null;
    return true;
  }
  /** Destructive/side-effecting labels never tapped by the generic driver. */
  static DANGER = /支付|付款|立即购买|购买|下单|提交订单|确认订单|开通|注销|退出登录|删除|清空|解绑|充值|提现|转账|发送红包|打赏|预约|抢购|秒杀/;
  async tapText(text, opts = {}) {
    const { nodes } = await this.tree();
    const node = this.find(nodes, text, opts);
    if (!node) { this.note('tapText MISS', text); return false; }
    if (Flow.DANGER.test(node.text || node.desc)) { this.note('tapText SKIP-DANGER', (node.text || node.desc).slice(0, 30)); return false; }
    this.note('tap', (node.text || node.desc).slice(0, 30));
    return this.tapNode(node);
  }
  async type(text) {
    const escaped = String(text).replace(/([%\s])/g, '\\$1');
    await adb(['shell', `input text "${escaped}"`]);
    await sleep(700);
  }
  async back() { await adb(['shell', 'input keyevent 4']); await sleep(1200); }
  async swipeUp() { await adb(['shell', 'input swipe 600 1900 600 700 260']); await sleep(1200); }
  async swipeDown() { await adb(['shell', 'input swipe 600 700 600 1900 260']); await sleep(1200); }
  async key(code) { await adb(['shell', `input keyevent ${code}`]); await sleep(600); }

  /** A logged-in/guest main surface, as opposed to a consent sheet or login form. */
  mainScreen(nodes) {
    const t = this.texts(nodes);
    const all = t.join(' | ');
    if (/请输入手机号码|请输入手机号|验证并登录|获取验证码|请阅读并同意以下条款/.test(all)) return false;
    const tabs = new Set(t.filter((x) => /^(首页|推荐|发现|关注|消息|我的|购物车|分类|视频|直播|同城|附近|书架|频道|工作台|通讯录|钱包|商城|服务|社区|音乐|电台|排行|市集|我)$/.test(x.trim())));
    if (tabs.size >= 2) return true;
    return /跳过|开始使用|进入应用|立即体验/.test(all) && !this.loginMarkers(nodes);
  }

  async shot(name) {
    await ensureDir(this.artifacts);
    return saveShot(this.artifacts, name);
  }
  async screenshotWithText(pkg, name) {
    const path = await this.shot(`${pkg}-${name}`);
    const { nodes } = await this.tree();
    return { path, texts: this.texts(nodes) };
  }

  loginMarkers(nodes) {
    const all = this.texts(nodes).join(' | ');
    const hasPhoneEntry = /手机号|手机号码|一键登录|密码登录|验证码登录/.test(all);
    const hasCredentialForm = nodes.some((n) => /EditText/.test(n.cls)) && /登录|登陆|账号|帐户|密码/.test(all);
    return hasPhoneEntry || hasCredentialForm
      || /验证码|登录\/注册|登录注册|注册\/登录|立即登录|请输入手机|账号登录/.test(all);
  }

  /** True when the only way in is a password/third-party account we do not own. */
  credentialOnly(nodes) {
    const all = this.texts(nodes).join(' | ');
    return !/手机号|手机号码|一键登录|获取验证码|发送验证码/.test(all)
      && /密码|账号|帐户|邮箱|GitHub|Google|Apple|微信登录|QQ登录/.test(all);
  }

  /** Tick an agreement checkbox or dismiss an "abandon login?" prompt. Returns true when it acted. */
  async handleAgreement(nodes) {
    const joined = this.texts(nodes).join(' | ');
    if (/要放弃登录吗/.test(joined)) {
      const keep = this.find(nodes, /^继续登录$/, { clickableOnly: true }) || this.find(nodes, /^继续登录$/);
      if (keep) { await this.tapNode(keep); this.note('abandon-login dialog -> continue'); await sleep(600); return 'abandon'; }
    }
    // XHS-style labelled checkbox: content-desc/text "未选中，同意" / "已选中，同意".
    // Some builds expose only a tiny content-desc="同意" Button whose real state is
    // `selected` (checked=false), so never toggle a box that is already on: doing so
    // silently disables the login button and produces an endless consent loop.
    const hasAgreementText = nodes.some((n) => /我已阅读并同意|已阅读并同意|用户协议|隐私政策/.test(n.text || n.desc || ''));
    const toggle = nodes.find((n) => /^(同意|未选中，同意|已选中，同意)$/.test((n.desc || n.text || '').trim())
      && (n.bounds[2] - n.bounds[0]) < 180 && n.clickable);
    if (toggle) {
      const on = toggle.checked || toggle.selected || /已选中/.test(toggle.desc || toggle.text || '');
      if (on) { this.note('agreement already checked'); return false; }
      if (hasAgreementText) {
        await this.tapNode(toggle);
        this.note('ticked agreement toggle');
        await sleep(600);
        return 'tick';
      }
    }
    const labelled = nodes.find((n) => /未选中.*同意|同意.*未选中/.test((n.desc || n.text || ''))
      && (n.bounds[2] - n.bounds[0]) < 180);
    if (labelled) { await this.tapNode(labelled); this.note('ticked labelled agreement checkbox'); await sleep(600); return 'tick'; }
    const hasStatefulCheckbox = nodes.some((n) => /未选中|已选中/.test((n.desc || n.text || ''))
      || (/^同意$/.test((n.desc || n.text || '').trim()) && (n.bounds[2] - n.bounds[0]) < 180));
    const checkbox = nodes.find((n) => /CheckBox|CheckView|CompoundButton/.test(n.cls) && !n.checked && n.clickable);
    if (checkbox) { await this.tapNode(checkbox); this.note('ticked agreement checkbox'); await sleep(600); return 'tick'; }
    // Text row with a small unlabeled tick box at its left (only when there is room for one).
    const agree = !hasStatefulCheckbox && nodes.find((n) => /我已阅读并同意|已阅读并同意/.test(n.text || '')
      && n.bounds[0] > 90 && n.bounds[0] < 500);
    if (agree) { await this.tapAt(agree.bounds[0] - 45, agree.bounds[1] + 24); this.note('ticked near agreement text'); await sleep(600); return 'tick'; }
    return false;
  }

  /** Generic phone/SMS login attempt. Returns {state, detail, codeUsed}. */
  async login(pkg, { phone = PHONES[1], maxRounds = 14, smsTimeoutMs = 75000 } = {}) {
    let codeRequestedAt = 0;
    let codeUsed = null;
    let oneClickTried = false;
    let phoneRowTried = false;
    const trail = [];
    const filled = (node) => {
      const digits = String(node?.text || '').replace(/[^\d*]/g, '');
      return /^1\d{10}$/.test(digits) || /\*{2,}/.test(digits);
    };
    const codeFilled = (node) => /\d{4,6}/.test((node?.text || '').replace(/[*•\s]/g, ''));
    const rejectedText = (texts) => /验证码错误|验证码不正确|验证码已失效|验证码失效|请重新获取|登录失败|账号异常|操作频繁|网络异常/.test(texts);

    for (let round = 0; round < maxRounds; round++) {
      let { nodes } = await this.tree();
      let texts = this.texts(nodes);
      let joined = texts.join(' | ');
      trail.push(texts.slice(0, 8));
      this.note(`round ${round}:`, texts.slice(0, 10).join(' | ').slice(0, 300));

      // 0a. Lost the guest window (engine restart) — stop immediately; taps would hit
      //     the Tripoder workbench instead of the app and corrupt the run.
      if (/应用标签/.test(joined) && /设置标签|连接工具|正在读取/.test(joined)) {
        this.note('guest window lost: Tripoder workbench in front');
        return { state: 'HOST_LOST', detail: 'workbench-visible', trail, codeUsed };
      }

      // 0. Already inside the app? A late auto-login (carrier auth completing in the
      //    background) must not be undone by re-driving a login CTA on the main UI.
      if (round > 0 && this.mainScreen(nodes) && !this.loginMarkers(nodes)) {
        await sleep(2500);
        const confirm = await this.tree();
        if (this.mainScreen(confirm.nodes) && !this.loginMarkers(confirm.nodes)) {
          this.note('login observed on main screen (round', round + ')');
          await this.shot(`${pkg}-login-ok`);
          return { state: 'LOGGED_IN', detail: `main-screen-round-${round}`, trail, codeUsed: Boolean(codeUsed) };
        }
      }

      // 0. welcome screens that only expose 登录 / 注册 (e.g. WeChat splash)
      if (round < 3 && !nodes.some((n) => /EditText/.test(n.cls))
        && nodes.some((n) => /^登录$/.test((n.text || '').trim()) && n.clickable)
        && nodes.some((n) => /^注册$/.test((n.text || '').trim()))) {
        const login = nodes.find((n) => /^登录$/.test((n.text || '').trim()) && n.clickable);
        await this.tapNode(login); this.note('welcome -> login'); await sleep(2500); continue;
      }

      // 0. consent sheets / agreement checkboxes / abandon-login dialogs, every round
      if (/请阅读|同意|要放弃登录|我已阅读/.test(joined)) {
        const act = await this.handleAgreement(nodes);
        if (act === 'abandon') { await sleep(800); continue; }
        if (act === 'tick') {
          await sleep(700);
          nodes = (await this.tree()).nodes;
          texts = this.texts(nodes);
          joined = texts.join(' | ');
        }
      }

      // 0b. carrier consent sheet: grant the carrier-auth terms first. The phone
      //     icon below "更多登录方式" is only a fallback when no consent action exists.
      if (/请阅读并同意以下条款/.test(joined) && /更多登录方式/.test(joined)) {
        const agree = nodes.find((n) => /^同意并继续$/.test((n.text || n.desc || '').trim()));
        if (agree) {
          await this.tapNode(agree);
          this.note('carrier consent -> agree');
          await sleep(6000);
          continue;
        }
        const phoneEntry = nodes.find((n) => /手机号登录/.test((n.desc || n.text || '')) && n.centre[1] > 1800)
          || nodes.find((n) => /手机号登录/.test((n.desc || n.text || '')) && n.clickable);
        if (phoneEntry) {
          await this.tapNode(phoneEntry);
          this.note('consent sheet -> phone login entry');
          await sleep(2500);
          continue;
        }
        // The icon below "更多登录方式" sometimes has no accessibility label.
        const more = nodes.find((n) => /更多登录方式/.test(n.text || ''));
        if (more) {
          await this.tapAt(more.centre[0], more.bounds[3] + 105);
          this.note('consent sheet -> phone icon below more');
          await sleep(2500);
          continue;
        }
      }

      // 1. agree to protocols if a checkbox row is present (after a fresh render)
      if (round === 0 && /请阅读并同意|我已阅读并同意|已阅读并同意/.test(joined)) {
        await this.handleAgreement(nodes);
      }

      // 2. carrier one-click login is the fastest path
      if (!phoneRowTried) {
        const row = this.find(nodes, /使用\s*\+?86[\d*\s]{6,}登录|使用.*手机号登录|使用.*号码登录/);
        if (row && !/一键登录/.test(row.text || '')) {
          phoneRowTried = true;
          await this.tapNode(row);
          this.note('tapped masked-phone login row');
          await sleep(3000);
          continue;
        }
      }
      if (!oneClickTried) {
        const oneClick = this.find(nodes, /一键登录|本机号码一键登录|手机号一键登录/);
        if (oneClick) {
          oneClickTried = true;
          await this.tapNode(oneClick);
          this.note('tapped one-click login');
          await sleep(5000);
          let after = await this.tree();
          // A transition can yield an empty dump; re-read before declaring success.
          for (let k = 0; k < 3 && !after.nodes.length; k++) { await sleep(2500); after = await this.tree(); }

          // "更多登录方式" leads to the editable SMS form, which is more deterministic than carrier auth.
          const more = this.find(after.nodes, /更多登录方式|其他登录方式|其他手机号|使用其他手机号|更换手机号|切换账号/);
          if (more) { await this.tapNode(more); this.note('one-click -> more methods'); await sleep(2500); continue; }

          // Carrier auth sometimes asks for agreement first; tick and continue.
          if (/请阅读并同意|认证服务条款/.test(this.texts(after.nodes).join(' | '))) {
            const cb = after.nodes.find((n) => /CheckBox|CheckView/.test(n.cls))
              || this.find(after.nodes, /请阅读并同意/);
            if (cb) { await this.tapAt(Math.max(20, cb.bounds[0] - 40), cb.centre[1]); this.note('carrier agree toggle'); await sleep(600); }
            const cont = this.find(after.nodes, /^(同意并继续|同意|继续)$/, { clickableOnly: true });
            if (cont) { await this.tapNode(cont); this.note('carrier agree continue'); await sleep(5000); after = await this.tree(); }
          }
          if (this.mainScreen(after.nodes)) {
            await this.shot(`${pkg}-login-ok`);
            return { state: 'LOGGED_IN', detail: 'carrier-one-click', trail, codeUsed: Boolean(codeUsed) };
          }
          continue;
        }
      }

      // 3. choose phone login entry when on a chooser screen
      const entry = this.find(nodes, /密码登录|手机号登录|手机登录|验证码登录|账号密码登录|其他登录方式|更多登录方式/, { clickableOnly: true });
      const hasPhoneField = nodes.some((n) => /EditText|TextField|SecureEditText/.test(n.cls));
      if (entry && !hasPhoneField && !/一键登录/.test(entry.text)) { await this.tapNode(entry); this.note('open phone login'); continue; }

      // 3b. WeChat-style phone page defaults to password; switch to SMS verification.
      const smsEntry = this.find(nodes, /用短信验证码登录|短信验证码登录/, { clickableOnly: true });
      if (smsEntry && !/获取验证码|验证码/.test(joined)) {
        await this.tapNode(smsEntry); this.note('switch to sms login'); await sleep(2200); continue;
      }
      // 3c. WeChat first-run phone consent button (not the carrier consent sheet).
      const continueButton = nodes.find((n) => /^同意并继续$/.test((n.text || '').trim()) && n.clickable);
      if (continueButton && !/更多登录方式|中国移动认证服务条款/.test(joined)
        && nodes.some((n) => /EditText/.test(n.cls))) {
        await this.tapNode(continueButton); this.note('phone consent -> continue'); await sleep(2600); continue;
      }

      // 4. fill phone number into the phone-ish editable
      const editables = nodes.filter((n) => /EditText|TextField|SecureEditText/.test(n.cls));
      const phoneNode = editables.find((n) => /手机|电话|号码|账号|phone/i.test(n.text + n.desc)) || editables[0];
      const codeNode = editables.find((n) => /验证码|动态码|code/i.test(n.text + n.desc)) || (editables.length > 1 ? editables[1] : null);

      if (phoneNode && !filled(phoneNode)) {
        await this.tapNode(phoneNode);
        this.key(123); // move caret to end
        await this.type(phone);
        this.note('typed phone');
      } else if (!editables.length) {
        // Flutter/Compose login pages may expose only text nodes; focus the hint and type blind.
        const hint = this.find(nodes, /手机号|手机号码|请输入手机|账号|phone/i);
        if (hint) {
          await this.tapNode(hint);
          await sleep(600);
          await this.type(phone);
          this.note('typed phone blind');
        }
      }

      // 5. request code
      const codeButton = this.find(nodes, /获取验证码|发送验证码|获取短信验证码|获取动态码|获取语音验证码|点击获取|发送|验证并登录|验证登录|下一步/);
      if (codeButton && !codeUsed && !/重新获取|重新发送/.test(codeButton.text)) {
        codeRequestedAt = Date.now();
        await this.tapNode(codeButton);
        this.note('requested sms code');
        const got = await waitForCode({ sinceMs: codeRequestedAt - 3000, timeoutMs: smsTimeoutMs });
        if (got) {
          codeUsed = got.code;
          this.note('sms code received');
          // The code field often appears only after the SMS arrives; poll instead of
          // submitting the phone-only form (which re-opens carrier consent).
          let target = null;
          for (let attempt = 0; attempt < 7; attempt++) {
            const updated = (await this.tree()).nodes;
            const fields = updated.filter((n) => /EditText|TextField|SecureEditText/.test(n.cls));
            const codeHint = updated.some((n) => /重新发送|重新获取|获取验证码|验证码已发送/.test(n.text || n.desc || ''));
            if (fields.length > 1 || codeHint) {
              target = fields.slice(1).find((n) => !codeFilled(n)) || fields[fields.length - 1] || null;
              if (target) break;
            }
            await sleep(1800);
          }
          if (target) { await this.tapNode(target); await sleep(400); await this.type(got.code); this.note('typed code'); }
          else this.note('code field not visible yet');
        } else {
          this.note('no sms code received');
          return { state: 'LOGIN_BLOCKED', detail: 'SMS_CODE_NOT_RECEIVED', trail, codeUsed: Boolean(codeUsed) };
        }
      } else if (codeUsed && codeNode && !codeFilled(codeNode)) {
        const updated = (await this.tree()).nodes;
        const target = updated.filter((n) => /EditText/.test(n.cls))[1];
        if (target) { await this.tapNode(target); await this.type(codeUsed); }
      }

      // 6. submit (re-read: the code page may have replaced the phone form)
      const current = await this.tree();
      const codeVisible = current.nodes.some((n) => /EditText|TextField|SecureEditText/.test(n.cls)
        && /^\d{4,8}$/.test((n.text || '').replace(/\s/g, '')));
      let submit = this.find(current.nodes, /^(登录|登 录|立即登录|确定|下一步|进入|登录\/注册|同意并登录|立即体验)$/, { clickableOnly: true })
        || this.find(current.nodes, /^(登录|登 录|立即登录|确定|下一步|进入|登录\/注册|同意并登录|立即体验)$/);
      // Some apps keep the "验证并登录" label for the final submit after the code field appears.
      if (!submit && codeUsed && codeVisible) submit = this.find(current.nodes, /^(验证并登录|验证登录)$/);
      if (submit && !/获取验证码|一键登录/.test(submit.text)) {
        await this.tapNode(submit);
        this.note('submitted');
        await sleep(5000);
        const after = await this.tree();
        const afterTexts = this.texts(after.nodes).join(' | ');
        if (rejectedText(afterTexts)) {
          this.note('login failed on submit:', afterTexts.slice(0, 200));
          return { state: 'LOGIN_REJECTED', detail: afterTexts.slice(0, 300), trail, codeUsed: Boolean(codeUsed) };
        }
        if (this.mainScreen(after.nodes)) {
          await sleep(2500);
          const confirm = await this.tree();
          if (this.mainScreen(confirm.nodes)) {
            this.note('login confirmed by two observations');
            await this.shot(`${pkg}-login-ok`);
            return { state: 'LOGGED_IN', detail: 'sms', trail, codeUsed: Boolean(codeUsed) };
          }
        }
      }
      await sleep(2500);
    }
    let final = await this.tree();
    for (let k = 0; k < 3 && !final.nodes.length; k++) { await sleep(2000); final = await this.tree(); }
    await this.shot(`${pkg}-login-end`);
    if (!final.nodes.length) return { state: 'LOGIN_UNKNOWN', detail: 'empty-tree', trail, codeUsed: Boolean(codeUsed) };
    return { state: this.mainScreen(final.nodes) ? 'LOGGED_IN' : 'LOGIN_BLOCKED', detail: this.texts(final.nodes).slice(0, 20), trail, codeUsed: Boolean(codeUsed) };
  }

  /** Browse the main tabs/sections, capturing a screenshot per stop and watching for crashes. */
  async browse(pkg, { stops = 5, settleMs = 2500, mode = 'thin' } = {}) {
    const seen = [];
    for (let i = 0; i < stops; i++) {
      const alive = await this.live(pkg);
      if (!alive) { this.note('process gone during browse at stop', i); return { crashed: true, stopAt: i, seen }; }
      const { nodes } = await this.tree();
      const texts = this.texts(nodes);
      seen.push({ stop: i, texts: texts.slice(0, 12), screenshot: await this.shot(`${pkg}-${mode}-browse-${i}`) });
      this.note(`browse stop ${i}:`, texts.slice(0, 6).join(' | ').slice(0, 200));
      // Bottom navigation: pick the next clickable node in the lowest band.
      const bottom = nodes.filter((n) => n.clickable && n.centre[1] > 2250 && n.bounds[3] > 2300);
      const target = bottom[i % Math.max(bottom.length, 1)];
      if (target) { await this.tapNode(target); }
      else { await this.swipeUp(); }
      await sleep(settleMs);
    }
    const alive = await this.live(pkg);
    return { crashed: !alive, seen };
  }
}
