# charen.shop 安全风险评估报告

> ⚠️ **本报告中关于 put.js 的结论已被后续深度分析取代，请以 [put.js-深度分析报告.md](put.js-深度分析报告.md) 为准。**
> 关键更正：(1) put.js 未读取任何表单/输入内容，"可拿到身份证号"一说证伪；(2) 该脚本实为 **iOS 漏洞利用投递器**，会按 iOS 版本静默下发 coruna / silkpath / darksword / ghostwave 利用链（已实证 `/rce_worker_18.6.js` 748,678 B）。总体风险由"信息泄露"上调为"设备可能被控制"。

- 目标：`https://charen.shop/`（品牌名「闪索 / FLASH SEEK」）
- 评估时间：2026-10-22
- 评估方式：**被动外部观察**（DNS / WHOIS / 证书 / 响应头 / 页面与前端代码 / 公开可访问资源的少量正常 GET），不做任何越权、爆破、注入、批量取数
- 证据留存：`charen_recon/` 目录（`hdr.txt`、`home.html`、`js_app.js`、`js_captcha.js`、`put.js`、`put_headers.txt`、`dir_*.html` 等）

---

## 一、结论速览

**总体判断：这不是一个"正常商业网站被入侵"的问题，而是站点本身的性质就构成对访问者的高风险。**

| 维度 | 判断 | 依据 |
|---|---|---|
| 业务性质 | ⛔ 疑似「社工库 / 开盒」型个人信息查询站 | 首页唯一功能是输入 **手机号 / 姓名 / 身份证号 / QQ** 查询，返回分层数据（`level_0_1_results`、`total_count` 数百条） |
| 对我（访问者）的风险 | 🔴 高 | 输入的身份证号等敏感信息对站方完全可控；页面还加载一个 29KB 全混淆、来源无关的第三方脚本 |
| 站点自身技术安全 | 🟠 中—高 | 安全响应头全缺、目录列举开启、Python 简易 HTTP 服务直接对外、nginx 版本陈旧、无 DMARC |
| 法律合规 | ⛔ 高 | 全站 `noindex` 主动隐藏、无备案信息、无隐私政策；在中国大陆，未经授权收集/提供/查询公民个人信息涉嫌违反《个人信息保护法》《刑法》第 253 条之一 |

**一句话建议：不要在该站输入任何真实身份信息（包括你自己的），不要把它的查询结果当作事实使用，也不要在有重要账号的浏览器里打开它。**

---

## 二、这个站点是做什么的（业务性质判定）

首页 HTML 总共 1916 字节，只有一个搜索框，占位符原文：

```
请输入手机号/姓名/身份证号/qq进行查询
```

前端代码（`js/app.js`）进一步说明了它的运作方式：

- 按输入自动判定类型：`^1\d{10}$` → 手机号；`^\d{17}[\dXx]$` → 身份证号；`^[\u4e00-\u9fff·•]{2,10}$` → 姓名；`^[1-9]\d{4,10}$` → QQ 号；
- 通过滑动验证后请求 `api/search?q=<关键字>`，响应结构为 `{code, message, data:{ total_count, level_0_1_results:[{姓名, 性别, 手机号码, 籍贯, 扩展1…}] }}`；
- 结果以卡片流「下拉加载更多」方式渲染，并提供「导出 TXT」按钮，即**批量导出**；
- 站点自述「真实接口地址和 key 在服务器 server_config.json，不会出现在 F12」——说明它只是一个**代理层**，真实数据来自上游第三方数据接口（`js/config.js` 注释）。

`level_0_1_results`（"等级 0-1 结果"）这一命名，配合「按姓名/身份证号反查多字段」，是典型的多层级人员数据聚合（业内俗称社工库/查档）。同时：

- `<meta name="robots" content="noindex, nofollow, noarchive, nosnippet, noimageindex">`——**主动拒绝被搜索引擎收录**；
- `robots.txt` 404，`urlscan.io` 无任何历史扫描记录（30 天窗口内 `total: 0`），说明刻意低可见度运营；
- 证书为 Certum **DV**（仅域名验证），无 CAA 记录，页面无备案号、无隐私政策、无用户协议、无任何运营主体信息。

> 结论：站点没有任何"合规经营"的痕迹，而业务内容指向非法个人信息交易/查询的高风险类别。对访问者而言，"进去查一下"这一行为本身就可能同时承担**个人信息被反向收集**与**法律风险**两重代价。

---

## 三、风险清单（按严重度）

### 🔴 高风险

#### H1. 页面加载来源不明、无法审计的第三方混淆脚本（供应链风险）

`home.html` 末尾：

```html
<script src="https://abc.007tx1a1lb7rr16xwm.site/rejs/put.js?channelCode=7e83e3b23df152b6925485f478f3c5a6"></script>
```

观察到的特征：

| 观察项 | 结果 |
|---|---|
| 脚本体积/形态 | 29,493 字节，全量混淆（obfuscator.io 风格：字符串数组切分 + 十六进制变量名 664 个 + base64/RC4 解码 + 自防御死循环）——常规反混淆工具与沙箱跑不穿，**无法静态判定其行为** |
| 域名关系 | `007tx1a1lb7rr16xwm.site` 与 charen.shop 无任何品牌/证书/注册关联，随机字符串二级域名 |
| 托管 | Cloudflare NS（`razvan/opal.ns.cloudflare.com`）+ Cloudflare 代理，响应头 `server: cloudflare` |
| 该域名的其他内容 | 根路径 404（nginx），实测只对外提供 `/rejs/put.js` 这一个文件，`cache-control: no-transform` |
| 埋点参数 | `channelCode=7e83e3b23df152b6925485f478f3c5a6` —— 典型「流量渠道分成」计费参数 |
| 声誉数据 | urlscan.io 无记录，域名 2026-10-09 才产出该文件（`last-modified`） |

**影响**：该脚本运行在 charen.shop 的源（origin）上，拥有完整的 DOM 读取、Cookie/Storage 读写、`fetch` 任意域名、任意跳转能力。一个能在你输入身份证号时读到输入框内容、并把它外发到自有服务器的脚本，与"查档站"结合是最坏的组合。这类"混淆 + 随机域名 + channelCode 分成"的投放方式，在业内多用于**导流劫持、暗链注入、赌博/色情落地页跳转、广告联盟回传**。

**建议**：不要在该站输入任何真实信息；如需确认其行为，应在隔离浏览器 + 抓包环境下打开（本次受策略限制未启动浏览器，无法给出其运行期网络行为的实证）。

#### H2. 查询关键字以 GET + 明文 URL 参数提交，敏感信息必然落日志

```js
url.searchParams.set("q", keyword);          // 关键字直接进 URL
url.searchParams.set("ct", captchaToken);    // 验证码 token 也进 URL
```

**影响**：身份证号/手机号会同时出现在——浏览器历史记录、任何 Referer 外发、nginx 与后端 access log、CDN/代理日志、书签同步、以及本机共享用户下。**即使站点"没有恶意"，这些日志本身就是一批高危数据资产**（一旦被拖库或日志泄漏，等于二次泄露你查询的人）。

**建议**：这类查询必须 POST + body，且服务端不得记录入参；作为访问者，你无法改变它，只能不去用。

#### H3. 业务本身的法律与合规风险

- 提供/查询他人身份证号、手机号、QQ 的关联信息，在中国大陆属于《个人信息保护法》规制范围，情节严重的落入《刑法》第 253 条之一「侵犯公民个人信息罪」；
- 站点无运营主体、无备案、无隐私政策、主动 `noindex`，属于规避监管的典型特征；
- 「导出 TXT」+「total_count 数百条」的批量能力，意味着它面向的是**批量取数**需求，而非个人自查。

**影响**：使用者可能因"购买/获取公民个人信息"承担责任；同时你的查询行为会被站方完整记录，成为反向要价或诈骗的素材。

---

### 🟠 中风险（站点自身技术面缺陷）

#### M1. 安全响应头几乎全缺

实际返回的全部响应头（`https://charen.shop/`）：

```
HTTP/1.1 200 OK
Server: nginx/1.20.1
Date: ...
Content-Type: text/html
Content-Length: 1916
Connection: keep-alive
Last-Modified: Tue, 22 Sep 2026 16:37:24 GMT
Cache-Control: no-cache, must-revalidate
```

缺失项与影响：

| 缺失头 | 影响 |
|---|---|
| `Content-Security-Policy` | 无任何脚本来源白名单。**这正是 H1 能成立的前提**——外域混淆脚本可无约束执行 |
| `Strict-Transport-Security` | 已有 301 跳 HTTPS，但无 HSTS，首次访问仍可被 SSL 剥离/中间人 |
| `X-Frame-Options` / `frame-ancestors` | 可被任意站点 iframe 嵌套 → 点击劫持（诱导用户在"查档站"上点击/输入） |
| `X-Content-Type-Options: nosniff` | 存在 MIME 嗅探风险（讽刺的是它加载的那个第三方脚本反倒带了 nosniff） |
| `Referrer-Policy` | 配合 H2，URL 中的查询关键字会随 Referer 外泄 |
| `Permissions-Policy` | 未限制摄像头/麦克风/地理位置等能力 |

#### M2. 目录列举（Directory listing）开启

```
GET /assets/ → 200  Directory listing for /assets/
GET /js/     → 200  Directory listing for /js/
GET /css/    → 200  Directory listing for /css/
```

**影响**：任何静态目录可直接列出文件清单并逐个下载。当前只列出 `logo.png`、`app.js`、`captcha.js`、`config.js`、`style.css`，但若将来出现上传/备份/导出目录，等同**任意文件可枚举下载**。已确认 `server_config.json`（前端注释提到的密钥文件）当前**未被公开**（404），`/api/search` 无参数返回 **403**（验证码门槛生效）——这两点算是站点做对的地方。

#### M3. 后端是 Python 简易 HTTP 服务直接对外

任意不存在路径返回的是 Python `http.server` 的默认错误页（`Error code 404. Message: File not found.`，目录列举页面也是其 `Directory listing for ...` 模板），前端代码里还留着 `请确认 server.py 已启动` 的开发期提示。而 `Server: nginx/1.20.1` 说明 nginx 在前面终止 TLS 并反代。

**影响**：
- Python 标准库 `http.server` 官方明确声明"仅用于开发测试"，**单线程/弱并发、无超时防护、无日志轮转**——一次小规模并发即可拖垮，属可用性风险；
- 生产环境残留开发期服务框架，通常意味着整体缺乏运维加固（无 WAF、无速率限制的迹象）；
- 精确版本号 `nginx/1.20.1`（2021 年 5 月发布，1.20 主线早已停止维护，其后 1.20.2 修复了 `CVE-2021-23017` DNS resolver 漏洞）被直接暴露，便于攻击者做针对性利用。

#### M4. 邮件域认证缺失，可被冒名发钓鱼邮件

```
charen.shop. TXT      → "v=spf1 include:spf.efwd.registrar-servers.com ~all"
_dmarc.charen.shop TXT → （无记录）
```

**影响**：无 DMARC，SPF 仅 `~all`（软失败）。第三方可以伪造成 `service@charen.shop` 给"该站用户"发钓鱼/勒索邮件，而收件方服务器基本无法拒收。结合本任务场景（一个收集身份证号的站点），**冒名钓鱼的现实威胁很高**。

---

### 🟡 低风险 / 观察项

| 编号 | 观察 | 说明 |
|---|---|---|
| L1 | 无 CAA 记录 | 任意 CA 均可为该域名签发证书，便于中间人/钓鱼（DV 证书本身也只做了域名验证） |
| L2 | 自研滑动验证码 | `api/captcha/challenge` + `api/captcha/verify`，前端 localStorage 存 token，token 同时作为 `X-Captcha-Token` 头和 `ct` URL 参数传输；自研滑块一般可被自动化绕过，威胁模型上只能防"手动连点"，挡不住批量取数 |
| L3 | 存在额外子域 `buyerhou.charen.shop` | crt.sh 证书透明日志中可见，与主站同 IP（192.163.172.21），命名像"买家/代理"面板。**本次未做任何访问**（无授权），仅作为资产暴露面记录 |
| L4 | IP 无 PTR、无 CAA、域名 2016 年注册但站点 `Last-Modified` 为 2026-09-22 | 老域名 + 新上线页面，典型的"养域名再复用"模式 |
| L5 | 前端渲染使用 `textContent` 而非 `innerHTML` 拼接数据 | 未发现明显 DOM XSS（`renderCard` 用 `textContent`，`innerHTML` 仅用于固定模板字符串），这一点做得比预期好 |
| L6 | `Cache-Control: no-cache, must-revalidate` | 合理，避免结果被代理缓存（对这类敏感查询是正确选择） |
| L7 | TLS 配置 | 仅启用 TLS 1.2 / 1.3（`ECDHE-RSA-AES256-GCM-SHA384`、`TLS_AES_256_GCM_SHA384`），已禁用 TLS 1.0/1.1，配置正常；HTTP→HTTPS 301 存在 |

---

## 四、给你的操作建议

**如果你只是想知道"点进去安不安全"：**

1. **不要输入任何真实信息**——包括你自己的身份证号、手机号、姓名。输入框里的内容会被一个无法审计的第三方脚本同源读取。
2. **不要登录/注册/上传/付款**，不要点它弹出的任何"下载客户端""加入群""客服"。
3. 如果已经查过：把你查过的关键词视为**已泄露**；不要复用在该站用过的任何密码；清掉该站点的站点数据（Cookie / localStorage / 浏览历史）。
4. 若你是"被查"的一方（发现自己的信息出现在这类站点）：不要私下联系站方；保留截图与 URL 证据，向 **12321（网络不良与垃圾信息举报）**、**属地网警/公安网安部门**或 **国家网信办举报中心（12377）** 举报，涉及身份信息被冒用可同时报案。
5. 想验证站点行为又不想暴露：用一次性浏览器配置文件/虚拟机 + 抓包，非必要不开（本次因策略限制未启用浏览器跑动态分析）。

**如果你是站方（或有授权的运维方）——加固优先级：**

1. 立刻移除 `abc.007tx1a1lb7rr16xwm.site/rejs/put.js` 这类外域脚本；若必须保留统计，改为自域代理 + 固定版本 + SRI 校验；
2. 加 CSP（`default-src 'self'`）、HSTS（`max-age=31536000; includeSubDomains`）、`X-Frame-Options: DENY`、`X-Content-Type-Options: nosniff`、`Referrer-Policy: no-referrer`；
3. 查询改为 `POST` + body，URL 里不出现关键字与 token；服务端 access log 关闭 query 记录，日志脱敏并设短留存；
4. 关闭 `http.server` 的目录列举（换 gunicorn/uwsgi + 显式静态目录白名单），移除 `server.py` 生产部署；
5. 升级 nginx（≥1.26 主线），`server_tokens off`；
6. 发布 DMARC（`p=reject`）、收紧 SPF 为 `-all`、补 CAA；
7. 若要合法运营：需备案、隐私政策、数据来源合法性证明与最小化留存——但就"手机号/身份证号反查"这一业务形态而言，合规路径基本不存在。

---

## 五、方法与边界（重要）

- 本次为**未授权的第三方站点被动评估**：所有结论来自 DNS/WHOIS/CT 日志、TLS 握手、HTTP 响应头、公开页面与公开 JS 文件，以及 4 次无参数的常规 GET（`/robots.txt`、`/server_config.json`、`/api/search`、三个目录）。
- **未做**：SQL 注入/越权/爆破/目录字典扫描/上传测试/接口批量取数；未提交任何身份证号或手机号；未访问 `buyerhou.charen.shop`；未对该站上游数据接口做任何请求。
- **未能完成**：`put.js` 的动态行为（运行时请求外发/跳转）因混淆自防御 + 本机策略禁止启动浏览器未能取证，仅能判定为"不可审计的高风险外域脚本"。
- 因此本报告**不能**断言"站点已被入侵"或"存在可直接利用的漏洞"；能确定的是：**业务性质非法/高风险、供应链不可信、敏感数据以明文 GET 传输、安全基线与运维卫生均不达标**。就访问者视角，这些已足以得出"不要使用"的结论。

---

## 附录：关键证据原文

**A. 首页唯一功能入口**

```html
<form class="search" id="searchForm">
  <input id="keyword" type="search" placeholder="请输入手机号/姓名/身份证号/qq进行查询" ...>
  <button class="search-btn" id="searchBtn" type="submit">搜索</button>
</form>
...
<script src="https://abc.007tx1a1lb7rr16xwm.site/rejs/put.js?channelCode=7e83e3b23df152b6925485f478f3c5a6"></script>
```

**B. `js/config.js`（完整）**

```js
window.FLASH_SEEK = {
  // 前端只请求本站代理（相对路径，兼容 /seek/）
  // 真实接口地址和 key 在服务器 server_config.json，不会出现在 F12
  searchUrl: "api/search",
  logUrl: "api/log",
  useMock: false,
};
```

**C. 响应头与目录列举**

```
$ curl -I https://charen.shop/
HTTP/1.1 200 OK
Server: nginx/1.20.1
Content-Type: text/html
Content-Length: 1916
Last-Modified: Tue, 22 Sep 2026 16:37:24 GMT
Cache-Control: no-cache, must-revalidate

$ curl -I http://charen.shop/
HTTP/1.1 301 Moved Permanently
Location: https://charen.shop/

$ curl https://charen.shop/assets/
<title>Directory listing for /assets/</title>      ← Python http.server 模板

$ curl https://charen.shop/server_config.json
404  Message: Not Found.                            ← 未被公开（良好）

$ curl https://charen.shop/api/search
403                                                ← 验证码门槛生效（良好）
```

**D. DNS / 证书**

```
A       charen.shop → 192.163.172.21   (无 PTR)
NS      dns1/dns2.registrar-servers.com (Namecheap)
MX      eforward1-5.registrar-servers.com
TXT     "v=spf1 include:spf.efwd.registrar-servers.com ~all"
_dmarc  无记录
CAA     无记录
created 2016-05-05
CT 日志 charen.shop / www.charen.shop / buyerhou.charen.shop
证书    CN=charen.shop, Certum DV TLS G2 R39 CA, 2026-09-17 ~ 2027-04-04
TLS     TLS1.2 ECDHE-RSA-AES256-GCM-SHA384 / TLS1.3 TLS_AES_256_GCM_SHA384（TLS1.0/1.1 已禁用）
```
