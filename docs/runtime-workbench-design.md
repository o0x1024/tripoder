# P5 工作台接入契约

状态：最新生产 Hook 控制链通过 62 项检查，原生编辑、保存、人工部署、实际客体行为改变及停用恢复通过 11 项检查。分别见 `evidence/runtime-p2-plus-2026-10-14/workbench-hooks-control-01` 与 `workbench-hook-ui-02`；两者构建独立绑定，不相加为一次验收。完整 P5 尚未完成。用户已授权卸载旧工作台，设备上的 `dev.tripoder.hooklab` 已删除。因此不再等待旧签名或实施旧私有数据迁移；历史源码与验收记录保留。

## 可信控制端与客体引擎

工作台保存项目源码、授权与配对状态；引擎保存空间、安装、进程、组件租约、执行操作与运行证据。**2026-10-17 起两者合并为单一 APK `dev.tripoder.engine`（同一 UID）**：用户认为两个应用切换造成体验割裂，并明确接受客体（与引擎同 UID）可读取工作台私有数据。工作台 UI 在主进程，Broker/控制服务在 `:authority`，客体在 `:slotN`。2026-10-14 用户提出由 Sentinel 等外部 Agent 提供 AI，故暂缓内置模型/密钥接入，候选方案见 [外部 Agent 控制设计](external-agent-control-design.md)。现有 Runtime probe 是验收工具，同 UID 的测试控制协议不能直接开放给生产工作台。

复用 ProductUi、编辑器交互、AiClient、ApiKeyVault 与项目文件操作的已验证部分。生产构建明确列出源文件，不包含 GuestClient、GuestService、GameRuntime、旧 fixture 路由和隐式默认项目迁移。不能在连接新内核失败时重新创建旧运行器。

## 连接与所有者

控制服务与客体启动 Gateway 均不导出。`ControllerTrust` 只接受本包 UID 且调用进程名为主进程（UI）的连接；这不是对客体的安全边界（客体同 UID），只防止客体代码误取控制租约。

连接通过控制端提供的存活 Binder 建立 owner lease，固定实际 PID/UID 和连接代次。已有 owner 存活时拒绝第二个控制端；Binder 死亡后废弃整个连接端点，迟到请求不能因 PID 重用重新获得授权。控制端重连只查询已存操作与客体状态，不重放未知副作用。

2026-10-18：原先连接前有一次随机 challenge，用来证明存活 Binder 确实由发起连接的进程持有。这是跨 UID 时代的机制，已删除。同 UID 下任何证明都区分不了调用者，而 CONNECT 已按调用进程名做过准入。现在存活 Binder（`ControlWire.Owner`）只作为生命周期令牌。

2026-10-18：工作台不再全局只允许一个待确认操作。每个未确认操作单独持久记录、单独查询；同一目标的请求是否冲突由引擎判断。工作台只拦截同一项目或应用仍在 Binder 上提交时的重复提交。见 [current-architecture.md](current-architecture.md#操作模型)。

工作台页面直接 `startForegroundService` 并绑定 `:authority` 的 `RuntimeControllerService`，不再经过跳板 Activity。整个包只保留该服务的一条常驻通知，点击回到工作台。客体启动仍经过单次 capability 的 `RuntimeGuestGateway`，让客体以独立 task 根启动。

页面导航不拥有客体生命周期。应用级连接对象与 Authority 生命周期独立于 Activity；引擎在有运行会话时通过 Android 允许的前台服务和可见通知维持，不用一个页面的 onDestroy 关闭数据库并杀死全部客体。用户显式停止与系统杀进程各自保留原有执行契约。

## 文件、项目与启动

APK 导入沿用只读 FD + 名称/SHA 的安装 API，工作台不能指定引擎物理路径。生产控制命令采用允许列表，不暴露故障注入、直接 allocate、任意源路径迁移或原始数据库接口。大文件流式复制、校验和 Worker 调用必须在 Broker/数据库事务锁外；预约、安装代次比较与提交使用短事务，不能让一次大 APK 安装阻塞活跃客体回调。

项目文件属于工作台；项目绑定由 Authority 明确关联 space/package/installGeneration。新项目绑定不能复用“复制 legacy 数据”的迁移操作。升级导致绑定失效时需要显式确认新制品，不能自动沿用旧符号证据或脚本激活状态。数据保留由 dataId 规则决定，不由 UI 的项目目录推测。

所有客体物理 stub 继续 nonexported。跨 UID 启动走一个受信 Gateway：验证实际平台调用者、一次性启动能力和已接受操作，再启动真实组件；不能为了让按钮可用就导出整个 stub 池。Gateway 也不能凭调用方传入的 component/path/space 任意启动。

## UX 与验收

UI 分开显示“原始 APK 已导入”“制品已验证”“进程已运行”“组件已呈现”“脚本已确认激活”和“业务验收通过”。失败应展示可操作原因与对应操作 ID；UNKNOWN 不是失败后的自动重试入口。长安装/加载有阶段状态，导航不丢操作；证据内容不混入 API 密钥或账号数据。

验收：非主进程（含客体 `:slotN`）、旧连接和重复控制端均不能取得控制租约；工作台主进程被系统回收不结束客体；重新打开能查询相同操作。注意 `am force-stop`/`am instrument` 会结束整个包（含 `:authority` 与客体），跨 instrumentation 保留引擎状态的验收需重新设计。真实项目绑定、文件保存、草稿检查、人工部署与明确授权的自动部署均进入同一 Runtime 契约。P4 未确认的 Hook 不能显示为已生效，P6 未登录的应用不能显示为已完成验收。

## 当前构建与剩余接入

`build-workbench.py` 生成单一产品 APK `dev.tripoder.engine`（工作台 + 引擎），以及单独的 `dev.tripoder.controltest` instrumentation APK（targetPackage 为 `dev.tripoder.engine`）。工作台仅复用 ProductUi/UiInsets，产品包不包含旧 GuestClient、GuestService、GameRuntime 或 Runtime probe 控制器。

20 项控制基础门槛对应 status/catalog/createSpace/query/disconnect；UI 真实展示连接与持久空间，保存未确认操作 ID，重连查询原操作，不重放。源文件在 `android/control`、`android/engine`、`android/workbench`（同包编译）。工作台私有数据对客体不设防，这是有意选择。

install/bindProject/resolveProject 已通过 35 项产品控制真机检查。install 接收只读、普通、起始偏移为零的 FD 与名称/摘要；拒绝可写 FD、物理路径及未知字段，返回逻辑安装身份。新项目绑定复用 project_refs 表，schema 17 显式区分 DIRECT 与历史 MIGRATED。DIRECT 不复制旧运行数据或工作台源码；绑定当前安装代次与数据实例，升级统一使旧绑定失效。显式重绑必须比较旧 bindingOperation，不能用相同目标代次绕过绑定版本检查。

安装 prepare 阶段在 Authority 和缓存互斥锁之外复制、签名校验与完整复验。提交前再次比较同空间安装目录快照、当前安装代次和组件引用；并发目录变化明确失败，不自动重试。正在准备的 staging/artifact 路径显式保留，恢复清理不可删除；进程死亡后该内存保留自然失效，持久操作仍按 UNKNOWN/已提交回执恢复。原安装/升级/卸载及故障门槛继续验证。

APK 导入、项目界面、脚本本地保存与空闲引擎的控制端死亡保留已验证。历史 `workbench-launch-atomic-01` 通过 50 项控制检查，同构建 `workbench-launch-ui-02` 通过 6 项真实入口检查：工作台 PID 6404 被停止后，客体仍在引擎 UID 10400 / PID 13244 内递增计数并重建界面，重新打开工作台能查询启动完成回执。该计数器没有安装为 Android 系统应用。后续 ART 与 Hook 接入是不同构建，证据各自保留。

## Hook 所有权与原生部署

生产命令 `hookProcesses` 只返回当前项目绑定的实际运行进程；`hook` 同时比较项目、绑定操作、安装代次、进程代次及控制连接。进程上的观察/准备/激活状态由同一个项目持有。跨项目覆盖、持有 Hook 时重绑，以及新控制连接激活旧准备令牌均明确拒绝。连接结果未知时只允许查询对账；读取 Native 模块列表不能消除未知部署状态。

工作台以应用级内存持有准备令牌，页面切换不丢失；令牌不落盘，连接或进程改变时清除。源码保存与检查是两个动作，激活前必须匹配此次检查的源码。界面仅展示最近确认的状态、真实 PID、源码摘要、版本及命中计数；查询、参数版本比较、停用和显式放弃都经过同一控制 API。未知结果不重放。

`workbench-hook-ui-02` 原生 UiAutomation 操作真实页面及未修改自研 APK：工作台 UID 10401，客体 UID 10400 / PID 31514，真实 `formatCount(int)` 被替换后显示 `Hook 0 → Hook 1`，引擎回执 hits=2/errors=0；停用后再次显式启动显示原始 `1`，状态 IDLE。截图已逐张核对。第一轮 `workbench-hook-ui-01` 的错误入口文字与过早截图导致测试失败，仍保留，未算通过。

尚缺外部 Agent 控制与授权自动化接入、完整故障/预算验证与 P6 业务验收；内置 AI 接入暂缓。当前结果只证明上述自研计数器闭环。

## 应用与项目的一对多界面（2026-10-15）

应用表达安装对象，项目表达针对该应用的一份工作内容。界面按包名聚合应用，在应用下列出多个项目，并保留独立项目总览。项目运行空间由绑定明确指定；应用图标独立选择运行空间，同一空间的同一应用共享客体数据，脚本按项目保存。复用 `project_refs` 作为唯一绑定权威，不增加应用—项目映射表；应用导入不隐式创建项目，新建项目走显式 `bindProject` 操作。

首页采用三列深蓝应用网格与项目数，添加页使用来源标签、选中角标和固定底部安装按钮。引擎连接与空间管理进入设置。`appPresentation` 仅按空间、包名和安装代次读取已验证 APK 的资源，返回有界图标和名称，不载入客体代码。生产导入器支持本机 APK 集合的逐文件复制和只读 FD 提交，当前 UI 一次选一个应用。

最终构建 14 项原生检查及截图见 [应用与项目分离验收](evidence/app-project-library-2026-10-15/README.md)。真实第三方应用安装和业务运行不计入本轮验收。

## 应用图标的点击与长按（2026-10-15）

单击图标直接运行应用，不创建或选择项目。一个可用安装直接启动，多个空间由用户选择并记住；长按提供“进入项目空间”“选择运行空间”“删除应用”。默认空间失效后重新明确选择。

`prepareApplicationLaunch` 只接受空间、包名和安装代次，解析 APK 中实际启用、导出的 MAIN/LAUNCHER 入口；没有入口或入口不唯一时拒绝，不猜测任意 Activity。它复用现有启动准备、一次性能力、前台 Gateway、进程与 Activity 账本。无项目的启动记录将 project/bindingOperation 同时置空，消费能力时仍验证安装代次、控制连接和进程；项目启动保留额外的绑定校验。

删除确认明确包含所选空间的应用数据和进程。`uninstallApplication` 拒绝活动 Agent 会话，按安装代次关闭对应进程，所有对应进程确认退出后，核对 Gateway 能力、原始任务、所属安装及栈内组件，移除 Android 保留的对应任务，再执行未占用检查和事务删除。自动维护仍只回收空任务；显式卸载才允许移除已退出进程的保留任务记录。未知进程终止、未释放组件引用不能视为删除成功。操作在接收后可持续查询；提交结果未知时不重放。删除使该空间的项目绑定失效，项目和脚本保留；其他空间不变。应用网格只聚合实际安装，失效绑定仍可从项目总览进入。

最终同构建的 20 项应用操作检查和 14 项一对多界面回归见 [点击与长按验收](evidence/app-actions-2026-10-15/README.md)。原用户待确认记录保留，测试使用独立操作日志。
