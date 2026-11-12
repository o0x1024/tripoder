# 京东容器内一键登录闭环实测（2026-11-13）

## 结论

容器内京东（`com.jingdong.app.mall` 16.1.50 / 102918）**本机号码一键登录成功**，登录后个人中心完整可用，**全程没有任何安装来源 / 渠道类提示与阻断**。

因此：**京东的 installer / 安装来源类校验在容器内可满足**——它的登录门槛不落在「渠道归属」这一族事实上。这与抖音的 P2 边界是同构对照：两台应用在容器里拿到的是**完全相同的**来源回答（`installer=null`、`installSource` 三字段 null）与**完全相同的** uid 结构（客体跑在宿主 uid 10542 上、包名与运行 uid 不自洽），抖音据此拒绝登录，京东照常放行。

推论：**「容器内电商类应用能不能登录」不能按抖音个案外推**。判据是每个应用自己的风控策略，必须逐应用实测。

## 环境

| 项 | 值 |
| --- | --- |
| 设备 | `d861a4fa`（MIUI） |
| 宿主 | `dev.tripoder.q6` = `build-hint-v53`（`MAPS_PROBE=false`） |
| 原生京东 | `versionName=16.1.50`、`versionCode=102918`、`installer=com.xiaomi.market`、`codePath=/data/app/~~t1_8smFEJBp7R0pV9ak5TQ==/com.jingdong.app.mall--HQzT1apfk1MNAL_8FkqSw==` |
| 容器内京东 | 已在 Workbench 应用列表中（9 个应用之一），启动后 5 个进程全部 `uid=10542`（=宿主 uid），与抖音客体同构 |

容器侧来源回答与抖音客体一致（宿主自身 `installerPackageName=null`，且宿主未声明任何 `ContentProvider`），细节见
[身份与来源契约](../../identity-provenance-contract.md) 与 [P2 来源能力矩阵](../../guest-provenance-capability-matrix.md)。

## 实测步骤与观测

| # | 动作 | 观测 |
| --- | --- | --- |
| 1 | Workbench 轻点「京东」 | `ps -A`：`com.jingdong.app.mall`（pid 14073）、`:widgetProcess`、`:jdpush`、`:mantoProcess`、`:manto0` 全部 `uid=10542`；`topResumedActivity=dev.tripoder.q6/...TaskP0$F0M1C0`；首页商品流正常渲染，**无任何来源提示**（截图：[home.png](home.png)） |
| 2 | 读取未登录态 | 首页底部浮层「登录最高得 5 元红包 / 立即登录」 |
| 3 | 点「立即登录」 | 登录页正常渲染，`uiautomator dump` 可读：`153 **** 8553`、`天翼数字生活账号为您提供号码认证服务`、`本机号码一键登录`、`更多登录方式` |
| 4 | 勾选协议（`87,2238`）→ 点「本机号码一键登录」（`604,1318`） | 约 12 秒后回到首页：出现「¥164 惊喜红包 / 待使用」，底部「立即登录」浮层消失 ⇒ **登录成功** |
| 5 | 点「我的」 | 个人中心渲染：账号 `轻风游子-安定x8162`、`普通会员`、`PLUS会员入口`、优惠券 / 京豆 / 红包 / 白条 / 金条等模块全部在位 |
| 6 | 进入评价中心（评价/问答/待评价） | 页面正常，账号信息一致 |

一键登录走的是运营商号码认证（天翼数字生活账号），掩码号码与本机一致 ⇒ 号码认证 SDK 在容器内可正常工作。

## 与抖音的对照（为什么这条证据有意义）

| 事实 | 抖音（[结论](../../douyin-risk-control-strategy-analysis-20261112.md)） | 京东（本文） |
| --- | --- | --- |
| `installer` | `null` | `null` |
| `installSource` 三字段 | 全 `null` | 全 `null`（同一门面代码路径） |
| 客体运行 uid vs 包名 | 10542 vs `com.ss.android.ugc.aweme`（不自洽） | 10542 vs `com.jingdong.app.mall`（同样不自洽） |
| APK 字节 / 签名 | 与原生一致 | 未经字节比对（京东侧未触发该判定，无需） |
| 登录结果 | **被拒**：`当前应用版本过低或经非官方渠道下载` | **成功**，账号完整可用 |

同一容器、同一来源回答，两个结果 ⇒ 拦截与否取决于**应用自身的判据**，而不是容器是否「看起来官方」。

## 边界

1. **n=1**：只跑了一次冷启动 + 一次登录，未做多次复测与稳定性统计。
2. **未测交易强风控**：下单、支付、白条、PLUS 购买等环节未验证。登录成功**不等于**支付链路可用（支付类风控常另查设备指纹 / 环境完整性）。
3. **登录页是 `FLAG_SECURE`**：`screencap` 得到纯黑图（22 KB），因此登录页只有 `uiautomator dump` 的结构证据，没有像素证据。京东的 UI dump 可用（抖音登录页返回 `null root node`，两者不同）。
4. **本次证据不含读取序列**：`TripoderIdentitySnapshot` 每个客体进程只在启动时打一条快照，本轮的 `logcat -c` 发生在登录动作之前、晚于客体进程启动，因此没有抓到京东进程启动时的 `facadeAnswer` 快照，也没有「京东读了几次 installer」这类计数。结论建立在**行为观测**上（无提示、登录成功），不是读取计数。
5. **真实账号**：使用真实手机号账号（`153****8553`）在容器内建立了登录会话。回滚方式：容器内「我的 → 设置 → 退出登录」，或删除该空间内的京东数据。

## 复现命令

```bash
adb -s d861a4fa shell am start -n dev.tripoder.q6/dev.tripoder.hooklab.WorkbenchActivity
# Workbench 报 AUTHORITY_HEALTH_TIMEOUT 时点「重新连接」，再轻点「京东」
adb -s d861a4fa shell "ps -A -o PID,UID,NAME | grep jingdong"
adb -s d861a4fa shell "dumpsys activity activities | grep -m1 topResumedActivity"
adb -s d861a4fa shell uiautomator dump /sdcard/jd.xml && adb -s d861a4fa shell cat /sdcard/jd.xml
# 登录页：勾选协议 → 点「本机号码一键登录」
adb -s d861a4fa shell input tap 87 2238
adb -s d861a4fa shell input tap 604 1318
```

坐标取自本轮 dump（1208×2624），不同分辨率需重新解析 `bounds`。
