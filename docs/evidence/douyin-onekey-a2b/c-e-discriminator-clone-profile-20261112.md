# C1/E1 判决：真实环境 + installer=null 的一键登录（clone profile，2026-11-12）

设备 d861a4fa（Xiaomi 18 Pro Max / HyperOS 4.0.27.0.XEOCNXM，用户 0 = 张杰）。
实验代号 `c-vs-e-discriminator-clone-profile`。**判据锁 C1，E1 排除。**

## 1. 为什么不是「第二空间」

计划里的对照组原本写作「MIUI 第二空间（user 999）」，实测该前提不成立：

```
$ adb shell dumpsys user | sed -n '/UserInfo{999/,/^  UserInfo{/p'
  UserInfo{999:XSpace:801010} serialNo=10 isPrimary=false parentId=0
    Type: android.os.usertype.profile.CLONE
    Flags: 8392720 (INITIALIZED|PROFILE|800000)
    State: RUNNING_UNLOCKED
    Created: +44d15h51m42s613ms ago
```

- `Type: android.os.usertype.profile.CLONE` + `parentId=0` + `PROFILE` ⇒ 这是**应用双开**的 clone profile，
  不是可分身前台的 full secondary user。因此 `am switch-user 999` 必然失败（**profile 不能成为前台用户**），
  设置里也没有「手机分身」入口。
- 旁证 1：`设置 → 更多设置 → 用户与账户 → 用户` 只列「我（张杰）机主」，无 XSpace。
- 旁证 2：`更多设置 → 特色功能` 只有传送门 / 复制直达 / 自由窗口 / 卡包 / 屏幕录制 / 内存扩展 / 边缘防误触。
- 旁证 3：`com.android.settings/.ConfirmSpacePasswordActivity` 可以 `am start`，但立即退出（缺 extras 不渲染页面）；
  `com.xiaomi.subscreencenter` 是「妙享背屏」副屏中心（display 1，976x596），与分身无关。

## 2. 不需要切空间也能做这个实验

clone profile 的应用与主空间**同屏运行**，用 `am start --user 999` 即可把它的界面拉到前台，当前用户仍是 0：

```
$ adb shell am start --user 999 -n com.ss.android.ugc.aweme/.splash.SplashActivity
Starting: Intent { cmp=com.ss.android.ugc.aweme/.splash.SplashActivity }
$ adb shell ps -A -o PID,UID,NAME | grep aweme
12927 99910327 com.ss.android.ugc.aweme
$ adb shell dumpsys activity activities | grep -m1 ResumedActivity
topResumedActivity=ActivityRecord{259105012 u999 com.ss.android.ugc.aweme/.splash.SplashActivity t16349}
$ adb shell am get-current-user
0
```

这一格正是此前缺的格子：**真实系统环境 + installer=null + 包名与运行 uid 自洽（99910327）**。

```
$ adb shell dumpsys package com.ss.android.ugc.aweme | grep -E 'versionName|installerPackageName|codePath'
    codePath=/data/app/~~z_ij0CA61jain4y2w89gRg==/com.ss.android.ugc.aweme-Lj7odB_wdtPnGwY-39BgLg==
    versionName=40.7.0
    installerPackageName=null
```

## 3. 登录过程（与容器同一条路径）

| 步骤 | 观测 |
|---|---|
| 启动 | `个人信息保护指引` → 点「同意」 |
| 登录页 | `一键登录 153****8553`、`认证服务由天翼数字生活账号提供`、`登录其他账号` |
| 勾协议 | `已阅读并同意 用户协议/隐私政策/天翼数字生活账号服务条款` 由「未选中」变选中 |
| 点「一键登录」 | 先弹系统权限 `是否允许"抖音"获取已安装的应用信息` → 选「始终允许」 |
| 结果 | **直接进入 feed**（推荐流可见），无任何拦截弹窗 |
| 账号 | 点「我」：`可可儿`、`抖音号：85191533219` |

拦截文案在这段时间的 logcat 里 0 次命中：

```
$ adb shell logcat -d -t 400 | grep -cE '非官方渠道|force_update|block_mode'
0
```

## 4. 判决表

| 格子 | 运行环境 | installer | 包名↔运行 uid | 结果 |
|---|---|---|---|---|
| 容器（dev.tripoder.q6 内） | 虚拟化 | null | ✗ 10542 | **拦截**「当前应用版本过低或经非官方渠道下载」 |
| 本实验（clone profile 999） | 真实系统 | null | ✓ 99910327 | **登录成功**（可可儿） |
| user 0 原生 | 真实系统 | 现为 null（侧载副作用） | ✓ 10327 | 登录页可正常登录（早前实测） |

- 只改变「承载方式」、保持 `installer=null` 不变，拦截消失 ⇒ **本地安装归因（A 平面）不是判据**（与 v46 谎报实验一致）。
- 真实环境 + 无商店归因（E1 的典型形态）仍然登录成功 ⇒ **E1 不成立**。
- 剩下的差异只有容器运行期身份：客体以宿主 uid 10542 运行，与其自称的包名不一致（C1，见 `guest-runs-as-host-uid`）。
  **判据 = C1。**

## 5. 边界与副作用

- 只跑了一次；同一账号（可可儿）在 clone profile 里多登录了一份，双开数据独立，主空间登录态不受影响。
- 过程中给 clone profile 的抖音授予了「获取已安装的应用信息 = 始终允许」（系统权限弹窗的正常选择），未改动任何来源事实。
- 结论对「容器该不该继续投入」是决定性的：**C1 是容器结构本身（同进程虚拟化）的属性，不能在容器内修**，
  与既有 `uid-install-time-attribute`、`guest-runs-as-host-uid`、`p2-unfixable-conclusion` 一致。

## 6. 复现命令

```bash
adb -s d861a4fa shell am start --user 999 -n com.ss.android.ugc.aweme/.splash.SplashActivity
adb -s d861a4fa shell ps -A -o PID,UID,NAME | grep aweme     # 期望 uid=99910327
adb -s d861a4fa shell dumpsys user | sed -n '/UserInfo{999/,/^  UserInfo{/p'
adb -s d861a4fa shell dumpsys package com.ss.android.ugc.aweme | grep installerPackageName   # null
```
