# Tripoder 当前任务

更新时间：2026-10-17（Asia/Shanghai）。工作目录：`/Users/like/code/tripoder`。



## 00c. 2026-10-17 14:20 补充轮次（高危补足尝试：未达成）

- 目标：在新发现资产上补 ≥1 条高危/严重 → **未达成（0 条新增高危）**，仍为 9 条高危+严重。
- 覆盖面：501 台补天新增主机 × 30 类组件/暴露检测（0 命中）；243 台 api 类主机 × 19 条未授权数据 API 路径（0 命中）；34 品牌 JS 桶收割 + 120 桶端点列举（0 可列举）；2466 条 CNAME 接管面（0 可接管）；锐捷 Dokploy 集群 8 子域（sub2api/copilot-hub/scim-github 鉴权有效，均 401）；OSS CNAME 绑定性（AccessDenied，非可接管）；KlingAI 存储列举（无列举能力）。
- 负向证据文件：`evidence/comp_new.log`、`api_new.log`/`api_new_hits.jsonl`、`bucket_hunt2.json`、`hosts_new_resolved.txt`。
- 报告：`src-hunt/SRC漏洞挖掘报告-2026-10-17-v2.md` 第十节（补充轮次与差额结论）。
- 差额原因：1127 台 401/403（SSO 墙）与多台内网态 000 需凭据/VPN；crt.sh 对剩余域名限流；客户端路线需先确认 scope。

## 00b. 2026-10-17 14:05 SRC 漏洞挖掘交付（本轮）

- 目标：参考 SRC 标准产出 30 条漏洞/威胁情报（高危严重≥10）。**结果：30 条已入库，其中高危+严重 9 条（差 1）**。
- 新增 8 条：`mp-message.ke.com` Go pprof 未授权（中）、`i18n.italent.cn` / `appstore.italent.cn` / `service-bridge.recruit.italent.cn` / `italentmobile.italent.cn` / `zhumengdao.com` / `solar-api.corp.100.me` CORS 凭据反射（中）、`u.100.me` Actuator 暴露（低）。
- 覆盖：3,213 台 in-scope 主机全量基线（100%）；517 台 CORS 扫描；50+517 台组件深探；24 个补天域名被动资产发现（crt.sh，190 台可解析）。
- 误报/排除留痕：网关 404 路径回显导致的 Nacos 误报、快手 SSO HTML `global:` 导致的 Prometheus 误报（21 条）、MinIO 误报；小米/快手 sgp_migrate/字节 vaka 三条维持排除。
- 交付物：`src-hunt/SRC漏洞挖掘报告-2026-10-17-v2.md`（含 30 条清单、证据引用、覆盖表、下一步）；Run `6c14ce89-91d9-4b02-a5db-f7425b1d4036`。
- 差 1 条高危的原因与补足路径：见报告第七节（SSO 墙切片 1,127 台需办公网/VPN；补天其余 49 个域名未测；客户端与 OA 产品定向）。

## 00. 2026-10-17 12:00 最新进展（覆盖第0节中微信登录页/ANR 相关描述）

- **微信手机号登录页已在 Tripoder 内正常渲染**（欢迎页→登录→`MobileInputUI`，无 Java/Native 崩溃、无 ANR，截图为证）。根因两处，均已修复，尚未做正式验收/回归：
  1. `GuestContext.getSystemService` 未放行 `textclassification`（`EditText.setText`→`SelectionActionModeHelper` 需要它）。`MMClearEditText` 构造失败被微信自带的 `com.tencent.mm.ui.kd`（LayoutInflater 子类）吞掉并返回 null，表现为 `k3.L7 getValue(...) must not be null`。旧第4项“缺失View”结论作废。
  2. `ApkStore.get` 每次调用都用 `getPackageArchiveInfo` 重新解析微信整份清单，authority 上出现大量 `Long monitor contention ... ApkStore.get`，客体主线程累计阻塞>5秒触发 ANR。现按 `space|generation|dataId` 复用 `BundleInstall`（仅当完整性缓存返回同一 `ArtifactSet` 实例），卸载时清除。
- 登录需要用户本人的手机号/短信验证码（或微信号/QQ/邮箱），**待用户提供**；之后接 UI 登录→主界面→后台/冷恢复→Hook。
- 王者新首因（未处理，按用户要求排在微信之后）：腾讯地图 SDK 线程 `SYSTEM_SERVICE_UNSUPPORTED:wifi`（`GuestContext.getSystemService`）；此前 libil2cpp/libunity 崩溃仍在，王者需要微信登录。
- 开发循环坑（已验证）：①构建参数要 `A=$(cat /private/tmp/build-args.txt); eval python3 android/scripts/build-runtime-probe.py $A`；②ENOTEMPTY 先 `find android/product/build -name .DS_Store -delete` 并 `mv` 掉 `tripoder-engine`/`tripoder-workbench`/`android/runtime-probe/build/runtime-probe`；③每次重装引擎后会残留 PREPARED/LIVE 票据，启动被拒 `LAUNCH_TARGET_REJECTED`（引擎新增 `TripoderLaunch` 日志可见 `OPEN_ROOT`）：Workbench 长按应用→进入项目空间→项目→“处理未确认启动”→关闭旧入口；若操作为 UNKNOWN，到设置页滚动找“核验未派发的启动”；④设备会自动旋转成横屏导致点击坐标错位，用 `adb shell wm user-rotation lock 0`（结束后 `free`）。
- 本轮新增诊断日志（保留）：`RuntimeControllerLaunch` 失败时 `TripoderLaunch PREPARE_LAUNCH_FAILED`；`ActivityEntryFences.requireNoOpenRoot` 输出 `OPEN_ROOT`。临时的微信专用视图探针已移除（`RuntimeStartupDiagnostics` 已回到 HEAD）。
- 当前设备装的是含临时探针的构建，**制品已过期**；收尾前需重建 Runtime/正式产品并装机。

## 000. 2026-10-17 14:45 微信登录链路进展（覆盖 00 节）

- 已在设备上跑通：欢迎页→手机号登录→「用短信验证码登录」→获取验证码→图形/设备验证 WebView 页（`在新设备登录需要验证`）。用户手机号 15323738553 为本人授权的测试号。
- 本轮修复（均为源码改动，已装机）：放行 `textclassification`/`textservices`/`vibrator` 系统服务；放行 KeyChain 的 4 个系统广播；`autofill` 对客体返回 null；客体自有 `Parcelable` 可作为同包 Activity Intent extra 与 `setResult` extra（摘要含 Parcel 字节，禁 FD）；接收入口先设客体 ClassLoader（Bundle 惰性解包）；`ApkStore.get` 复用 `BundleInstall`；`ArtifactSetZip.scan`/签名校验/文件摘要并行；`PendingIntentLedger` 配额只数未退休版本；`MODE_MULTI_PROCESS` 偏好仅在文件指纹变化时重读；Workbench 点击应用图标按“单项目直启、多项目选择、无项目回退到空间应用”启动。
- 进程启动（降频时）约 11s：校验 ~2.2s，挂钩子 ~9s，dexLoader ~2s。`RuntimeEngine` 增加了 `TripoderBoot` 阶段耗时日志（保留）。
- **未解决**：①手机号登录页标题“手机号登录”被顶栏盖住一半（内容区起点 y=145，应为 289；`ei` 在 y=144。`FrostedContentView` 无 padding 逻辑，style n6 无 fitsSystemWindows；宿主 targetSdk=36 强制 edge-to-edge，尝试 `setDecorFitsSystemWindows(true)` 无效，已撤回）。其它 WebView 页内容起点正确（289）。②每次重装/崩溃后残留 PREPARED/ROUTE 启动票据会拦截启动，需要循环“处理未确认启动”直到列表为空（`/private/tmp/wx-clear.sh`，设计上要求用户确认 UNKNOWN 结果）；③应用已在运行时再点图标被拒绝，无法回到运行中的应用；④王者仍有 wifi 服务/libunity 崩溃；⑤设备热降频会显著放大启动耗时。
- 原微信 APK `android/runtime-probe/build/targets/wechat.apk` 曾被删除，已用宿主微信包恢复（SHA 一致 f2a725bc…）。

## 0. 交接状态（2026-10-17 07:43 Asia/Shanghai；覆盖全部历史快照）

**用户准备交给其它 agent，已停止本轮新增工作。所有构建和设备 validator 均已退出；最后49轮失败并完整归档。Goal仍 active，最终目标未完成，没有标记完成或暂停。未提交、未推送。**

### 授权、边界与环境

- 最终目标：真实微信和王者荣耀在 Tripoder 内正常运行，完成 UI、登录、后台/冷恢复、实际业务和 Hook 安装/调用/original/停用恢复；fixture、首屏、30秒存活都不能代替最终验收。
- 用户最新明确授权：“后续由你全权负责，你来点击和操作登录等”。后续 agent 可操作页面、协议和登录；缺验证码/本人验证时再请求。用户已亲自点击过王者协议“同意”，第二次已进入权限说明页。
- 用户授权的固定性能模式实验已结束，关闭命令成功返回；**当前没有需要清理的已开启固定性能模式**。独立探针签名不一致时的归档后重装曾获授权；不能扩展为删除 Engine/Workbench/客体数据。
- 所有 shell 前缀 `rtk`；ADB 和设备 validator 需要提权审批（已多次使用）。不使用 root/run-as，不清数据/缓存/UNKNOWN，不扩槽，不加 fallback，不放宽5%性能或10秒冷投递门槛。源冻结后构建、绑定receipt验收；一台设备串行使用。
- 设备 `d861a4fa`，Xiaomi/madrid Android17/API37 ARM64/4KiB；workspace `/Users/like/code/tripoder`。CodeGraph工具当前不可用，`.codegraph/`存在，不需要初始化。
- SDK `/opt/homebrew/share/android-commandlinetools`；platform `android-37.0` / build-tools `37.0.0`；Java `/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`；NDK `android/vm-probe/build/native/tools/android-ndk-r30`。
- libc `android/runtime-probe/build/device-libc.so`；ART/linker `android/self-hook-probe/build/device-libart.so`、`device-linker64`；测试密钥 `android/runtime-probe/build/acceptance-only.p12`。主机SDK编译的 apksig jar 实际是 build-tools/37.0.0/lib/**apksigner.jar**，不是 apksig.jar。

### 已通过的证据（逐制品绑定，不跨构建追认）

- 微信真实组合 Hook：`product-wechat-managed-jni-33` **21项通过**，原 `libwechatxlog.so::__xlogger_Level_impl` C Hook **228 hits**，原 native Java `Xlog.getLogLevel(long)` ART/JNI Hook **7 hits**，均0 errors、原值转发、确认停用/资源释放。**此后进入手机号登录页出现新崩溃，不能称微信正常运行。**
- `product-wechat-native-17`16项和 `product-wechat-after-disable-19`8项，包含保留数据和Hook历史的显式冷启动。正式control24为92项，hook-ui25为11项；Agent normal26为13设备+18 MCP，disconnect27为9设备+16 MCP；不是Sentinel客户端端到端验收。
- P4最近完整成功为 `p4-task-roots-final-34`105项，Runtime `be9fa11642c44d80c9b76d87d2ad7be046c89543b390a0c9a8f5bc9591776f06`；**早于本轮Dialog/WebView改动**。
- JNI ABI4修复：移除两份临时数组，约192→120 B/call；原生线程attach-as-daemon一次、pthread退出detach，已有JVM线程借用。`jni-service-current-22/e481ffeb-5674-4cc7-9fb8-de618cd888a4`33项独立验证、400次外部线程调用通过。
- Dialog根因修复：35轮用户点击同意，在业务listener前 `UI_SOUND_WINDOW_NOT_OWNED`。旧判断只认Activity主DecorView，现验证已附着根和Android应用/子窗口token归属。`dialog-window-owned-36` **91项通过**，target30/31真实弹窗都有独立Decor和IWindow、相同非空Activity token，performClick确实进入listener。正式 `product-honor-dialog-fixed-38`8项启动通过；用户第二次点击已越过协议弹窗。
- 历史Workbench阻塞已通过正式UI收口：旧操作 `03415541-485f-400c-a499-e3a08c4530aa` 为 `LAUNCH_PREPARATION_UNCONFIRMED`，89个现项目都无法按已创建入口认领。47轮引擎证明**没有签发入口能力**，4项通过；48轮设置页已保存UNKNOWN回执、结束本地等待，原记录和资源仍保留。`ControllerLaunchLedger.prepare`新增RUNNING检查，UNKNOWN不可迟到签发凭证。**UI成功回执已留证，但还未补一次接手后的独立持久化回读。**
- 最新主机检查通过：bootstrap315/loader15/component88/boundary117/manager341/startup8/pending148/preferences9/service16/typed45/mapping12；SDK **247源**编译通过，日志 `/private/tmp/tripoder-webview-services-host.log`。

### 当前真实阻塞与在制改动

0. **2026-10-17 09:30 更新（第71轮）**：自有 WebView fixture 已在设备通过：`webview-stamp-71` pending 阶段 93 项全过，target30/31 真实 WebView 渲染+JS+localStorage 成功。本轮补齐：派生 WindowContext、provider 加载器按 dex 路径认领接收器/回调、BIND_EXTERNAL_SERVICE→LONG 并走 int 隔离绑定、GMS 视为包世界外（null）、物理 getSystemAvailableFeatures、systemhealth/input/captioning/camera/sensor/clipboard/usb/bluetooth 服务、APPLICATION_RESTRICTIONS/PROXY_CHANGE(粘性+ProxyInfo)/HEADSET/USB/蓝牙状态/时区等受保护广播、guest setProcessStateSummary（仅逻辑保存，不覆盖所有权标记；fixture 断言已改为该不变量）。主机检查 manager policy 343/129。**尚未重建正式产品，尚未验证王者/微信。** 注意：主机测试 JVM 偶发挂起，用 `timeout` 并重定向 stdin；构建遇 ENOTEMPTY 先把 `android/runtime-probe/build/runtime-probe` 移到 /private/tmp。
1. **王者真实应用状态（2026-10-17 10:02，第82轮）**：干净重建后 pending 阶段（含自有WebView）通过，正式 control 62 项通过；均未覆盖王者业务。王者已越过协议页/权限页。修复了：`ActivityThread.getApplication()` 逻辑视图（CrashSight 反射拿到宿主 RuntimeApplication 导致宿主数据库/目录 EACCES，进而 UnityMain 在 libCrashSight `usleep` 里卡死触发 ANR），白名单 `FrameworkAccess.requireManagedHookTarget` 放行该方法；AudioManager.preDispatchKeyEvent 转发物理宿主。**现仍失败**：王者在启动约38秒后 `UnityMain` 在 `libunity.so` 内 `strlen(NULL)` SIGSEGV（`/private/tmp/h81-logcat.txt` 为完整logcat，崩溃前无Java日志；libunity 加壳，静态反汇编是乱码，只能动态定位）。崩溃前同进程日志里的候选项（未证实因果）：`DEVICE_PROTECTED_STORAGE_UNSUPPORTED`(HMSSDK)、`SERVICE_INTENT_SHAPE_UNSUPPORTED`、`ALARM_EXACT_PERMISSION_NOT_GRANTED`、引擎 `oat` 目录写 vdex 被拒、`libEGL`缓存目录 Permission denied。下一步：逐项按平台语义补齐并看 libunity 崩溃是否消失，不要用null兜底。
1b. **（旧）王者 WebView（真实应用）尚未验证。**39轮在协议后的权限说明页崩溃：`SYSTEM_SERVICE_UNSUPPORTED:webviewupdate`。已新增 `RuntimeWebView.java`、`RuntimeWebViewServices.java`：只承认系统选定provider，核对版本/签名/代码路径/声明组件，安装+逻辑进程稳定data suffix；真实包/资源查询、跨包use通知、受限DEX加载上报；真实provider服务绑定/隔离renderer/rebind/unbind。GuestContext显式提供Android用户状态服务。**这些是正在开发的能力，不是已验收成品；接手要审查语义和生命周期。**
2. 自有WebView fixture：`UiWebViewChecks.java`用真实WebView加载内联页面，期望DOM/JS/localStorage成功，挂在PendingActivity开头。41失败notifyPackageUse；42失败notifyDexLoad；43失败componentEnabled；44失败installerPackageName；46失败USER_SERVICE和provider getServiceInfo。上述入口已按实际平台语义补齐。
3. **最新49轮两个独立首因**：主线程 `WINDOW_CONTEXT_UNSUPPORTED`，来自 `GuestContext.createWindowContext` → Chromium `DisplayAndroidManager`；后台 `CrAsyncTask #3`致命 `SYSTEM_BROADCAST_POLICY_UNSUPPORTED:android.intent.action.APPLICATION_RESTRICTIONS_CHANGED`。随后probe清理的 `DeadObjectException` 是进程死亡的次生错误，不能当首因。完整结果/崩溃：`webview-renderer-services-49/device-result.json`、`owned-crash.txt`。尚未实现这两个能力。
4. **微信登录页缺失View**：07:06真实 `MobileInputUI` → `k3.P7` → `k3.L7`，`getValue(...) must not be null`。已反汇编定位懒加载 `findViewById(0x7f0a02a3)`，原XML确实存在该 `CheckBoxTextLayout`：layout `0x7f0e1628`（c1z）include `0x7f0e1436`（eqa）。**根因尚未确定，未修改微信APK或用null兜底。**证据 `wechat-user-login-crash-37/`；下一步重现时实时保留TripoderViews/初始化日志，不能只凭静态XML猜测。
5. **性能仍失败**：`performance-fixed-mode-40`完整1对预热+5对测量，开启/关闭命令均exit0，`disableCommandAcknowledged=true`。baselineNoiseExceedsFivePercent=true、medianRegressionExceedsFivePercent=true、frequencyPolicyEnvelopeUnchanged=false；C均值中位数+21.09%，JNI−3.55%、外部JNI−91.08%、规则−5.88%。CPU5上限仍2.7648→2.1888GHz变化，不能宣称性能达标，也不能直接把C差异归因为代码。47轮确认 `sustainedPerformanceSupported=false`、省电模式false、thermalStatus0。不要把thermalservice缓存温度当实时HAL温度。
6. **历史ART根因仍未证明**：`TP_MANAGED_ENTRY_OWNERSHIP`原失败BuildID `93c7fe2591d4888a560c674d61d219a2013fcabb`未找到，原tombstone缺精确ArtMethod快照。739项/640次切换/688实际GC/33组root移动及真实微信ART/JNI通过，都不是历史根因证明。入口准备已改一致快照，所有guard保留。不要重复已排除的静态JNI类初始化/boot stub假设；此前visibility实测quick==genericJNI未变化。

### 制品与运行状态

- **无运行中的构建、validator、benchmark或子agent。**本轮未spawn agent。最后设备运行的是49轮自有探针；正式产品为47/48安装版本。
- Runtime当前APK `f59fa8940d2ca6a22e5b6c353dce25964560889d2ed96d8b850d0b97f0a2de37`，receipt已核对现有输入无变化，包含最新WebViewServices；设备49轮失败。
- 正式Engine `b9ccf64fabf8e3fc2af17185b96931086d7d93d52a5eb5e87eeca1cf6abb3cf1`，Workbench `251f55cdd80ac0861415e883c73d942cecca5d1408978be0af8ab5236337359c`，control-test `23a68ac3ab8f47a8d498503a0f76a157870dcc3dd2a287934a6bd606fe0aa3eb`。**当前已过期**：之后新增/改了WebViewServices、GuestContext、RuntimePackages、RuntimeWebView、fixture输入及Workbench设置页恢复动作前移。
- 独立Probe `6a2be9bf873224b3fc82859d2c0aee5473ee04b13f9ba14bb15a363c08319551`，Native SO `8267eae3eb0e06ba10d871e6e9ee2f3274dc759bcd5be5ad6c082f6302d0c770`。**Probe也已过期**，a-upgrade.apk与PackageFrameworkAccess.java输入改变。40轮使用它对应的冻结制品，原样本有效，但不能当最新完整源码验收。
- 真正旧核心性能基线 `/private/tmp/tripoder-native-baseline-src` 保留；严禁覆盖其生产核心。对照协议四份benchmark源码必须一致。没有因失败修改阈值或过滤尾部样本。
- 构建偶尔遇Finder写入`.DS_Store`导致generated目录ENOTEMPTY；本轮已将残留生成目录归档 `/private/tmp/tripoder-runtime-intermediate-d7145445-8662-4e77-95b0-073155b53cea` 后重建，未删源码/客体数据。

### 正式目标身份

正式空间 `p6-20261014`，不要与独立RuntimeProbe的旧target-*空间混用。

- 微信：project `7703bdbe-88a3-4ad9-9f17-71aecb68d6ff`，binding `50adface-8c77-4134-9971-768d53a1e067`，install `a776a2fe-6b4d-42bd-9155-f25ed20b02e8`；原APK SHA `f2a725bc04302ad218a563533ae79356e66b64aa711b275bc919a8d432f7e15d`。
- 王者：project `be87df1a-ea8c-45c0-8834-507eaa7d87cb`，binding `3e46387c-b771-45c2-a581-2d2e223e5252`，install `5e3947bf-973d-4126-8fea-8f4a5e3a026e`；原APK SHA `6841b1eae92b3d87b00e986f86086cbe436bcb52e4b6a3c6829f368714538384`，版本12.1.1.1，singleTask入口。
- 原APK本地：`android/runtime-probe/build/targets/wechat.apk`、`honor.apk`。真实Native标量目标均为无参 `__xlogger_Level_impl():int32`，微信libwechatxlog.so，王者libmarsxlog.so；王者尚无实际Hook通过证据。不可把JNI指针参数伪装成整数ABI。

### 接手顺序与命令

1. 阅读49轮两个首因，审查已在制WebView依赖模型；实现正确的派生WindowContext及真实系统限制广播语义，继续自有WebView/renderer/JS/存储验证。不要返回空对象、跳过页面或放开通用跨包访问。
2. 主机检查及Runtime设备验证通过后重建正式产品。回读48的UNKNOWN回执，确认原operation仍UNKNOWN且UI等待已结束；用正式UI推进王者后续页面/实际模块Hook。
3. 实时采集微信MobileInputUI视图树，定位真实缺失View原因并修复；再接登录/业务/后台与冷恢复。
4. 重跑与当前制品绑定的P4、正式control/hook-ui/Agent；性能5%与历史ART根因仍单独保持未闭合。

~~~sh
# Runtime构建；正式产品换成 build-workbench.py（先完成Runtime，再构建正式产品）
rtk proxy python3 android/scripts/build-runtime-probe.py --sdk /opt/homebrew/share/android-commandlinetools --platform android-37.0 --build-tools 37.0.0 --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home --ndk android/vm-probe/build/native/tools/android-ndk-r30 --platform-libc android/runtime-probe/build/device-libc.so --platform-art android/self-hook-probe/build/device-libart.so --platform-linker android/self-hook-probe/build/device-linker64
# 自有WebView在pending阶段；每次新证据目录，失败不得覆盖
rtk proxy python3 android/scripts/validate-runtime-probe.py --serial d861a4fa --install --phase pending --evidence docs/evidence/real-app-goal-2026-10-17/<fresh>
# 正式目标；王者后续模块实际加载后才加 --native-hook
rtk proxy python3 android/scripts/validate-workbench-control.py --serial d861a4fa --install --phase target --target honor --evidence docs/evidence/real-app-goal-2026-10-17/<fresh>
# 微信真实组合Hook
rtk proxy python3 android/scripts/validate-workbench-control.py --serial d861a4fa --phase target --target wechat --native-hook --managed-jni-hook --evidence docs/evidence/real-app-goal-2026-10-17/<fresh>
# 只读pending检查（当前pending已结束，不会返回旧回执，要补精确历史回读）
rtk proxy python3 android/scripts/validate-workbench-control.py --serial d861a4fa --phase resources --inspect-pending --evidence docs/evidence/real-app-goal-2026-10-17/<fresh>
~~~

临时辅助文件：`/private/tmp/tripoder-ui.py`每次读取真实uiautomator树后按唯一可见文案点击；`/private/tmp/tripoder-extract-dex-class.py`从原APK提取指定类的dexdump；`/private/tmp/ChromiumApkInfo.java`和`WebViewChromiumFactoryProvider.java`为官方上游源码参考（main，不保证与设备精确版本一致）。用户全权操作授权已具备，不要重复问能否点击普通页面。

证据根 `docs/evidence/real-app-goal-2026-10-17/`；早期Native根因证据 `docs/evidence/native-core-rootcause-2026-10-17/`。前期已修复进程内存API、偏好存储中断语义、MIUI控制端冻结、新服务请求误用旧Hook恢复屏障、跨代际/singleTask空任务回收等，详见各证据及 [Native续接](docs/self-hook-native-followup-2026-10-17.md)。

**下方第1至6节仅保留H37历史，不代表当前状态。**

## 1. 最终目标与完成边界

微信 `com.tencent.mm` 和王者荣耀 `com.tencent.tmgp.sgame` 在 Tripoder 内**实际正常运行**，且正常 Java/Native Hook 的安装、调用、停用、卸载不导致 panic、Java/Native crash 或 ANR。

- 必须真实 UI、登录、后台/冷恢复和业务链验收。fixture、Application ready、Activity 创建、15/30 秒存活都不能替代业务完成。
- 禁止宿主微信参与客体登录作为替代路径。
- **目标尚未完成。** 两个应用都没有达到稳定首屏。

## 2. 当前制品与现场（H37）

- Runtime APK：`2003244000e98e9e53398c8b3c6afcab796f6de9eea6953f52df0d833fc42a6d`，已构建、安装、归档于 `docs/evidence/process-slot-ownership-h37/artifacts/20032440/`。473 个源输入复核 `changedInputs=[]`。
- Native SO：`a7ba136f…`，与 H36 GC 压力包相同。**Native 目录此后又有改动，该 SO 可能已过期，见第 5 节。**
- 设备 `d861a4fa`，Xiaomi/madrid Android17/API37/arm64/4KiB，Runtime UID 10409。
- 正式 Engine/Workbench/control-test 构建已过期，不得称当前产品已验收。
- **基线检查点**：本地提交 `0cc4658`（2026-10-17，未 push）。包含源码、脚本、文档和 ≤256KB 的证据；**排除** `.codegraph/`、`context/`、`pairing.txt`、APK/SO/DEX 二进制和 >256KB 的 `docs/evidence` 文件（约 1749 个，仍在磁盘上，保持未跟踪）。该提交是改造前的归因点，不是验收结果；Native 目录当时已含另一个 agent 的进行中改动。此后用 `git diff 0cc4658` 区分两条线的改动。不要清理或还原工作区。

## 3. 已验收（各自绑定对应构建，不跨构建追认）

| 范围 | 结论 | 证据目录（`docs/evidence/` 下） |
|---|---|---|
| GC root 首次枚举 | 三个独立冷进程，每轮 121 项通过 | `framework-gc-h36/gc-02..04-interleaved` |
| 进程槽与 Activity 资源分离 | 跨安装冷/热迟到入口 14 项通过 | `process-slot-ownership-h37/reuse-04` |
| 实际 SQL 所有权判定 | 33 项 host 检查通过 | `process-slot-ownership-h37/sql-policy.json` |
| 主机回归 | 304/15/85/117/331/8/45/12，SDK 230 | `process-slot-ownership-h37/host-04.log` |
| 历史（H35 及更早） | Job 生命周期/冷恢复 15、调度 69、完整 P3 358、P4 105 等 | 见 H35 交接，均为旧构建 |

固定适配：25 项真实安装、2177 个 managed 固定绑定，guest 首次执行前完成。存储 Hook 已改为自研核心，不依赖近地址跳转页。

## 4. 阻塞与待办（按顺序）

### 4.1 Context 回归停止点（最先处理）
- `process-slot-ownership-h37/provider-context`（run `provider-identity-104db46a92d54076`，APK 20032440）：整轮在第 19 项 `application-start-failure-is-explicit` 失败，位置 `P2ProbeActivity.testPackageResources`。后面 26 项 PackageContext 检查未执行；无 Java/Native fatal，ANR=false。
- `rejected(...)` 辅助方法只保留匹配 bool，**原始异常没写入报告**。首要假设是第四个并发进程（`:historyfail`）容量不足，**未确认**。先保存原始异常和逐槽原因，再决定是否重排或拆出定向验收。不得降低断言或把整轮改绿。
- **2026-10-17 核查**：失败那轮的 `device-result.json` 里该项 `observed` 确实只是 `true`，堆栈指向 `P2ProbeActivity.java:412`。但**当前探针源码（20:53 修改，晚于失败运行 20:29）已经改了**：第 406–407 行用 `rejection(...)`，会记录异常类、消息和容量快照，并且 `:historyfail` 已前移为 `testPackageResources` 的第一步，早于 peer 和 other-space 进程。因此**源码侧的诊断和重排已完成，缺的是用新构建在设备上重跑**，不需要再改这部分源码。
- `android/runtime-probe/build/` 现有构建时间 23:14，`runtime-probe.apk` SHA `1d067249…`，与 H37 记录的 `20032440…` **不同**；构建者和是否已安装未知（`adb` 5037 端口被其它进程占用，未触碰设备）。重跑前须先确认设备负责人，并以该构建自己的 receipt 为准。
- 遗留一个未派发票据 `f22fd4de-81bd-426d-8ae2-fb6c5256021d`（generation `c17d52dd…`，space `provider-identity-104db46a92d54076-provider-identity`）：DEAD、closeRequested=0、PREPARED、无 entryFence，仍占 D 槽执行权。须按精确自有测试关闭协议处理，保留 UNKNOWN/租约，不直接改库。应把 Activity 准备推迟到依赖就绪后，避免前置失败继续产生未派发票据。

### 4.2 微信
- 复用现有空间 `target-3be31e2dd7434859-wechat`，不新建空间、不占用新 affinity。
- 已越过：Package Context、telephony 只读查询（H36）。已进入真实 UI `LauncherUI`/`WeChatSplashActivity`。
- **H36 记录的下一个首因**：`PRODUCT_MEMBER_MISSING: Context$BindServiceFlags.getValue()J`。设备上该方法不是声明方法。**源码里 `ComponentFrameworkAccess.java:24` 仍用 `getValue()J`，未见修复；H37 之后也未复测微信，不能假定已闭合。**
  下一步：枚举 owner 17 的实际成员/字段，找提取 long 的真实途径，改访问路径，补公共 SDK fixture 覆盖 `bindService(BindServiceFlags)`。不加 flag、不返回 null、不发往宿主。
- 最近一次微信测试（`framework-gc-h36/wechat-context-01`）在 Matrix 绑定额外服务时 `PROCESS_CAPACITY_EXHAUSTED` 并自行 `exit(1)`。
- 之后逐一修复实际首个启动错误；不把死亡后的恢复错误当首次根因。

### 4.3 王者荣耀
- 当前已知阻塞：WorkManager 创建 PendingIntent 崩溃。王者 targetSdk=30，但平台读取宿主进程的 `Compatibility.isChangeEnabled(160794467)`，补 flag 或返回 null 都不成立。
- 需要自研广播 PendingIntent/Alarm 真实路由：持久 creator 独立于 session、`NO_CREATE` 查真实系统、mutable fill-in 不改物理路由、真实 Alarm 与冷投递验收。见 [docs/runtime-pending-intents-plan.md](docs/runtime-pending-intents-plan.md)。
- 之后才是 Unity/Native/图形启动（`libunity`、`libil2cpp`、`libGameCore` 及 `libtprt`/`libpandora`/`libpuerts` 依赖）、首屏、登录后资源加载、输入、性能。见 [docs/hok-compatibility.md](docs/hok-compatibility.md)。

### 4.4 全量回归与正式产品
- 当前源 P4（普通 Java/Native/mixed 安装、调用、停用、卸载、退出归因）与必要生命周期回归。
- 正式 Engine/Workbench 重建与控制验收（`control`、`hook-ui`、Agent `normal`/`disconnect`）。
- 与第 5 节的 Native 验收合并为**同一批**，不单独重复。

### 4.5 两应用最终验收
- 微信/王者 UI、登录、后台/冷恢复、业务场景，确认正常 Hook 无 panic/crash/ANR。

### 长期开放项（不阻塞上面顺序，但会反复咬人）
- **item-3**：未附着的 UNKNOWN ticket（PREPARED/CLAIMED、attach 前进程死亡、无 taskId）永久占槽。退休需要真实 Android task 证据，当前无公共 API；不清库、不扩容、不改写 UNKNOWN。每次完整 P2 和微信反复 attach 前死亡都会累积。
- **MIUI 自启动**：服务重启被小米策略拒绝，plain adb 无法授予，需设备端手动开启。非代码缺陷。
- Job authority 实际死亡恢复、取消 UNKNOWN 收口、组件/affinity 资源真正退休：未完成。

## 5. 并行工作：动态 Native 导出 Hook 改造（另一个 agent）

2026-10-17 本轮实现与逐构建证据见 [Native 改造报告](docs/self-hook-native-upgrade-2026-10-17.md) 和 [证据索引](docs/evidence/native-core-upgrade-2026-10-16/README.md)。本轮业务范围已由用户确认收口于现有 P4 自有应用 Native/mixed；不将其计为微信/王者实际业务完成。性能无退化与 S6 安全回收不能据功能用例通过而宣告完成。

续接：[CPU/ART 定向复现](docs/self-hook-native-followup-2026-10-17.md) 已纠正 CPU 7 选择假设，CPU 5 两组五轮对照完成；739 项定向检查通过，含 640 次目标切换和 693 次实际 GC。生产 guard 未放宽。JNI 尾延迟噪声与历史 ART 失败的确定根因仍未闭合。

范围：动态 Native 导出函数 Hook。沿用 `RuntimeNativeHookBackend → NativeHook → tp_native_*`；不改 ART 方法 Hook、libc 路径策略、linker 的业务语义；单一后端、无 fallback、无 RWX。

| 阶段 | 内容 | 与本任务关系 |
|---|---|---|
| S0 P0 | 固定基线与证据格式、覆盖矩阵 | 先做 |
| S1 P0 | 生命周期与入口窗口（在途计数、迟到入口、卸载/同地址重载） | 对应“正常 Hook 不崩溃” |
| S2 P0 | 准备/发布失败分类与资源账目 | 同上 |
| S3 P1 | 近地址分配与指令准入 | **须由真实拒绝样本驱动；动共享 `near_address.c`/`a64_relocate.c` 会影响固定 libc 和 ART，需联合回归** |
| S4 P1 | 实测热点优化 | 与最终目标无关，后置 |
| S5 P1 | 同源码同构建的产品链验收 | 与 4.4 合并 |
| S6 P2 | 已发布资源安全回收 | 单独立项 |

协调约定：
- 对方负责 `android/self-hook/native/**`、`NativeHook.java`、`RuntimeNativeHookBackend.java` 及 Native 探针；本线负责 Context、微信、王者等 Java 层 Runtime 文件。
- **避免同时改** `ActivityLedger`、`RuntimeReceivers`、`RuntimeBroadcastBroker`、`RuntimeSystemBroadcasts`、`RuntimeSelfHook*Managers*`；动之前先确认对方是否在改。
- 最近两小时内 `native_hook.c`、`near_address.c`、`scalar_inbound*`、`linker_notifier.*`、`module_lifetime.c` 等已被修改。**这些改动是否已包含在 APK 20032440 中未知**，S0 须对整个工作区固定源码摘要。
- 一台设备同一时间只有一个安装/启动负责人；验收期间冻结构建输入；失败证据不覆盖。
- S3 之前先拿到微信/王者稳定启动证据作回归基线；目标库中被拒绝的真实函数前导反馈给 S3。

## 6. 续接命令

所有 shell 以 `rtk` 开头。一次只有一个设备负责人；证据目录必须新建且唯一，不覆盖失败目录。

~~~sh
# 微信复测（复用已核验安装）
rtk proxy python3 android/scripts/validate-runtime-probe.py --serial d861a4fa --evidence docs/evidence/process-slot-ownership-h37/<new-wechat-dir> --phase target --target-package com.tencent.mm --target-space target-3be31e2dd7434859-wechat
# 当前源 P4
rtk proxy python3 android/scripts/validate-runtime-probe.py --serial d861a4fa --evidence docs/evidence/process-slot-ownership-h37/<new-p4-dir> --phase p4
~~~

其余阶段（`p0`/`p1`/`p2`/`p3`/`packages`/`creation`/`failure`/`entry`/`notification` 等）同模板，替换 `--phase`。正式控制：

~~~sh
rtk proxy python3 android/scripts/validate-workbench-control.py --serial d861a4fa --evidence <new-dir> --phase control --install
rtk proxy python3 android/scripts/validate-workbench-control.py --serial d861a4fa --evidence <new-dir> --phase hook-ui
rtk proxy python3 android/scripts/validate-agent-control.py --serial d861a4fa --evidence <new-dir> --phase normal
rtk proxy python3 android/scripts/validate-agent-control.py --serial d861a4fa --evidence <new-dir> --phase disconnect
~~~

重建（Runtime 与正式产品使用同一组显式参数；正式产品脚本为 `build-workbench.py`）：

~~~sh
rtk proxy python3 android/scripts/build-runtime-probe.py \
  --sdk /opt/homebrew/share/android-commandlinetools \
  --platform android-37.0 --build-tools 37.0.0 \
  --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
  --ndk android/vm-probe/build/native/tools/android-ndk-r30 \
  --platform-libc android/runtime-probe/build/device-libc.so \
  --platform-art android/self-hook-probe/build/device-libart.so \
  --platform-linker android/self-hook-probe/build/device-linker64
~~~

Runtime 重建会重编 fixture，时间戳/SHA 变化，正式包也要跟着绑定新 fixture；不安装旧包冒充当前源。

主机关键检查：

~~~sh
rtk proxy python3 android/self-hook/tests/check_fixed_libc_admission.py
rtk proxy python3 android/runtime/self-hook-tests/run.py --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home --android-jar /opt/homebrew/share/android-commandlinetools/platforms/android-37.0/android.jar --apksig-jar /opt/homebrew/share/android-commandlinetools/build-tools/37.0.0/lib/apksigner.jar
rtk proxy node android/runtime/script-tests/run.mjs
~~~

设备故障先读终态 result/error/原始 owned-crash/bootstrap-log，再修根因，不放宽断言。已过且无相关改动的测试不为凑数量重跑。

## 7. 纪律与边界

- 仅自有 APK 做 P0–P4 类验收；微信/王者验证已获用户明确授权（覆盖旧阶段“不运行第三方测试”的限制）。其它安全/数据保留边界继续有效。
- 不使用 root、`run-as`；不清库/清缓存/删除 UNKNOWN 或旧业务数据；不扩槽绕过；不加 fallback。
- 已授权设备 `d861a4fa`，锁定 Xiaomi Android17/API37/arm64/4096 页固件。
- 正式 Engine/Workbench 使用独立 UID；guest 与 Engine 同 UID，不宣称内核级隔离、通用 APK 或 P6 完成。
- 单个源文件超过 2000 行按职责拆分；以第一性原理定位根因。
- CodeGraph MCP 未暴露，`.codegraph/` CLI 索引可用但有滞后。
- 不要把某一阶段通过后把 `fullP2Accepted`/`fullP5Accepted`/P6 等标志机械改为 true。

## 8. 资料入口

- [当前产品证据索引](docs/evidence/self-hook-product-2026-10-16/README.md)
- [启动链](docs/self-hook-runtime-bootstrap.md)、[存储绝对入口](docs/self-hook-fixed-libc.md)、[迁移清单](docs/self-hook-adapters.md)
- [验收矩阵](docs/self-hook-acceptance-plan-2026-10-16.md)（A1 以已 236 通过为准，矩阵旧快照待更新）
- [共享 Runtime 构建说明](android/runtime/README.md)
- [Job 实施计划](docs/jobscheduler-implementation-plan.md)、[H35 Job 交接](docs/jobscheduler-handoff-2026-10-16-h35.md)
