import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Deterministic vector diagram; reference-image styling, current product modules.
const output = path.dirname(fileURLToPath(import.meta.url));
const width = 1800;
const height = 2590;
const p = {
  border: '#526396', cyan: '#08b4cb', cyanTile: '#1595aa',
  control: '#1596b6', controlTile: '#177d98', green: '#8cc34b',
  greenPanel: '#669e32', greenTile: '#51852d', purple: '#a29dd7',
  purplePanel: '#a77bd4', purpleTile: '#8862b1', gray: '#bfbfbf',
  divider: '#cdcdcd', white: '#ffffff', ink: '#3f4c63', note: '#606975',
};
const escape = (value) => String(value).replace(/[&<>"']/g, (c) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;',
}[c]));
const svg = [
  `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-labelledby="title desc">`,
  '<title id="title">Tripoder 技术架构分层图</title>',
  '<desc id="desc">从上到下为工作台与外部 Agent、应用运行空间、Runtime Java 适配与控制权威、Android Framework、Native 路径与 Hook 实现、Android Native 和 Linux Kernel。工作台与引擎采用不同 UID，客体与引擎共享 UID；图中组件表示已有实现范围，不代表通用兼容性。</desc>',
  '<defs><filter id="shadow" x="-5%" y="-10%" width="112%" height="130%"><feDropShadow dx="1" dy="4" stdDeviation="2.5" flood-color="#000000" flood-opacity="0.25"/></filter></defs>',
  '<style>text{font-family:Arial,"Hiragino Sans GB","PingFang SC","Heiti SC",sans-serif} .title{font-weight:500}</style>',
  `<rect width="${width}" height="${height}" fill="${p.white}"/>`,
];
function rect(x, y, w, h, fill, stroke = p.border, shadow = false) {
  svg.push(`<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="${fill}" stroke="${stroke}" stroke-width="1.8"${shadow ? ' filter="url(#shadow)"' : ''}/>`);
}
function text(x, y, label, size = 30, fill = p.white, anchor = 'middle') {
  svg.push(`<text x="${x}" y="${y}" font-size="${size}" fill="${fill}" text-anchor="${anchor}" dominant-baseline="middle">${escape(label)}</text>`);
}
function tile(x, y, w, h, labels, fill, size = 28, shadow = true) {
  rect(x, y, w, h, fill, shadow ? fill : p.border, shadow);
  const lines = Array.isArray(labels) ? labels : [labels];
  const leading = size * 1.32;
  lines.forEach((line, i) => text(x + w / 2, y + h / 2 + (i - (lines.length - 1) / 2) * leading, line, size));
}
function grid(x, y, w, h, cols, labels, fill, size = 27, gapX = 24, gapY = 22) {
  const rows = Math.ceil(labels.length / cols);
  const cw = (w - (cols - 1) * gapX) / cols;
  const ch = (h - (rows - 1) * gapY) / rows;
  labels.forEach((label, i) => tile(x + (i % cols) * (cw + gapX), y + Math.floor(i / cols) * (ch + gapY), cw, ch, label, fill, size));
}
function divider(y, above, below) {
  svg.push(`<path d="M 20 ${y} H 1780" stroke="${p.divider}" stroke-width="6" stroke-dasharray="18 10"/>`);
  if (above) text(1760, y - 20, above, 22, '#999999', 'end');
  if (below) text(1760, y + 25, below, 22, '#999999', 'end');
}

text(60, 43, 'Tripoder 技术架构分层图', 37, p.ink, 'start');
text(1740, 43, '当前源码结构 · 2026-10-15', 23, p.note, 'end');

// Product/control is a separate APK and UID; the MCP client runs on the desktop.
rect(60, 90, 1680, 170, p.control);
text(900, 126, 'Tripoder Workbench · 工作台与外部控制', 34);
grid(100, 157, 1600, 64, 5, ['应用 / 项目 / 空间', '脚本编辑 / 运行记录', '配对 / 源码审批', 'Binder Control API', '外部 Agent / MCP'], p.controlTile, 27, 24, 0);
text(900, 242, '工作台与引擎分离 UID；外部 MCP 经 ADB 转发签名帧进入工作台', 21);

rect(60, 280, 1680, 140, p.cyan);
text(900, 315, 'Tripoder Space · 应用运行空间', 35);
grid(100, 341, 1600, 55, 4, ['自研验证应用', '微信 · 适配中', '王者荣耀 · 适配中', '其他 APK · 待验证'], p.cyanTile, 28, 36, 0);

divider(460, 'APP', 'Framework');

rect(60, 510, 1680, 755, p.green);
text(900, 550, 'Tripoder Runtime · 应用虚拟化框架', 36);

tile(100, 595, 250, 83, ['TypeScript SDK', 'Plan / Params'], p.greenPanel, 27, false);
tile(100, 703, 250, 83, ['Evidence / AST', '证据与草稿检查'], p.greenPanel, 26, false);
tile(100, 811, 250, 83, ['Java Hook', '观察 / 激活 / 停用'], p.greenPanel, 26, false);

rect(380, 585, 1320, 310, p.greenPanel);
text(1040, 622, 'Guest Runtime / APP Hook · 客体进程内适配', 31);
grid(405, 658, 1270, 210, 4, [
  'Application / Loader', 'Activity / Task', 'Service / Binder', 'Provider',
  'Receiver / Broadcast', 'Package / Resources', 'Storage / Preferences', 'Connectivity / Wi-Fi',
  'Telephony / Audio', 'Battery / Power', 'Notifications（受限）', '系统能力门禁',
], p.greenTile, 25, 22, 21);

rect(100, 925, 1600, 305, p.greenPanel);
text(900, 963, 'Runtime Broker · :authority 控制与状态权威', 33);
grid(130, 999, 1540, 128, 6, [
  'Space / Catalog', 'APK 安装 / 验签', 'Project Binding', 'Process Session', 'Activity Ledger', 'Service Ledger',
  'Provider Broker', 'Broadcast Broker', 'Operation / Recovery', 'Control Lease', 'Agent Session', 'Hook Control',
], p.greenTile, 23, 20, 22);
tile(130, 1153, 1540, 50, 'RuntimeStore / SQLite：代次、操作、组件账本     |     ApkStore：不可变 APK 制品', p.greenTile, 25, false);

rect(60, 1300, 1680, 120, p.gray);
text(900, 1340, 'Android Framework', 37);
text(900, 1385, 'Activity / Service / Provider / Receiver · PackageManager · Resources · 系统服务', 27);

divider(1460, '', 'Native');

rect(60, 1510, 1680, 650, p.purple);
text(900, 1555, 'Tripoder Native · 原生适配与用户 Hook', 36);

const panels = [
  { x: 105, title: 'Path Policy · 路径策略', labels: ['空间数据路径映射', 'APK 制品只读', '目录 FD 锚定', '越界 / 符号链接拒绝'] },
  { x: 650, title: 'Native I/O · libc 入口', labels: ['openat / stat', 'readlink / access', 'mkdir / rename / unlink', '真实 FD / errno 语义'] },
  { x: 1195, title: 'Native Hook · 用户脚本', labels: ['ELF / Build ID 校验', '动态模块代次跟踪', '导出符号 / ABI 绑定', '替换 / 停用 / 错误终止'] },
];
for (const panel of panels) {
  rect(panel.x, 1590, 500, 460, p.purplePanel);
  text(panel.x + 250, 1627, panel.title, 29);
  panel.labels.forEach((label, i) => tile(panel.x + 42, 1664 + i * 85, 416, 60, label, p.purpleTile, 28));
  text(panel.x + 250, 2027, panel.x === 105 ? 'path_policy.c' : panel.x === 650 ? 'io_adapter.c' : 'hook_native.ts / hook_modules.ts', 22);
}
tile(105, 2075, 1590, 58, 'Frida Gadget / Gum · QuickJS · frida-java-bridge · libtripoderio', p.purplePanel, 31, false);

rect(60, 2190, 1680, 110, p.gray);
text(900, 2228, 'Android Native', 37);
text(900, 2271, 'ART / JNI · Bionic libc · Linker · Binder · 图形与输入原生库', 28);

divider(2338, '', 'Kernel');
rect(60, 2385, 1680, 100, p.gray);
text(900, 2435, 'Android / Linux Kernel', 38);

text(60, 2523, '边界：工作台 ≠ 引擎 UID；客体与引擎共享 UID，不提供恶意 Native 代码强隔离。', 23, p.note, 'start');
text(60, 2560, '模块代表当前实现范围，非完整兼容性；旧运行器与 QEMU 不进入产品执行链。', 23, p.note, 'start');

svg.push('</svg>');
fs.writeFileSync(path.join(output, 'tripoder-layered-architecture.svg'), svg.join('\n') + '\n');
console.log(path.join(output, 'tripoder-layered-architecture.svg'));
