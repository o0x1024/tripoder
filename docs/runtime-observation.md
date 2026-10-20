# 通用运行时观察与代码快照

Tripoder 提供通用对象与采集能力，Hook 脚本处理进程内条件与动作，Agent 组合工具完成分析。没有 `unpack_*` 或按应用名称选择的采集流程。

## 接入与对象

先调用 `runtime_capabilities` 和 `projects_list`。项目无需已运行；`targets_list` 仍列出实际运行进程。配置与对象引用均不属于身份认证。

新工具：

| 工具 | 作用 |
| --- | --- |
| `runtime_capabilities` | 声明已实现的观察、快照和格式范围 |
| `projects_list` | 列出已绑定安装的项目，包括未运行项目 |
| `probes_get` / `probes_configure` | 读取、CAS 更新新进程的探针配置 |
| `application_stop` | 停止准确项目安装的进程，保留数据与源码 |
| `process_start` | 使用准确 probeRevision 启动停止状态的项目 |
| `probe_status` / `probes_stop` | 查询和停用当前进程的加载器探针 |
| `classloaders_list` | 查询稳定的进程内加载器引用及发现来源 |
| `classloader_create` | 从已发现的客体 DEX 创建受控内存子加载器，不更换应用加载器、不初始化载荷类 |
| `dex_list` | 按加载器分页查询真实 ART DEX 对象 |
| `class_inspect` | 在指定加载器内查看类，不主动初始化类 |
| `runtime_events` | 按游标读取启动阶段、加载器、DEX 和产物事件 |
| `dex_snapshot` | 将指定 DEX 的代码快照提交给后台线程 |
| `artifacts_list` / `artifact_read` | 查询持久化采集回执、分块读取产物，支持原进程退出后读取 |

所有加载器和 DEX 引用均属于准确的 processGeneration。加载器 ID 在一次进程生命周期内保持稳定；新进程拒绝旧加载器 ID。`class_inspect` 返回 superclass、接口和实际定义加载器，便于沿继承链定位方法并识别父加载器委托。Java `hook_observe` selector 支持可选 `classLoaderId`，编译器的 `ctx.java.method(..., {classLoaderId})` 可继续选择同一对象。

## 启动前配置

`probes_get({project})` 返回当前 revision；首次为空。`probes_configure` 示例：

```json
{
  "operationId": "configure-example-1",
  "project": "PROJECT_ID",
  "expectedRevision": "",
  "configuration": {
    "enabled": true,
    "discoverLoaders": true,
    "captureDexOnLoad": true,
    "processName": "com.example.app",
    "maxDexBytes": 16777216,
    "maxTotalBytes": 67108864,
    "maxArtifacts": 32
  }
}
```

`processName` 为空时覆盖该安装后续创建的所有进程，否则准确匹配一个逻辑进程名。配置绑定项目 bindingOperation 和 installGeneration；过期绑定不会应用到后来安装。每个 slot 在分配时冻结配置，后台组件创建的进程也沿同一入口获取配置。

可选 `startupHook: {selectors, source}` 使用现有受限 TypeScript 编译器和 Java/Native Hook 后端，在客体 AppComponentFactory 和 Application 执行前观察、准备并激活脚本。脚本目标必须已经能通过客体加载器解析；尚未加载的动态类由 Agent 在发现后另行部署。编译或安装失败会使该次启动失败，不能报告探针已生效。

随后调用：

```json
{"operationId":"start-example-1","project":"PROJECT_ID","component":"","probeRevision":"RETURNED_REVISION"}
```

空 component 选择唯一 launcher。已存在的客体进程会明确拒绝，避免误称启动探针已应用；先用 application_stop 明确停止准确安装再启动。入口 dispatch 回执只说明提出了 Activity 启动请求，前台可见性通过 UI 工具另行验证。

配置只影响新进程。`probes_stop` 停用当前进程的加载器回调，已排队的写入会完成；用户 Hook 通过 `hook_disable` 单独停用。`hook_status.firstInvocation` 保留首个真实回调的进程、线程、运行阶段及单调时间；上下文仅采样一次，事件环覆盖不会丢失这条记录。底层 Hook 的备份和必要引用保留至进程退出；需要完全未布置探针的执行环境时启动新进程。需要后续进程也关闭探针时，再 CAS 配置 `enabled:false`。authority 重启清空配置，不自动重放启动脚本。

## 动态代码与产物

显式启用时观察客体所有者范围内的 BaseDexClassLoader 及 PathClassLoader、DexClassLoader、InMemoryDexClassLoader 的构造与 addDexPath，记录内存和文件 DEX 加载器；根加载器在安装探针后登记。使用内存 DEX 和文件 DEX 的独立加载器会得到不同引用。`dex_search_classes` 在观察器已创建时合并运行时 DEX 中的类名。

快照从保留的 ART DexFile 对象读取内存，不把 APK ZIP 中的原始 classes.dex 冒充运行时快照。读取通过内核自进程拷贝处理不可读或失效地址，结构校验后才生成产物；不提供用户指定任意裸地址的入口。产物目录由 authority 创建并返回该代次专用的目录 FD，worker 仅通过该 FD 原子写入产物，客体路径策略保持原规则。

`dex_snapshot` 和脚本 `ctx.code.snapshotDex(dexId)` 返回 artifactId，将拷贝、摘要和落盘交给有界后台队列。自动采集同样通过队列执行。脚本可以把 artifactId 通过 `ctx.debug.emitEvent` 返回给 Agent：

```typescript
// 在已经部署的 Hook 回调中；dexId 可作为脚本参数传入。
const capture = ctx.code.snapshotDex(params.dexId);
ctx.debug.emitEvent('code.capture', capture.artifactId);
```

`artifacts_list({processGeneration})` 查询已持久化的 CAPTURING、COMPLETE 或 FAILED 回执；尚未进入写线程的队列项可能暂未出现。只有 COMPLETE 产物可读取。`artifact_read` 每次读取 1–16384 字节，返回 base64、nextOffset、eof 和完整文件 SHA-256。Agent 拼接后重新计算摘要。完成产物独立于原 worker 生命周期；进程退出不会自动重试未完成采集。DEX 列表以 cursor/limit 分页；class_inspect 每次最多返回 64 个方法及字段，明确标记截断。

`classloader_create` 使用已发现的 dexId 和 parentClassLoaderId，遵循正常父加载器委托，返回稳定的新加载器引用。探针已启用时，origin=constructor 表示由真实构造回调首先登记；origin=command 表示命令自行登记。复制后的直接只读缓冲区、ART 元数据和观察引用保留至进程退出。该工具用于可控动态加载实验，不能把它的事件称为应用自然插件加载。

## 性能与边界

- 默认无配置：不安装加载器探针，不创建采集线程，不扫描运行时 DEX，不写快照；显式查询才建立观察器。
- thin 继续保留 JIT/AOT，不新增全局退优化。探针仅影响选择的进程及少量加载器入口；旧的编译或内联调用不承诺全部覆盖。
- 加载器上限 128、DEX 引用上限 512、事件环 256、写线程 1、待写队列 8。事件回执报告丢失和游标缺口，容量拒绝明确返回错误。
- 单 DEX、每进程采集与受控载入的输入字节总量、产物数量均受配置预算约束；输入预算不等于完整 RSS 上限。产物保留在宿主私有文件目录，每进程目录独立，尚未提供自动清理或产物删除工具。
- 当前采集器支持标准 DEX 035、037、038、039、040 的可识别内存布局，CompactDEX、DEX 041 容器、方法体重建与 SO 重建未实现。能力查询不代表所有壳都经过验收。
- 内存内容可并发改变。产物标记 `BEST_EFFORT_HEADER_STABLE`，不是原子快照，也不证明所有业务方法已经解密。

## 验证

```sh
node --test agent/agent.test.mjs agent/audit.test.mjs
npm test
node android/runtime/script-tests/run.mjs
python3 android/scripts/validate-agent-control.py --serial DEVICE_SERIAL --install --scenario code --evidence docs/evidence/NEW_DIRECTORY
```

真机夹具是独立自研 APK，启动阶段创建内存和文件 DEX 加载器。Agent 验证默认无探针、启动脚本实际命中、加载事件时序、稳定引用、动态方法 Hook、脚本触发快照、导出字节 SHA-256、停用和新进程引用失效。该验证不等于网易云或任意第三方壳的验收。

## 网易云真机验收

`agent/netease-acceptance.mjs` 使用网易云 9.6.05 的已核对 APK、真实 stdio MCP 和独立证据目录。通过实际 TinkerApplication.onCreate 命中、运行时 DEX、受控内存子加载器、字节快照及本地角度计算方法返回值修改完成验收。详见 [网易云验收](evidence/netease-mcp-2026-10-20-01/summary.json)。原始 DEX、反编译内容和完整 UI/RPC 保留在忽略的私有目录。
