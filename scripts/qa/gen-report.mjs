// Generate the Chinese QA report from collected evidence (import log + deep runs).
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const root = process.cwd();
const readJson = async (p, fallback = {}) => { try { return JSON.parse(await readFile(resolve(root, p), 'utf8')); } catch { return fallback; } };

const importLog = await readJson('artifacts/qa/import-log.json');
const thin = await readJson('artifacts/qa/deep-thin/deep-thin.json');
const thick = await readJson('artifacts/qa/deep-thick/deep-thick.json');
const deviceApps = await readJson('artifacts/qa/dev-apps.json', { applications: [] });
const labels = Object.fromEntries((deviceApps.applications || []).map((a) => [a.package, a.name]));

const packages = [...new Set([...Object.keys(importLog), ...Object.keys(thin), ...Object.keys(thick)])]
  .filter((p) => !p.startsWith('dev.tripoder.'))
  .sort();

const label = (pkg) => labels[pkg] || '';
const importState = (pkg) => {
  const s = importLog[pkg]?.state;
  if (s === 'SUCCEEDED' || s === 'ALREADY') return 'OK';
  return s || '未处理';
};
const importReason = (pkg) => {
  const error = importLog[pkg]?.error || '';
  const m = error.match(/"(?:error)":"([^"]+)"/);
  const raw = m ? m[1] : error;
  if (/NameNotFoundException/.test(raw)) return '设备上该包已不可解析（残留/已卸载）';
  if (/ABI_OR_LIBRARY_UNSUPPORTED/.test(raw)) return 'APK 内含不支持的 ABI/原生库';
  if (/TRANSPORT_TIMEOUT/.test(raw)) return '导入超时（控制通道）';
  if (/DeadObject|ENGINE_NOT_CONNECTED/.test(raw)) return '引擎控制通道中断';
  return raw.replace(/application_import_device TOOL_ERROR/, '').slice(0, 120);
};

const stateOf = (rec) => rec?.state || '未执行';
const loginOf = (rec) => rec?.login?.state || '-';
const isPass = (rec) => stateOf(rec) === 'PASS';
const isCrash = (rec) => stateOf(rec).includes('CRASH') || stateOf(rec) === 'ERROR';

const rows = packages.map((pkg) => ({ pkg, name: label(pkg), import: importState(pkg), thin: thin[pkg], thick: thick[pkg] }));
const imported = rows.filter((r) => r.import === 'OK');
const thinPass = rows.filter((r) => isPass(r.thin));
const thickPass = rows.filter((r) => isPass(r.thick));
const thinLogin = rows.filter((r) => loginOf(r.thin) === 'LOGGED_IN');
const thickLogin = rows.filter((r) => loginOf(r.thick) === 'LOGGED_IN');
const thinCrash = rows.filter((r) => isCrash(r.thin));
const thickCrash = rows.filter((r) => isCrash(r.thick));
const importFailed = rows.filter((r) => r.import !== 'OK');

const crashLine = (rec) => {
  const lines = (rec?.logcat || []).filter((l) => /FATAL|TripoderHook|Fatal signal|Process is going/.test(l));
  const head = lines.find((l) => /TripoderHook|FATAL EXCEPTION|Fatal signal/.test(l)) || lines[0] || rec?.error || '';
  return head.replace(/^\d\d-\d\d [\d:.]+ \w\/[^:]+:\s*/, '').slice(0, 160);
};
const fmtLogin = (rec) => {
  const state = loginOf(rec);
  const map = {
    LOGGED_IN: '已登录', NO_LOGIN_SCREEN: '无需登录', SKIPPED_BY_PROFILE: '跳过',
    CREDENTIAL_ONLY_NO_PHONE_LOGIN: '仅账号/密码登录', LOGIN_BLOCKED: '登录未完成', LOGIN_REJECTED: '登录被拒', '-': '-',
  };
  return map[state] || state;
};

const table = (list) => list.map((r, i) => `| ${i + 1} | ${r.name || '-'} | \`${r.pkg}\` | ${r.import} | ${stateOf(r.thin)} / ${fmtLogin(r.thin)} | ${stateOf(r.thick)} / ${fmtLogin(r.thick)} | ${crashLine(r.thin) || crashLine(r.thick) || ''} |`).join('\n');

const section = (title, list, intro = '') => `### ${title}（${list.length}）\n\n${intro ? intro + '\n\n' : ''}| # | 应用 | 包名 | 导入 | THIN | THICK | 首个崩溃/运行时错误 |\n|---|------|------|------|------|-------|------|\n${table(list)}\n`;

const md = `# Tripoder 全量应用 THIN / THICK 兼容性与功能测试报告

生成时间：${new Date().toISOString()}
设备：MIUI（M154FF, \`d861a4fa\`），Tripoder 宿主包 \`dev.tripoder.engine.globaltest\`（schema 35）
测试账号：通过本地环境变量 TP_QA_PHONES 显式配置，报告不记录手机号。

## 1. 结论摘要

| 指标 | 数量 |
|------|------|
| 设备第三方应用（已排除 Tripoder 自身测试包） | ${rows.length} |
| 成功导入 Tripoder | ${imported.length} |
| 导入失败 | ${importFailed.length} |
| THIN 通过（启动 + 登录 + 浏览，无崩溃） | ${thinPass.length} |
| THICK 通过（启动 + 登录 + 浏览，无崩溃） | ${thickPass.length} |
| THIN 实际完成登录 | ${thinLogin.length} |
| THICK 实际完成登录（复用已登录数据） | ${thickLogin.length} |
| THIN 崩溃/错误 | ${thinCrash.length} |
| THICK 崩溃/错误 | ${thickCrash.length} |

判定口径：应用被导入 Tripoder → 对应模式下 \`process_start\` 成功、主进程存活、完成手机号/短信登录（或本身无需登录）→ 通过底部导航/滚动浏览至少 3 个界面 → 全程无进程退出、无 FATAL、无 Tripoder 运行时自杀，即记为 PASS。

## 2. 测试方法

1. 用 \`device_applications_list\` / \`pm list packages -3\` 枚举手机上的第三方应用；
2. 通过 MCP \`application_import_device\` 逐个导入默认空间（分块传输原始 APK/split）；
3. 每个应用依次执行：\`application_stop\` → \`application_mode_set(thin|thick)\` → \`process_start\` → 存活观察 → UIAutomator 无障碍树（可覆盖 Compose/Flutter/WebView）→ 登录（手机号 + 短信验证码）→ 浏览主界面 → 截图 + logcat 崩溃证据；
4. 崩溃判定同时使用：主进程是否仍在 \`targets_list\`、\`crash_diagnostics_get\`、logcat 中 \`FATAL EXCEPTION\` / \`TripoderHook\` / \`Process is going to kill itself\`。

自动化脚本：\`scripts/qa/mcp.mjs\`、\`flow.mjs\`、\`deep.mjs\`、\`import-batch.mjs\`、\`aggregate.mjs\`。
证据目录：\`artifacts/qa/deep-thin/thin/<pkg>/\`、\`artifacts/qa/deep-thick/thick/<pkg>/\`（逐应用截图）与 \`deep-thin.json\` / \`deep-thick.json\` / \`import-log.json\`。

## 3. 导入结果

- 成功：${imported.length} / ${rows.length}
- 失败：${importFailed.length}

${importFailed.length ? importFailed.map((r, i) => `${i + 1}. \`${r.pkg}\` ${r.name ? '(' + r.name + ')' : ''} — ${importReason(r.pkg)}`).join('\n') : '无'}

## 4. THIN 模式结果

- 通过：${thinPass.length}
- 登录失败/被挡：${rows.filter((r) => ['LOGIN_BLOCKED', 'LOGIN_REJECTED'].includes(loginOf(r.thin))).length}
- 仅支持账号密码/第三方登录（无手机号登录，无法用给定手机号完成）：${rows.filter((r) => loginOf(r.thin) === 'CREDENTIAL_ONLY_NO_PHONE_LOGIN').length}
- 崩溃/错误：${thinCrash.length}

${section('THIN 通过', thinPass)}
${section('THIN 崩溃或错误', thinCrash)}

## 5. THICK 模式结果

- 通过：${thickPass.length}
- 登录失败/被挡：${rows.filter((r) => ['LOGIN_BLOCKED', 'LOGIN_REJECTED'].includes(loginOf(r.thick))).length}
- 仅支持账号密码/第三方登录：${rows.filter((r) => loginOf(r.thick) === 'CREDENTIAL_ONLY_NO_PHONE_LOGIN').length}
- 崩溃/错误：${thickCrash.length}

${section('THICK 通过', thickPass)}
${section('THICK 崩溃或错误', thickCrash)}

## 6. 已定位的运行时缺陷

### 6.1 \`FIXED_BOOT_MANAGED_ADAPTER_REQUIRED:android.app.IntentService.onCreate\`

在 THIN 与 THICK 下均可复现（示例：\`com.zhihu.android\`、\`cn.wangzhe...\` 等启动早期创建 \`IntentService\` 的应用）。堆栈：

\`\`\`
E/TripoderHook: FIXED_INSTALLER_PARTIAL_PUBLICATION
E/TripoderHook: java.lang.IllegalArgumentException: FIXED_BOOT_MANAGED_ADAPTER_REQUIRED:android.app.IntentService.onCreate
    at dev.tripoder.selfhook.FrameworkAccess.requireManagedHookTarget(FrameworkAccess.java:169)
    at dev.tripoder.selfhook.ManagedHook.validateTarget(ManagedHook.java:245)
    at dev.tripoder.selfhook.ManagedHook.prepareBatch(ManagedHook.java:189)
    at dev.tripoder.runtime.RuntimeManagedInstaller.install(RuntimeManagedInstaller.java:54)
    at dev.tripoder.runtime.RuntimeManagedInstaller.installLifecycle(RuntimeManagedInstaller.java:37)
    at dev.tripoder.runtime.RuntimeSelfHookInstallers.install(RuntimeSelfHookInstallers.java:16)
    at dev.tripoder.runtime.RuntimeSelfHookComponents.install(RuntimeSelfHookComponents.java:129)
    at dev.tripoder.runtime.RuntimeSelfHookComponents.installService(RuntimeSelfHookComponents.java:95)
    at dev.tripoder.runtime.RuntimeBridge.installServiceAdapter(RuntimeBridge.java:95)
I/Process: Process is going to kill itself!   (SIG 9)
\`\`\`

含义：guest 在 \`Application\` 之后的第一个 \`Service\`（继承 \`IntentService\`、未覆写 \`onCreate\`）触发服务生命周期适配安装，适配器把框架类 \`android.app.IntentService.onCreate\` 当成需要 Hook 的目标，\`FrameworkAccess\` 拒绝未登记的 boot 方法，\`RuntimeManagedInstaller\` 采用 fail-closed 直接 \`killProcess\`，表现为"应用闪退/无法进入首页"。

建议修复方向：\`RuntimeSelfHookComponents.installService\` 只对 guest 类自身声明的生命周期方法安装回调（\`publicLifecycle\` 已具备该过滤，需确认实际安装路径走的是它），或在 \`FrameworkAccess\` 白名单中登记 \`IntentService\` 生命周期；同时把"部分发布"路径改为跳过该回调而不是终止进程。

## 7. 复现方式

\`\`\`bash
# 单应用双模式深测
node scripts/qa/deep.mjs --mode thin  --packages com.zhihu.android --out artifacts/qa/debug
node scripts/qa/deep.mjs --mode thick --packages com.zhihu.android --out artifacts/qa/debug

# 全量
node scripts/qa/import-batch.mjs
node scripts/qa/deep.mjs --mode thin  --resume
node scripts/qa/deep.mjs --mode thick --resume
node scripts/qa/aggregate.mjs
\`\`\`

## 8. 限制

- 仅覆盖第三方（\`pm list packages -3\`）应用；MIUI 系统组件（\`com.android.*\`、多数 \`com.xiaomi.*\`）没有可登录的用户功能，未纳入。
- 游戏类（原神/王者荣耀/和平精英等）需要 QQ/微信鉴权与实名，无法用手机号短信完成登录，仅记录启动/浏览结果。
- 银行、券商类应用（如 \`cmb.pb\`、\`com.hundsun.winner.pazq\`）依赖设备指纹与安全键盘，登录一般会被风控拦截。
- 短信验证码由手机 SIM 收取后读取，部分应用有频率限制，自动化只会请求一次验证码。
`;

await writeFile(resolve(root, 'artifacts/qa/report.md'), md);
console.log('report written', { rows: rows.length, imported: imported.length, thinPass: thinPass.length, thickPass: thickPass.length });
