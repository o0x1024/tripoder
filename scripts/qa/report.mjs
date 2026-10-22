// Build the Chinese QA report for the THIN/THICK campaign from compat.mjs state files.
// usage: node scripts/qa/report.mjs [--campaign artifacts/qa/campaign] [--out artifacts/qa/report.md]
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const exec = promisify(execFile);
const arg = (name, fallback = '') => {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : fallback;
};
const root = process.cwd();
const campaign = arg('campaign', 'artifacts/qa/campaign');
const outFile = resolve(root, arg('out', 'artifacts/qa/report.md'));
const read = async (p, fallback) => { try { return JSON.parse(await readFile(resolve(root, p), 'utf8')); } catch { return fallback; } };

const NAMES = {
  'com.tencent.mm': '微信', 'com.tencent.mobileqq': 'QQ', 'com.sina.weibo': '微博', 'com.xingin.xhs': '小红书',
  'org.telegram.messenger': 'Telegram', 'com.zhihu.android': '知乎', 'com.taou.maimai': '脉脉', 'com.hpbr.bosszhipin': 'BOSS直聘',
  'com.ss.android.article.news': '今日头条', 'com.ss.android.article.lite': '今日头条极速版', 'com.tencent.news': '腾讯新闻',
  'com.duokan.reader': '多看阅读', 'com.phoenix.read': '番茄免费小说', 'com.dragon.read': '番茄小说', 'com.fenxiang.storyline': '故事线',
  'com.taobao.taobao': '淘宝', 'com.jingdong.app.mall': '京东', 'com.xunmeng.pinduoduo': '拼多多', 'com.sankuai.meituan': '美团',
  'com.sankuai.meituan.takeoutnew': '美团外卖', 'com.dianping.v1': '大众点评', 'com.taobao.idlefish': '闲鱼',
  'com.achievo.vipshop': '唯品会', 'com.alibaba.android.rimet': '钉钉', 'com.eg.android.AlipayGphone': '支付宝',
  'cmb.pb': '招商银行', 'com.mipay.wallet': '小米钱包', 'com.xiaomi.youpin': '小米有品', 'com.xiaomi.shop': '小米商城',
  'com.xiaomi.smarthome': '米家', 'tv.danmaku.bili': '哔哩哔哩', 'com.ss.android.ugc.aweme': '抖音', 'com.smile.gifmaker': '快手',
  'com.qiyi.video': '爱奇艺', 'com.tencent.qqlive': '腾讯视频', 'com.hunantv.imgo.activity': '芒果TV', 'com.netease.cloudmusic': '网易云音乐',
  'com.luna.music': '汽水音乐', 'com.xs.fm': '喜马拉雅', 'com.miui.video': '小米视频', 'com.miui.player': '音乐',
  'ctrip.android.view': '携程旅行', 'com.autonavi.minimap': '高德地图', 'com.baidu.BaiduMap': '百度地图', 'com.sdu.didi.psnger': '滴滴出行',
  'com.tongcheng.android': '同程旅行', 'com.pangsheng.pshuochepiao.m': '火车票', 'com.booking': 'Booking.com',
  'com.quark.browser': '夸克', 'com.baidu.searchbox': '百度', 'com.baidu.netdisk': '百度网盘', 'com.alicloud.databox': '阿里云盘',
  'cn.wps.moffice_eng': 'WPS Office', 'com.microsoft.office.officehubrow': 'Microsoft Office', 'com.kingsoft.xiezuo': '金山写作',
  'org.videolan.vlc': 'VLC', 'com.xiaomi.scanner': '小米扫描', 'com.deepseek.chat': 'DeepSeek', 'com.aliyun.tongyi': '通义',
  'com.larus.nova': '豆包', 'com.openai.chatgpt': 'ChatGPT', 'com.anthropic.claude': 'Claude', 'com.google.android.apps.bard': 'Gemini',
  'com.github.android': 'GitHub', 'com.x8bit.bitwarden': 'Bitwarden', 'com.google.android.apps.authenticator2': 'Authenticator',
  'bbc.mobile.news.ww': 'BBC News', 'com.google.android.gm': 'Gmail', 'com.android.chrome': 'Chrome',
  'com.google.android.youtube': 'YouTube', 'com.instagram.android': 'Instagram', 'com.twitter.android': 'X (Twitter)',
  'com.hundsun.winner.pazq': '平安证券', 'com.pingan.paces.ccms': '平安财险', 'com.mi.health': '小米健康',
  'cn.cyberIdentity.certification': '网络身份认证', 'app.personal.linktools': 'LinkTools', 'com.tencent.tmgp.sgame': '王者荣耀',
  'com.tencent.tmgp.osgame': '和平精英', 'com.tencent.tmgp.WePop': '元梦之星', 'com.miHoYo.Yuanshen': '原神',
  'com.xiaomi.gamecenter': '小米游戏中心', 'com.google.android.googlequicksearchbox': 'Google', 'com.booking.english': 'Booking',
  'com.antutu.ABenchMark': '安兔兔', 'com.duolingo': 'Duolingo', 'com.luna.music.nightly': '汽水音乐',
  'com.mfashiongallery.emag': '小米画报', 'com.miui.notes': '笔记', 'com.miui.calculator': '计算器', 'com.miui.weather2': '天气',
  'com.miui.themestore': '主题商店', 'com.miui.cleanmaster': '垃圾清理', 'com.miui.securitymanager': '安全中心',
  'com.miui.screenrecorder': '屏幕录制', 'com.miui.newhome': '内容中心', 'com.miui.compass': '指南针',
  'com.miui.huanji': '换机', 'com.miui.mediaeditor': '视频编辑', 'com.miui.virtualsim': '虚拟SIM', 'com.miui.voiceassistProxy': '小爱语音代理',
  'com.miui.gallery': '相册', 'com.android.calendar': '日历', 'com.android.deskclock': '时钟', 'com.android.email': '邮件',
  'com.android.soundrecorder': '录音机', 'com.android.providers.downloads.ui': '下载管理', 'com.xiaomi.bestphotos': '最佳照片',
  'com.xiaomi.mibrain.speech': '小爱语音', 'com.xiaomi.vipaccount': '小米社区', 'com.xiaomi.tinygame': '小游戏',
  'com.xiaomi.gamecenter.sdk': '游戏SDK', 'com.quark.browser.lite': '夸克极速版', 'com.baidu.input_mi': '百度输入法小米版',
  'com.iflytek.inputmethod.miui': '讯飞输入法小米版', 'cn.wps.moffice_eng.xiaomi.lite': 'WPS Office 小米定制',
  'com.hyper.gallery.plugin': '相册插件', 'com.miui.newmidrive': '米家车载', 'com.ghostlock.app': 'GhostLock',
  'com.alibaba.android.rimet.dingtalk': '钉钉', 'com.sankuai.meituan.dianping': '点评',
};

let devicePkgs = [];
try {
  const { stdout } = await exec('adb', ['-s', process.env.ANDROID_SERIAL || 'd861a4fa', 'shell', 'pm list packages -3']);
  devicePkgs = stdout.split('\n').map((l) => l.replace('package:', '').trim())
    .filter((p) => p && !p.startsWith('dev.tripoder.')).sort();
} catch {}

const importLog = await read('artifacts/qa/import-log.json', {});
const thin = await read(`${campaign}/state-thin.json`, {});
const thick = await read(`${campaign}/state-thick.json`, {});

const name = (pkg) => NAMES[pkg] || '';
const importState = (pkg) => {
  const s = importLog[pkg]?.state;
  if (s === 'SUCCEEDED' || s === 'ALREADY') return 'OK';
  return s || '未导入';
};
const importReason = (pkg) => {
  const raw = String(importLog[pkg]?.error || '');
  if (/NameNotFoundException/.test(raw)) return '设备上该包已不可解析（残留/已被卸载）';
  if (/ABI_OR_LIBRARY_UNSUPPORTED/.test(raw)) return 'APK 原生库 ABI 不受支持';
  if (/SPLIT/.test(raw)) return 'Split APK 不受支持';
  if (/TRANSPORT_TIMEOUT|DeadObject|ENGINE_NOT_CONNECTED/.test(raw)) return '控制通道中断/超时';
  const m = raw.match(/"(?:error)":"([^"]+)"/);
  return (m ? m[1] : raw).replace(/^application_import_device TOOL_ERROR/, '').slice(0, 90) || '-';
};
const LOGIN_LABEL = {
  LOGGED_IN: '已登录', NO_LOGIN_REQUIRED: '无需登录', CREDENTIAL_ONLY: '仅账号/密码',
  LOGIN_UNVERIFIED: '登录未确认', LOGIN_REJECTED: '登录被拒', HOST_LOST: '窗口丢失', '-': '-',
};
const cell = (rec) => {
  if (!rec) return '未测试';
  const login = LOGIN_LABEL[rec.login?.state] || rec.login?.state || '-';
  return `${rec.status} ｜ ${login} ｜ ${rec.screens?.length ?? 0} 屏`;
};
const crashNote = (rec) => {
  const line = (rec?.crash || []).find((l) => /FATAL EXCEPTION|Fatal signal|ANR in/.test(l))
    || (rec?.crash || [])[0] || rec?.error || '';
  return String(line).replace(/^\d\d-\d\d [\d:.]+\s+\w+\/[^:]*:\s*/, '').replace(/\|/g, '/').slice(0, 110);
};

const thinPass = Object.values(thin).filter((r) => String(r.status).startsWith('PASS'));
const thickPass = Object.values(thick).filter((r) => String(r.status).startsWith('PASS'));
const thinLogin = Object.values(thin).filter((r) => r.login?.state === 'LOGGED_IN');
const thickLogin = Object.values(thick).filter((r) => r.login?.state === 'LOGGED_IN');
const thinFail = Object.values(thin).filter((r) => !String(r.status).startsWith('PASS'));
const thickFail = Object.values(thick).filter((r) => !String(r.status).startsWith('PASS'));
const imported = devicePkgs.filter((p) => importState(p) === 'OK');
const failedImport = devicePkgs.filter((p) => importState(p) !== 'OK');

const L = [];
L.push('# Tripoder 真机兼容性与功能测试报告（THIN / THICK）');
L.push('');
L.push(`生成时间：${new Date().toLocaleString('zh-CN')}`);
L.push('');
L.push('## 1. 环境与方法');
L.push('');
L.push('| 项 | 值 |');
L.push('| --- | --- |');
L.push(`| 设备 | M154FF（Android 17 / API 37，arm64） |`);
L.push(`| 引擎 | dev.tripoder.engine 0.1-dev |`);
L.push(`| 控制面 | MCP over HTTPS，空间 default |`);
L.push(`| 测试账号 | 1365****116（短信验证码自动读取设备收件箱） |`);
L.push('| 判定 | 启动成功 + 登录（或无需登录）+ 浏览 ≥4 主要界面 + 进程存活且无 FATAL/ANR = PASS |');
L.push('');
L.push('## 2. 总览');
L.push('');
L.push('| 指标 | 数量 |');
L.push('| --- | --- |');
L.push(`| 设备第三方应用 | ${devicePkgs.length} |`);
L.push(`| 成功导入 tripoder | ${imported.length} |`);
L.push(`| 导入失败 | ${failedImport.length} |`);
L.push(`| THIN 已测 | ${Object.keys(thin).length} （通过 ${thinPass.length}，登录成功 ${thinLogin.length}） |`);
L.push(`| THICK 已测 | ${Object.keys(thick).length} （通过 ${thickPass.length}，登录成功 ${thickLogin.length}） |`);
L.push('');
L.push('## 3. 应用矩阵');
L.push('');
L.push('| # | 应用 | 包名 | 导入 | THIN | THICK | 备注 |');
L.push('|---|------|------|------|------|-------|------|');
devicePkgs.forEach((pkg, i) => {
  const t = thin[pkg], k = thick[pkg];
  const note = importState(pkg) !== 'OK' ? importReason(pkg) : (crashNote(t) || crashNote(k) || '');
  L.push(`| ${i + 1} | ${name(pkg) || '-'} | \`${pkg}\` | ${importState(pkg)} | ${cell(t)} | ${cell(k)} | ${note} |`);
});
L.push('');
L.push('## 4. THIN 失败明细');
L.push('');
L.push('| 应用 | 包名 | 状态 | 登录 | 崩溃/原因 |');
L.push('| --- | --- | --- | --- | --- |');
for (const r of thinFail.sort((a, b) => a.package.localeCompare(b.package))) {
  L.push(`| ${name(r.package) || '-'} | \`${r.package}\` | ${r.status} | ${LOGIN_LABEL[r.login?.state] || r.login?.state || '-'} | ${crashNote(r)} |`);
}
L.push('');
L.push('## 5. THICK 失败明细');
L.push('');
L.push('| 应用 | 包名 | 状态 | 登录 | 崩溃/原因 |');
L.push('| --- | --- | --- | --- | --- |');
for (const r of thickFail.sort((a, b) => a.package.localeCompare(b.package))) {
  L.push(`| ${name(r.package) || '-'} | \`${r.package}\` | ${r.status} | ${LOGIN_LABEL[r.login?.state] || r.login?.state || '-'} | ${crashNote(r)} |`);
}
L.push('');
L.push('## 6. 导入失败明细');
L.push('');
L.push('| 应用 | 包名 | 原因 |');
L.push('| --- | --- | --- |');
for (const pkg of failedImport) L.push(`| ${name(pkg) || '-'} | \`${pkg}\` | ${importReason(pkg)} |`);
L.push('');
L.push('## 7. 证据位置');
L.push('');
L.push(`- 逐应用结果：\`${campaign}/thin/<package>/result.json\`、\`${campaign}/thick/<package>/result.json\``);
L.push(`- 截图：同名目录下 PNG；崩溃日志：result.json 的 \`crash\` 字段`);
L.push(`- 导入日志：\`artifacts/qa/import-log.json\``);
await writeFile(outFile, L.join('\n') + '\n');
console.log('wrote', outFile, 'apps', devicePkgs.length, 'thin', Object.keys(thin).length, 'thick', Object.keys(thick).length);
