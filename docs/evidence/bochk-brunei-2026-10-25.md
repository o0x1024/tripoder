# BOCHK Brunei 启动复核：尚未修复

日期：2026-10-25，Asia/Shanghai。

## 本轮结果

原生冷启动 PID 22403 能到达 `com.bochk.main.MainActivity`。独立诊断包 `dev.tripoder.compatbochk` 使用当前工作区同一套运行时代码、原版设备 APK 集和新建客体数据，两种模式均失败：

| 模式 | PID | 结果 |
| --- | --- | --- |
| THIN | 23167 | 保护库加载成功后约 34 ms，原生线程 FORTIFY：pthread_mutex_lock called on a destroyed mutex |
| THICK | 23870 | 相同 FORTIFY 错误，Application 尚未完成构造 |
| THICK，加通用包查询诊断 | 24940 | 相同错误；最后两次查询均成功，均为客体自身的 getApplicationInfo(0)、getPackageInfo(0) |

flags=0 没有请求签名，因此签名数组为空符合该查询契约，不能作为签名缺失的证据。此次失败发生在 2 秒线程采样之前，与 10 月 22 日约 10 秒看门狗退出不能混为同一已证实首因。

原生故障快照中主线程栈包含 libc 的 `__cxa_finalize` 地址范围；这支持“主线程正在退出析构，后台线程碰到已销毁互斥锁”的解释。具体触发退出的条件仍未定位，不能把互斥锁错误直接当成 Tripoder 锁实现的缺陷。

## 修改和验证边界

仅增加 RuntimePackageDiagnostics 的受限结构诊断，并在 RuntimePackages 的统一入口记录结果：仅 QA startupDiagnostics 开关开启时记录方法名、调用对象角色、flags、结果是否存在、签名数量；不记录签名内容、账户、UI 或凭证。构建成功，已在独立包重现。没有修改客体 APK、保护组件、退出条件或模式语义，没有加入应用包名分支。

原 Tripoder 数据和原生应用数据未清除；没有登录或执行业务操作。诊断包保持安装但已 force-stop，原 Tripoder 已重新打开。未提交或推送。启动操作的 UNKNOWN 收据保持原状，没有将进程死亡推断为可重放。

## 下一步

1. 将“退出触发条件”作为验收前置证据，不能继续凭 FORTIFY、ADB 或库加载路径猜测修复。
2. 对照原生与客体在相同初始化点的 Context/LoadedApk、包信息、代码路径及原生进程观测，提取能由自有客体独立复现的契约差异。
3. 仅在差异由自有客体复现后修改共享实现，并要求同一构建在 THIN、THICK 实际进入客体 MainActivity、越过退出窗口。若条件依赖 Android 内核/PackageManager 的真实包与 UID 归属，则需设计 Android 管理的真实执行身份，不能靠 Hook 伪造结果填补该边界。

截至本轮，以上第三步尚未达到，BOCHK Brunei 未修复。

构建摘要和受限日志：[receipt.json](bochk-brunei-2026-10-25/receipt.json)。原始原生快照、栈和临时诊断制品保留在 `/tmp/bochk-diag-current/`，未放入仓库。
