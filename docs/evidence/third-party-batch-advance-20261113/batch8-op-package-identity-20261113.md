# 第 8 批（续七）：身份分层——把 opPackage 交还给真正持有 uid 的包名

上一篇（`batch8-toast-identity-20261113.md`）把两件事钉死了：客体 `Toast.show()` 必抛是普遍事实（h42），而「换掉缓存的 `INotificationManager`」不是修法位置（h44 推翻，h45）。本文是那之后的第三步：**不动系统服务，改我们自己回答的身份**。

## 1. 为什么是「改身份」而不是「拦调用」

判断的链条很短：

1. `Toast.show()` → `INotificationManager.enqueueToast(pkg, …)`；
2. 系统服务端 `checkCallerIsSameApp(pkg, callingUid)`，判据是 **pkg 的属主 uid 是否等于调用 uid**；
3. 容器里客体跑宿主 uid `10542`，却自称 `tv.danmaku.bili`（属主 `10327`）⇒ 必败。

第 1、2 步里可改的只有 `pkg`。它由 `mContext.getOpPackageName()` 给出，而容器把这个问句答了两遍、答案还不一样：

- `RuntimeEngine` 早就在 base `ContextImpl.mOpPackageName` 上强制写了宿主包名；
- `GuestContext.getOpPackageName()` 却仍回答**客体**包名 —— 而 `Toast` 拿到的正是后者。

同一个问题两个答案，本身就是要修的不自洽。v82 把 `GuestContext` 的答案也改成宿主包名（`engine.host.getPackageName()`），由 `HOST_OP_PACKAGE` 一个开关控制，翻回 `false` 即恢复旧答案。

## 2. 结果：Toast 不再抛（v82，pid 11917）

```
I TripoderToastProbe: IDENTITY opPackage=dev.tripoder.q6 package=tv.danmaku.bili uid=10542
I TripoderToastProbe: PHYSICAL=OK
```

同一段探针代码、同一个客体、同一个物理 `NotificationManagerService`：v80/v81 是 `THREW SecurityException: Package tv.danmaku.bili is not owned by uid 10542`，v82 是 `OK`。**变量只有 opPackage 一处**。

## 3. A/B：哔哩哔哩从「必崩」变成「可用」

同一个动作（隐私页点「同意并继续」）在 v79 与 v82 下的对照：

| 版本 | 结果 |
|---|---|
| v79 | `FATAL EXCEPTION: main`，栈底 `Toast.show → enqueueToast → NMS.checkCallerIsSameApp`，进程死 |
| v82 | crash 缓冲**为空**；top 变 `TaskP0$F0M1C2`；dump 到首页（「登录，按钮」[30,150][171,270]、搜索栏、feed 内容） |

这同时反证了 h41 的原始表述：崩溃不是 bili 的代码质量问题，是这个身份不自洽的必然结果。

## 4. 回归：身份敏感的应用有没有回退

上一轮 `GuestContext` 的注释明确写着「返回容器包名会让 SDK 把重打包读出来（渠道、签名、运营商一键登录校验都这么做）」。所以这一改必须回归那一族应用。四个应用逐一冷启（每次先 `force-stop q6/q7` + 两次「重新连接」）：

| 应用 | 结果 | 判据 |
|---|---|---|
| 哔哩哔哩 | 通过（且更好） | 同意后进首页，crash 空 |
| 大众点评 | 通过 | top=`TaskP0$F0M1C2`；dump 到首页（美食/景点游玩/休闲玩乐）+ 2 进程 |
| 京东 | 通过 | top=`TaskP0$F0M1C0`；dump 到「搜索京东商品/店铺」 |
| 唯品会 | 通过 | dump 到「登录有机会领取惊喜优惠」，与已知良好基线一致 |

**没有一条回退**。其中大众点评与京东正是运营商一键登录族（上一轮实证过「同来源回答下京东一键登录可成功」），它们在本轮都没有因为 opPackage 变了而崩或空白。

## 5. 这条改动的语义代价（要留给下一轮复核的部分）

- 客体自报 `opPackageName ≠ getPackageName()` 成为常态（此前是相等）。任何把两者对比当作完整性自检的应用，看到的是「我自称宿主包」。
- 本轮**未覆盖**抖音：它在容器内的入口有守卫（「容器环境不支持抖音平台风控校验」），且 h2/h4 已证明它的判据不在本地来源族；但「opPackage 变化会不会触发它另一条本地自检」没有实测。
- 剪贴板（`clipboard-identity`）与存储（`storage-identity`）两族是同一根因的不同面：本轮只证了 Toast 面能被这一改收口，**没有**回头重测那两族；按机制推断它们也应随之改善（`callingPackage does not match UID` 与 `Package … is not owned by uid` 是同一判据），但「推断」不算证据。

## 6. 复现方式

- 打开探针：`RuntimeToastProbe.PROBE=true`，冷启任一客体，读 `logcat -d -s TripoderToastProbe:*`；
- 关掉身份分层：`GuestContext.HOST_OP_PACKAGE=false`，冷启同一客体，日志回到 `PHYSICAL=THREW …`；
- 构建必须显式 `--package dev.tripoder.q6`（默认包名是 `dev.tripoder.engine`，装错包 adb 仍报 Success）；本机 MIUI 的 `AdbInstallActivity` 确认页要点「继续安装」(355,2381)，否则 12s 后 `INSTALL_FAILED_USER_RESTRICTED`。
