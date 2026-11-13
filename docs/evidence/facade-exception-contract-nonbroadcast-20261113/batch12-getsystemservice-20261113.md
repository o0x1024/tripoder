# 第 12 批：`getSystemService` 家族收口（`build-facade-v74`）

> 日期：2026-11-13 · 设备 `d861a4fa`（PIN 锁屏，`AMS isSleeping=true`，本批**未上机**）
> 前置：[`batch11-package-endpoint-20261113.md`](batch11-package-endpoint-20261113.md)（v73，metric 61→55）
> 本批判据不变：**平台在这个调用点会失败吗？** 不会失败就不能抛。

## 1. 为什么这一族最危险

`GuestContext.getSystemService()` **直接**调 `Runtime*.facade()`，没有 Binder 边界、没有 `invoke()` 兜底、
没有客体可 catch 的位置。因此这 13 处 `*_FACADE_IDENTITY_INVALID` / `*_FACADE_REQUIRED` /
`PLATFORM_*_UNAVAILABLE` 全都跑在**客体自己的线程栈**上。

平台在这一点的合法答复只有两种：**Manager** 或 **null**（服务不存在时）。
平台不会因为「门面身份对不上」抛 `SecurityException` —— 那是容器内部概念。

现实中这一族的触发条件是「宿主 `getSystemService(X)` 返回 null」或「门面构造返回 null/等于平台实例」。
前者在真机上几乎不发生，后者的现实来源是**门面被替换/GC 后重建**；一旦发生，客体的
`getSystemService` 就变成一次进程崩溃，而现场（栈顶）只会指向应用自己的调用行。

## 2. 已存在的配方（不是本批发明的）

`RuntimeActivityManager:20` 早就是平台式写法：

```java
facade=(built==null||built==physical)?RuntimeFacadeContract.nothing("ACTIVITY_MANAGER_FACADE_IDENTITY_INVALID",null,physical):built;
```

`nothing(code,error,answer)` = 记一次 `TripoderFacade` 码 + 返回 `answer`（不抛）。
本批把这行模式复制到其余各族，**没有引入新抽象**。

## 3. 改动（16 处，13 处计入 metric）

| # | 文件:行 | 旧 | 新（= 平台答复） | 计 metric |
|---|---|---|---|---|
| 1 | `RuntimeActivityManager:26` | `throw ISE(PLATFORM_ACTIVITY_MANAGER_UNAVAILABLE)` | `return nothing(...,null)` → null | ✓ |
| 2 | `RuntimeAudio:13` | `throw SEC(AUDIO_FACADE_IDENTITY_INVALID)` | `facade=nothing(...,platform())` | ✓ |
| 3 | `RuntimeAudio:15` | `throw ISE(PLATFORM_AUDIO_UNAVAILABLE)` | `return nothing(...,null)` | ✓ |
| 4 | `RuntimeAudio:18` | `throw SEC(AUDIO_CALLBACK_OWNER_INVALID)` | `null` 回调直接返回；异加载器记码后继续注册 | ✓ |
| 5 | `RuntimeBattery:12` | `throw SEC(BATTERY_FACADE_IDENTITY_INVALID)` | `facade=nothing(...,platform())` | ✓ |
| 6 | `RuntimeBattery:16` | `Objects.requireNonNull(...,"PLATFORM_BATTERY_UNAVAILABLE")`（NPE） | `return nothing(...,null)` | ✗（正则盲区） |
| 7 | `RuntimeConnectivity:16` | `throw SEC(CONNECTIVITY_FACADE_IDENTITY_INVALID)` | `facade=nothing(...,physical())` | ✓ |
| 8 | `RuntimeConnectivity:20` | `throw ISE(PLATFORM_CONNECTIVITY_UNAVAILABLE)` | `return nothing(...,null)` | ✓ |
| 9 | `RuntimeLocation:30` | `throw SEC(LOCATION_FACADE_IDENTITY_INVALID)` | `facade=nothing(...,platform())` | ✓ |
| 10 | `RuntimeNetworkStats:8` | `throw ISE(NETWORK_STATS_FACADE_REQUIRED)` | `return nothing(...,null)` | ✓ |
| 11 | `RuntimePower:13` | `throw SEC(POWER_FACADE_IDENTITY_INVALID)` | `facade=nothing(...,platform())` | ✓ |
| 12 | `RuntimeProcessView:23` | `throw ISE(PLATFORM_ACTIVITY_MANAGER_UNAVAILABLE)` | 记码 + 返回空 `processes` Bundle | ✓ |
| 13 | `RuntimeTelephony:14` | `throw SEC(TELEPHONY_FACADE_IDENTITY_INVALID)` | `facade=nothing(...,platform())` | ✓ |
| 14 | `RuntimeTelephony:18` | `throw ISE(PLATFORM_TELEPHONY_UNAVAILABLE)` | `return nothing(...,null)` | ✓ |
| 15 | `RuntimeWifiP2p:16` | `throw ISE(WIFI_P2P_FACADE_REQUIRED)` | `return nothing(...,null)` | ✓ |
| 16 | `RuntimeWifiP2p:26` | `throw SEC(WIFI_P2P_CONTEXT_OWNER_INVALID)` | 记码后**继续**（平台接受任意 Context） | ✓ |

> #6 是审计器的盲区：`Objects.requireNonNull(x,"CODE")` 不是 `throw new X("CODE")`，
> 正则 `THROW` 抓不到它，所以它从不进 `metric`。本条按同一不变量顺手改掉，**不体现在数字里**。

## 4. 度量

```
改前（v73）: guest_facing=56  metric=55   ← 其中 must-degrade 13 处（本族全部）
改后（v74）: guest_facing=41  metric=40   ← must-degrade 0 处；剩下 40 全是 needs-review

剩余 40 的分布：getsystemservice 10 / package-endpoint 19 / receiver-pointer-stack 11（含 1 处 keep）
```

**口径提醒（会看错数字的地方）**：脚本尾部那行 `must_degrade=6 needs_review=1849` 是对**全部 rows**
（含 `host-internal`）统计的，不是客体栈口径；只有 `metric`（= `guest_facing` 里的 must-degrade + needs-review）
才是门禁数。本批之前 `metric=55 = 13 must-degrade + 42 needs-review`（old 口径下 needs-review 42 里含 package-endpoint 19 与 receiver 11 等），
本批之后 **客体栈上已无 must-degrade**，剩下的每一条都需要逐点判定平台行为。

复现：`python3 android/scripts/facade_throw_audit.py`（末尾 JSON 的 `metric` 即待处理数；F9 门禁只降不升）。

**family 归属的口径说明（重要，别被数字骗）**：审计器按**码名后缀**分族
（`*_FACADE_IDENTITY_INVALID` / `*_FACADE_REQUIRED` / `*_OWNER_INVALID` / `PLATFORM_*_UNAVAILABLE`），
不是按栈。因此 `RuntimeProcessView:23` 虽被归进 `getsystemservice` 族，实际跑在 **authority 侧**
（`broker.host.getSystemService`），它的抛出会变成 broker 错误回复而不是客体未捕获异常。
本批一并把它改成空结果，属于「顺手统一口径」，不是「救了一个崩溃点」。

## 5. 保留项与理由

`getSystemService` 族还剩 10 处 `needs-review`（metric 40 的组成部分）。逐条判定：

| 码 | 位置 | 处置 | 理由 |
|---|---|---|---|
| `NETWORK_CALLBACK_OWNER_INVALID` ×2 | Connectivity:27,46 | 保留 | `unregisterNetworkCallback(未知 callback)` 在平台上抛 `IllegalArgumentException` —— 同点同形状 |
| `THERMAL_LISTENER_OWNER_INVALID` | Power:19 | 保留 | 同理，注销未注册的 listener 平台会抛 |
| `TELEPHONY_CALLBACK_OWNER_INVALID` | Telephony:21 | 保留 | 注销未注册 callback，平台 `IllegalArgumentException` |
| `WIFI_P2P_CALLBACK_OWNER_INVALID` | WifiP2p:32 | 保留 | `initialize` 的 listener 归属；平台不校验加载器，但这里失败只会导致 listener 不回调，留待真机判断 |
| `WIFI_P2P_CHANNEL_OWNER_INVALID` | WifiP2p:43 | 保留 | `close(未知 channel)` 平台抛 `IllegalArgumentException` |
| `PLATFORM_PROCESS_OBSERVATION_UNAVAILABLE` | ProcessView:25 | 保留 | `getRunningAppProcesses()` 返回 null 在平台上不可能；返回 null 会让 guest NPE，返回空表会谎报「无进程」，两者都不比记录更好 → 需真机决定 |
| `PLATFORM_BROADCAST_POLICY_UNAVAILABLE` | SystemBroadcasts:64 | 保留 | 广播策略取不到时，平台侧行为未确认；改动会碰投递路径 |
| `PLATFORM_STORAGE_OBSERVATION_UNAVAILABLE` | SystemBroadcasts:116 | 保留 | 同上，且与存储身份族（网易云）交织 |
| `PLATFORM_UI_AUDIO_UNAVAILABLE` | UiEffects:14 | 保留 | UI 音效管理缺失：平台返回 null 会让 `playSoundEffect` 静默，与抛出的差别需真机确认 |

也就是说：本批只清「平台**必然**有答复、而我们在抛」的那些；剩下 10 处要先拿到真机行为才能定。

## 6. 构建

```
python3 android/scripts/build-workbench.py --build-dir android/product/build-facade-v74 \
  --sdk /opt/homebrew/share/android-commandlinetools --platform android-37.0 --build-tools 37.0.0 \
  --java-home /opt/homebrew/opt/openjdk --ndk /opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370 \
  --platform-libc android/runtime-probe/build/device-libc.so --platform-art android/self-hook-probe/build/device-libart.so \
  --platform-linker android/self-hook-probe/build/device-linker64

BUILD_EXIT=0
tripoder.apk  2,323,699 B  sha256 8b3b55205a5cbcf9098dd93aa2ab8c3fc3bfbb1cf2830c8feaeec296bec3c73e
```

## 7. 边界（别过度推断）

1. **只编译，未上机**：v74 没有安装到设备（PIN 锁屏）。`BUILD_EXIT=0` 只证明 javac 接受这 16 处改写。
2. **方向是单向的（少抛）**，但有两处换来了语义：`AUDIO_CALLBACK_OWNER_INVALID` 现在会**继续注册**
   异加载器回调（平台本来就不校验加载器）；`WIFI_P2P_CONTEXT_OWNER_INVALID` 现在接受任意 Context。
   两者都让「容器本会拒绝」的调用变成平台行为，代价是失去一条内部所有权防线（日志仍留痕）。
3. `*_FACADE_IDENTITY_INVALID` 的降级把**物理 manager**交给客体。这与 `RuntimeActivityManager:20`
   既有做法一致（客体本就跑在宿主 uid 上），但确实扩大了客体可触达的物理对象面。
4. metric 40 仍包含 34 处 `needs-review`，本批未触碰；其中 `package-endpoint` 的
   `PACKAGE_PLATFORM_CONTEXT_REQUIRED` / `PACKAGE_CONTEXT_IDENTITY_DENIED` 依然押后等真机。
