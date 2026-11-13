# 第 8 批附属：小红书「当前网络存在风险」判定的定性（v76/v77）

结论先行：这条 toast **不是网络环境问题**，而是小红书**服务端下发的风控文案**。本地 A/B 已把「出口 IP / VPN」这条路排除，APK 扫描进一步证明该文案不由客户端产生。

## 1. 现象与触发点

同一账号 `15323738553`，容器内小红书手机号 + 短信验证码登录：

| 步骤 | 结果 |
| --- | --- |
| 请求验证码（「验证并登录」/「重新发送」） | 稳定成功，5 秒内收到短信 |
| 提交验证码（「登录」） | 页面无跳转，底部浮出 toast「当前网络存在风险，请切换可靠网络后重试」 |

发码接口不做风控判定、提交接口做判定 —— 两次链路分离（详见 `batch8-xhs-login-20261113.md` 的 `xhs-sms-send-vs-submit-split`）。

## 2. A/B：把「网络出口」当自变量，结果不变（负结果）

| 轮次 | 设备网络状态 | 提交的验证码 | 结果 |
| --- | --- | --- | --- |
| A1 | 常驻 VPN 隧道 `tun0 172.19.0.1/30`，默认网络 209 | 167769（收码后 6 秒提交） | 「当前网络存在风险」 |
| A2 | `am force-stop app.personal.linktools` → `tun0` 消失，默认网络仍 209 | 101452 | 同上 |
| A3 | `svc wifi disable` + `svc data enable` → 只剩 `rmnet_data1 10.49.40.5`，默认网络 225 | 131676 | 同上 |

三轮换的是三条不同的出口路径（隧道 / WiFi / 移动数据），文案一字不变。

**用户侧对照**：宿主原生小红书在**同一台设备、同一条网络**下可正常登录运行。同一 IP、同一账号、同一设备，只有客户端不同 → 自变量不是网络。

⇒ 假设「VPN 出口 IP 被小红书判风险」被推翻。

## 3. 定性：文案来自服务端，不是本地字符串

把 `com.xingin.xhs` 的 `base.apk`（182,655,636 B）拉到本地，对全部 24 个 dex + `resources.arsc` + 全部 25,603 个条目做字节级检索（UTF-8 与 UTF-16LE 双编码）：

| 检索串 | 命中 |
| --- | --- |
| `当前网络存在风险` | 无 |
| `请切换可靠网络` | 无 |
| `可靠网络` | 无 |
| `网络存在风险` | `resources.arsc`（1 处，见下） |

`resources.arsc` 里唯一的命中是**另一句**本地文案：

```
检测到您当前的网络存在风险
```

它与我们看到的 toast（`当前网络存在风险，请切换可靠网络后重试`）措辞不同，且「切换可靠网络」这半句在 APK 中完全不存在。

⇒ 客户端本地没有这句文案，只能由服务端下发后渲染。这与抖音那条链路的形态一致（服务端判定 + 客户端只做 copywriting 渲染与回执，见 `docs/douyin-risk-control-strategy-analysis-20261112.md` 的三段式 block 协议）。

## 4. 服务端吃的是什么：客户端自带完整性引擎

小红书 APK 携带 157 个 native 库，其中与「运行时完整性/反注入」直接相关的一族（名字即用途）：

| 库 | 作用 |
| --- | --- |
| `libbytehook.so` / `libshadowhook.so` / `libcapahook.so` / `libopen_hook.so` | 内联 hook 框架（ByteDance 系） |
| `libloghook.so` / `libloghook_api30.so` | 专检测日志 hook |
| `libxhs_unwind.so` | 自定义栈回溯（可发现非应用栈帧） |
| `libSystemHealer.so` | 检出篡改后自愈 |
| `libdexvmp.so` / `libentryexpro.so` | dex 虚拟化 / 方法入口保护 |
| `libsecurebase.so` / `libxEF4.so` / `libsliver.so` / `libscalpel.so` | 安全基线 |
| `libmsaoaidsec.so` / `libmsaoaidsdk` 同族 | OAID 设备标识校验 |

即：客户端**本地计算环境完整性判定**并上报，服务端据此决定是否放行登录提交。容器与原生在同一设备上唯一的差别族是运行期身份事实（客体以宿主 uid 运行而自称 `com.xingin.xhs`，见 `guest-runs-as-host-uid` / `h14-c1-confirmed`）与进程内 hook 痕迹（`dev.tripoder.*` 栈帧）。

这两项都**不可由容器侧修复**：uid 是安装期属性、Binder 调用 uid 由内核盖章（见 `uid-install-time-attribute`）。

## 5. 当前设备状态（本轮结束时）

为排除网络变量所做的改动已全部回滚：`wifi_on=1`、`mobile_data=0`、`tun0` 不再存在、默认网络为 WiFi。

但登录闭环本身**无法继续**：为恢复输入焦点而做的灭屏/亮屏（`input keyevent 26` ×2）把设备重新锁上，现场读数：

```
mWakefulness=Awake
isKeyguardShowing=true
FocusedWindows: displayId=0, name='80103a9 NotificationShade'
```

这与 `keyguard-owns-window-focus` 是同一状态：锁屏持有窗口焦点时 `input tap` / `input text` / `keyevent` 全部被丢弃。此前「去登录」「我」两个 tab 点击无响应、BACK 也无效，现场读数为 `FocusedApplications` 有客体 ActivityRecord 而 `FocusedWindows` 为空 —— 即焦点两栏不一致，点击没有送达应用，**不是小红书忽略了点击**。

## 6. 边界

- 本文件只定性「这条 toast 属于谁」。它不主张小红书所有风控都不可绕过，也不主张密码登录会撞同一条判定（未测：无账号密码）。
- 服务端判定所用的完整特征集未逆出：本轮只证明「文案来自服务端」＋「客户端存在本地完整性引擎」，没有抓到上报报文。要拿特征集必须做 TLS 中间人，代价与抖音那条链路相当。
- 「原生可登录」是用户提供的对照事实，本轮未独立复现原生登录。
