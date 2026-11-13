# 第 13 批：40 处 needs-review 逐点判定（v75）

承接 v74（metric=40）。v74 之后**客体栈上已无 must-degrade**，剩下 40 处全是
`needs-review`（审计器不敢自动判的那一类）。本批把它们逐点摊开，只处理能给出
**平台依据**的那部分；给不出依据的原地留着，并写清"要回答什么问题"。

判据仍是一条：**平台在这个调用点会失败吗？**

- 会失败 → 保留抛，但异常类型与参数条件必须与平台原话一致；
- 不会失败 → 不得抛，改成平台会返回的值（`nothing`）或直接放弃这门检查（`drop` + 继续）。

---

## 1. 本批收口：getSystemService 家族的 10 处

这是唯一一族平台规则最硬的（`Context.getSystemService` 的合法答复只有
`Manager` 或 `null`）。10 处全部是"回调/listener 的类加载器不是我们的"这一类
**容器内部所有权概念**——平台没有这个概念：把别的库造的回调对象交给平台注册，
平台照常接受。

| # | 位置 | 原判定 | 平台在这个点会失败吗 | 本批做法 |
|---|---|---|---|---|
| 1 | `RuntimeConnectivity:27` `register()` | `NETWORK_CALLBACK_OWNER_INVALID`（SecurityException） | callback==null 时**会**：`registerNetworkCallback` 抛 IAE；类加载器不属于我们：**不会** | 拆开：null→`IllegalArgumentException(NETWORK_CALLBACK_REQUIRED)`；异加载器→`drop` + 继续注册 |
| 2 | `RuntimeConnectivity:46` `request()` | 同上 | 同上（`requestNetwork` 同族） | 同上 |
| 3 | `RuntimeTelephony:21` `register(Executor,TelephonyCallback)` | `TELEPHONY_CALLBACK_OWNER_INVALID` | null 时**会**：`registerTelephonyCallback` 对 null callback 抛 NPE；异加载器：**不会** | null→`Objects.requireNonNull`；异加载器→`drop` + 继续 |
| 4 | `RuntimePower:19` `add(listener,executor,isHeadroom)` | `THERMAL_LISTENER_OWNER_INVALID` | null 时**会**：`Preconditions.checkNotNull`；异加载器：**不会** | 同上 |
| 5 | `RuntimeWifiP2p:32` `initialize(...,listener)` | `WIFI_P2P_CALLBACK_OWNER_INVALID` | **不会**（平台允许 listener==null，也不看来源） | `drop` + 继续 |
| 6 | `RuntimeWifiP2p:43` `beginClose(channel)` | `WIFI_P2P_CHANNEL_OWNER_INVALID`（SecurityException） | **会**：`WifiP2pManager.close()` 对不认识的 channel 抛 IAE | 类型对齐 `IllegalArgumentException`，码进 `PLATFORM_THROW` |
| 7 | `RuntimeSystemBroadcasts:116` `roots(host)` | `PLATFORM_STORAGE_OBSERVATION_UNAVAILABLE` | **不会**（`StorageManager` 为 null 时平台没有抛点可比） | `drop` + 返回 `Set.of()` |
| 8 | `RuntimeUiEffects:14` `play(root,effect)` | `PLATFORM_UI_AUDIO_UNAVAILABLE` | **不会**（无音频服务 = 静音，不是异常） | `drop` + `return`（不响） |
| 9 | `RuntimeProcessView:25` 进程观测 | `PLATFORM_PROCESS_OBSERVATION_UNAVAILABLE` | **不会**（`getRunningAppProcesses()` 不返回 null） | `drop` + 当空列表 |
| 10 | `RuntimeSystemBroadcasts:64` `prepare()` | `PLATFORM_BROADCAST_POLICY_UNAVAILABLE` | **本批不判** | 原样保留，见 §5 |

`getsystemservice` 家族由此**只剩 1 处 needs-review + 1 处 keep**。

---

## 2. 两处新增白名单，及其为什么不是"改数字"

`PLATFORM_THROW` 的语义是"平台在同一个点自己也会抛"。本批只加了 2 条，两条都
是**从 SecurityException 改成平台自己的异常类型**，不是把抛点删掉：

```python
# ConnectivityManager.registerNetworkCallback / requestNetwork 对 null callback
# 抛 IllegalArgumentException（"networkCallback must not be null"）：同一点，保留。
"NETWORK_CALLBACK_REQUIRED",
# WifiP2pManager.close(channel) 对不认识的 channel 抛
# IllegalArgumentException("Unknown channel: ...")：同一点，保留（类型已对齐 IAE）。
"WIFI_P2P_CHANNEL_OWNER_INVALID",
```

第 6 条尤其要注意口径：`beginClose` 的"不在我们的 channel 表里"与平台的"未知
channel"是同一个条件（客体的 channel 只能来自我们的 `initialize()`），所以它是
真 platform-throw，只是原来抛错了类型（SecurityException 而不是 IAE）——类型
错了在客体侧同样致命，因为客体 catch 的是 IAE。

顺带记一条**审计器盲区**（本批用到两次）：`Objects.requireNonNull(x,"CODE")`
抛的是 NPE 且不匹配 `THROW` 正则，所以第 3、4 条那样的改法**不动 metric**。
这是好事（说明它们是平台形状），但也意味着 metric 不是"剩余风险"的完备度量。

---

## 3. 度量

```
v74: guest_facing=41  metric=40   （must-degrade 0 / needs-review 40）
v75: guest_facing=33  metric=31   （must-degrade 0 / needs-review 31? 见下）
     platform_throw 3 → 6（含本批 2 条 + 原有 4 条中的 guest_facing 部分）
```

准确读法：`guest_facing=33`、`metric=31`，另有 2 处是 keep（`ANDROID_PENDING_RESULT_REQUIRED`、
`WIFI_P2P_CHANNEL_OWNER_INVALID`）。剩余 31 的分布：

| 家族 | 数量 | 内容 |
|---|---|---|
| package-endpoint | 19 | `COMPONENT_REQUIRED`×2、`PACKAGE_USER_UNSUPPORTED`×2、`PHYSICAL_COMPONENT_REQUIRED`×2、`PACKAGE_WORLD_*`×4、`INFRASTRUCTURE_*`×4、`PACKAGE_PLATFORM_CONTEXT_REQUIRED`、`PACKAGE_CONTEXT_IDENTITY_DENIED`、`PACKAGE_FRAMEWORK_CONTEXT_INVALID`、`PACKAGE_ENGINE_MISSING`、`PHYSICAL_PACKAGE_CAPABILITY_DENIED`、`VERSIONED_PACKAGE_REQUIRED` |
| receiver-pointer-stack | 11 | `RECEIVER_LOADER_MISMATCH`×2、`STICKY_ACTION_REQUIRED`、`RECEIVER_NOT_REGISTERED`、`BROADCAST_INTENT_REQUIRED`、`BROADCAST_RESULT_REQUIRES_ORDERED`、`BROADCAST_PREPARATION_UNCONFIRMED`、`BROADCAST_SEND_UNCONFIRMED`、`ORDERED_SYSTEM_BROADCAST_UNSUPPORTED`、`SYSTEM_RECEIVER_CLAIM_FAILED`、`BROADCAST_RESULT_UNCONFIRMED` |
| getsystemservice | 1 | `PLATFORM_BROADCAST_POLICY_UNAVAILABLE` |

---

## 4. 边界与语义代价（都是有意换的）

1. **异加载器回调现在会真的注册上去**（#1–#5）。原先是"拒绝 + 崩溃"，现在是
   "注册 + 记码"。容器少了一道内部所有权防线；代价由 `TripoderFacade` 日志
   按码去重保留痕迹。实机上这条分支本不该出现（客体的回调只会由客体自己造）。
2. **`roots()` 返回空集**（#7）意味着"没有已知系统卷根"。依赖这个集合判定
   "某 extras 里的路径是否系统路径"的投影因此退化为"都不算系统路径"。仅当
   `StorageManager` 为 null 时发生（正常设备不发生）。
3. **`playSoundEffect` 静音返回**（#8）——与无音频设备的平台行为一致。
4. **进程观测空列表**（#9）——客体拿到"没有进程"，而不是崩溃。

---

## 5. 刻意留下的 1 处，以及它需要先回答什么

`RuntimeSystemBroadcasts:64` `PLATFORM_BROADCAST_POLICY_UNAVAILABLE` 包的是
`System.loadLibrary("tripoderselfhook")` + `RuntimeFixedPreparation.framework()`
失败。它不是"参数不对"，而是**引导期不变式**：self-hook 没加载起来，广播保护策略
就不可信。

改它的前提是先证明 `prepare()` **不在客体栈上**。若它确实在客体栈上，正确做法
不是 `drop` 后继续（那等于放行未校验的系统广播），而是分叉成"保护不可用 →
按最严处理"。这条需要一次调用链取证（谁调 `prepare()`、在哪个线程），**本批不做**。

---

## 6. 剩余 31 处，下一批要回答的问题

这些都不是"照配方改"能了结的，每一条都要先判"这段代码到底跑不跑在客体栈上"。
按优先级列：

**A. package-endpoint 19 处里先分两类**

- **可能根本不在客体栈**（4+5 处）：`INFRASTRUCTURE_ALREADY_CAPTURED`、
  `INFRASTRUCTURE_MANIFEST_NOT_CAPTURED`、`INFRASTRUCTURE_QUERY_KIND_INVALID`、
  `PACKAGE_ENGINE_MISSING`、`PACKAGE_WORLD_ALREADY_INITIALIZED`、
  `PACKAGE_WORLD_ACTIVATION_ORDER`、`PACKAGE_WORLD_ACTIVATION_REJECTED`、
  `PACKAGE_WORLD_NOT_ACTIVE` —— 这些是抓取/世界激活的内部状态机，入口在容器
  引导，不在 `IPackageManager` 的 Binder 回包里。要证明的是"从 `dispatch()` 出发
  不可达"（静态调用图即可），证明了就该移出客体栈口径，而不是改代码。
- **平台确实会抛**（5 处）：`COMPONENT_REQUIRED`（null ComponentName →
  NPE/IAE）、`PACKAGE_USER_UNSUPPORTED`（未知 user → IAE）、
  `VERSIONED_PACKAGE_REQUIRED`（不存在的 versioned package →
  `NameNotFoundException`）。这三类要逐个核平台异常类型再进白名单。

**B. receiver-pointer-stack 11 处**

- 平台有对应抛点的：`BROADCAST_RESULT_REQUIRES_ORDERED`
  （`setResultData` 在非有序广播上抛 ISE）、`STICKY_ACTION_REQUIRED`
  （sticky 广播必须带 action）。
- 平台没有对应抛点、纯内部"未确认"语义的：`BROADCAST_PREPARATION_UNCONFIRMED`、
  `BROADCAST_SEND_UNCONFIRMED`、`BROADCAST_RESULT_UNCONFIRMED`、
  `SYSTEM_RECEIVER_CLAIM_FAILED`、`RECEIVER_NOT_REGISTERED` —— 这一组是本族
  真正的剩余风险，且都发生在客体 `onReceive` 栈上，必须降级。

---

## 7. 复现

```
python3 android/scripts/facade_throw_audit.py          # 末尾 JSON: {"metric": 31}
python3 android/scripts/build-workbench.py --build-dir android/product/build-facade-v75 \
  --sdk /opt/homebrew/share/android-commandlinetools --platform android-37.0 --build-tools 37.0.0 \
  --java-home /opt/homebrew/opt/openjdk --ndk /opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370 \
  --platform-libc android/runtime-probe/build/device-libc.so --platform-art android/self-hook-probe/build/device-libart.so \
  --platform-linker android/self-hook-probe/build/device-linker64
```

`BUILD_EXIT=0`，`tripoder.apk` 2,323,699 B，`sha256 9e07a05b3cb8dbf2…`（v75）。
**仅编译，未安装**（设备停在 PIN 锁屏，见
`../third-party-batch-advance-20261113/batch8-input-focus-keyguard-20261113.md`）。
