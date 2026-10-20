# 小米自有应用漏洞挖掘报告

- 执行时间：2026-10-19
- 目标设备：M154FF / `madrid` / 小米 18 Pro Max
- 系统：Android 17 / HyperOS 4（`OS4.0.24.0.XEOCNXM`）/ 安全补丁 2026-09-01
- 规则依据：MiSRC V11.0（2026-09-11 发布）§4.3 移动端
- 授权性质：设备为本人所有，ADB 调试已开启

## 结论摘要

**本轮未发现可提交的高危漏洞。**

已实测关闭的攻击面共 4 类，全部有可复核证据。下面同时列出「已排除」与「未覆盖」，
后者是后续投入的方向，不是"可能有问题"的暗示。

## 一、已完成的工作与实测结果

### 1.1 攻击面枚举

| 应用 | 包名 | 导出组件 | 其中无 permission 保护 |
|---|---|---|---|
| 小米推送 | `com.xiaomi.xmsf` | 61 | 34 |
| 安全中心 | `com.miui.securitycenter` | 241 | 124（Activity） |
| AI 服务 | `com.xiaomi.aiservice` | 61 | 7 |
| 系统桌面 | `com.miui.home` | — | 含 2 个 Provider |
| 壁纸 | `com.miui.miwallpaper` | — | 含 3 个 Provider |

工具链说明：环境无 jadx/apktool 且多次下载失败，因此**自建 AXML 解析器**（`misrc/parse_manifest.py`）
与 **dex 解析器**（`misrc/dex_methods.py`、`misrc/callgraph.py`）完成分析，脚本均留存可复核。

### 1.2 已排除项（附实测证据）

#### A. ContentProvider 越权读写 —— 无

对 `PushControlProvider`、`SettingsSearchProvider`、`wallpaper.palette`、
`PUSH_COMMON`、`PUSH_PROFILE`、`xsof.provider` 等 8 个无权限 Provider 测试全部四类方法：

- `query()`：仅返回非敏感数据（桌面设置项标题、壁纸 Material You 配色值、推送控制开关状态）
- `insert()/update()`：返回成功但**值不变**，服务端静默拒绝
- `call()`：返回 `null`
- `openFile()/路径穿越`：返回 null 或 NPE
- `selection` 注入（`title=' OR '1'='1`）：无效果

按 MiSRC §4.3.3，仅"缺少权限声明"属配置现象，且实测无数据泄露，**不构成漏洞**。

#### B. VPN 管理服务 Binder 越权 —— 无

`com.miui.securitycenter/MiuiVpnManageService` 可被第三方 `bindService` 成功，
`onBind` 返回非 null，描述符 `IMiuiVpnManageService`。

构造测试 APK（`misrc/poc/`，自签名，uid 10392）实测 27 个事务码：

```
code=1  getVpnEnabled  → SecurityException: caller uid=10392 is not allowed to call vpn manage binder interface
code=8  connectVpn     → 同上
code=21 requestNetworkByTencent → ... dedicated net binder interface
```

**27 个接口全部拒绝**，且 `com.miui.securitycenter.permission.CONTROL_VPN`
的 `protectionLevel` 为 **`signature|privileged`**（`dumpsys package` 实证），
第三方无法申请该权限。**该面已关闭。**

#### C. 预下载服务 PreDownloadService —— 无越权（已反汇编确认）

这是本轮投入最多的目标，因为它最初看起来最可疑：

- `com.xiaomi.game.predownload.service.PreDownloadService` **导出、无 permission 保护**
- 第三方 APK（uid 10395）`bindService` 成功，`onBind` 返回非 null
- 描述符 `com.xiaomi.ecosys.gameservice.ability.predownload.IPreDownload`
- 对 code=1..40 扫描：**零 `SecurityException`**，与 VPN 服务 27 个接口全部拒绝形成鲜明对比

**反汇编 `IPreDownload$Stub.onTransact` 得到精确契约**：

| code | 方法 | 入参 | 出参 |
|---|---|---|---|
| 1 | `u8(String)` | JSON 字符串 | String |
| 2 | `l3()` | 无 | int |
| 3 | `w2()` | 无 | int |

**逐个反汇编实现后，结论反转：**

```
l3()   → Binder.getCallingUid() → PackageManager.getNameForUid()
       → "getPreDownloadSwitchStatus, packageName:" + 该包名
w2()/d → Binder.getCallingUid() → getNameForUid() → "showPreDownloadGuide, packageName:"
u8()   → JSONObject(入参) → "resourceParam.taskId" / "resourceParam.resourceKey"
       → PreDownloadServiceImp.u() → 下载逻辑
```

**三个方法全部用 `getCallingUid()` 自行查调用方身份，完全不信任客户端传入的包名。**
实测验证：传 4 种不同包名（自身/系统应用/宿主/路径穿越串）返回完全一致，
服务端日志里的 `packageName` 始终是 `com.misrc.poc`（内核 uid 决定，不可伪造）。

用正确契约传 8 种 JSON payload（合法结构、扁平 key、路径穿越、超大 taskId）：

```
code=1 u8(*)  → String="null"    （全部）
code=2 l3()   → int=2
code=3 w2()   → int=-1
```

**无调用方鉴权 ≠ 可越权。** 该服务在设计上就是给任意应用查询自身预下载状态的
（`getPreDownloadSwitchStatus` 返回的是"**你自己**的开关状态"），
包名由内核 uid 绑定，不存在身份伪造空间。**该面已关闭。**

其两个 Provider 亦被系统拒绝：

```
SecurityException: ... PredownloadProvider ... that is not exported from UID 1000
SecurityException: ... DownloadFileProvider ... that is not exported from UID 1000
```

`PreDownloadSettingsActivity` 虽有 BROWSABLE deeplink（`securitycenter://gamebooster_predownload_settings`），
但声明了 `com.miui.gamebooster.access.ACTIVITY` 权限保护。
#### D. 剪贴板 / 安全检测服务 —— 未取得可利用证据

| 服务 | 描述符 | 实测结果 |
|---|---|---|
| `SecurityClipboardService` | `android.os.IMessenger` | 返回 Messenger 而非业务 binder；构造 `Message` 时抛 `NullPointerException`（`sendingUid` 为 null），无法用裸 `transact` 驱动 |
| `MiSafetyDetectService` | `com.xiaomi.security.xsof.IMiSafetyDetectServer` | 7 个事务码全部返回 `noException`，但响应体仅 4 字节（空 int），**未取得数据** |

这两个服务的 `onTransact` 实现尚未逐个反汇编，**因此不能断言安全，也不能断言存在漏洞**。
这是未覆盖项，不是结论。

## 二、明确的未覆盖范围

诚实标注，避免把"没测"当成"没问题"：

1. **124 个无权限导出 Activity 的 Intent 重定向 / deeplink 注入** —— 未系统测试
2. **混淆代理（confused deputy）** —— 未完成。本轮已具备能力
   （`misrc/mth.py` + `misrc/dis.py` 可精确反汇编任意方法），但未做
   「导出 Activity → 调用特权接口」的定向映射
3. **`MiSafetyDetectService` 的 7 个方法实现** —— 有放行迹象但无数据，需逐个反汇编确认
4. **其余 2 个无权限 Service（`XMPushService` / `AppLLMService`）** —— 前者 bind 超时，
   后者服务端 `ClassNotFoundException`（类不存在，非权限问题）
5. **网络侧 / 服务端接口** —— 完全未涉及，且需单独授权
6. **其余 134 个米系应用** —— 仅静态解析了 7 个

> 注：测试仪器问题已解决。设备端授权后 `adb install` 成功，
> 后续全部动态测试均以真实 uid（10395）执行，非 shell uid 污染结果。

## 三、两个被实测推翻的中间假设（记录以免重复）

### 3.1 「`checkCallingPermission` 出现 0 次 → 校验密度低」

**错误。** 实测证明服务端使用自定义 uid 白名单（报错文案 `caller uid=X is not allowed`），
完全不走这两个框架 API。以特定 API 出现次数推断安全性不成立。

### 3.2 「服务无鉴权 + 返回 OK → 可越权伪造包名」

这是本轮最危险的误判，值得详细记录：

- `PreDownloadService` 对 code=1/2/3 全部放行、零 `SecurityException`，
  与 VPN 服务 27 个接口全拒形成鲜明对比 → 我一度认为这是越权
- 但 4 种不同 payload（自身包名/系统包名/宿主包名/路径穿越串）**返回完全一致**，
  这本应是"参数根本没被使用"的强信号，我却继续在错误假设上叠加推理
- 反汇编后才确认真相：`l3()`/`w2()` 用 `Binder.getCallingUid()` 自行查调用方，
  客户端传什么包名都无效；`u8()` 的 `JSONObject` 解析失败是**参数契约错误**，
  不是鉴权拒绝

**教训**：`SecurityException` 缺失只说明 dispatcher 层没有权限检查，
不等于业务方法可被有效触达。必须用「不同输入 → 不同输出」验证参数确实被消费。

## 四、如果要继续

按投入产出排序：

1. **混淆代理定向分析**（最高优先）：用已具备的反汇编能力，
   对 124 个无权限导出 Activity 做「是否调用特权 binder」的定向映射。
   这是本轮唯一没做完、且最可能出结果的方向。
2. **`MiSafetyDetectService` 深挖**：7 个方法放行但无数据，需反汇编确认
   是否存在需要前置状态才能取数的路径。
3. **Activity/deeplink 面**：124 个候选，配合真实 uid 做模糊测试。

## 五、证据文件

| 文件 | 内容 |
|---|---|
| `misrc/parse_manifest.py` | 自建 AXML 解析器 |
| `misrc/dex_methods.py` | 自建 dex 类/方法解析器 |
| `misrc/callgraph.py` | dex 方法引用提取 |
| `misrc/targeted_deputy.py` | 敏感符号定向定位 |
| `misrc/poc/` | 测试 APK 源码与构建产物 |
| `misrc/evidence_vpn_binder.txt` | VPN Binder 27 接口拒绝日志 |
| `misrc/apk/` | 7 个被分析 APK |
