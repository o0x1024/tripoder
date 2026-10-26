# 通用文件 pull

实现日期：2026-10-22。手机导出工具由同一 MCP 契约发布给 HTTP 和 stdio；客户端在本地下载字节，模型只接收状态、路径和摘要。实际权限由 Tripoder 宿主 UID 的文件系统访问决定，不使用 adb、root、shell 或额外权限申请。

## 文件来源

```json
{"kind":"path","path":"/sdcard/Download/example"}
```

路径必须是手机绝对路径，可以是普通文件或目录。Android scoped storage 和现有权限仍然生效；SAF 文档 URI 不属于路径接口。目录以清单逐文件复制，保留层级和空目录，客户端不需要 ZIP 解包。

```json
{"kind":"guest","target":{"space":"SPACE","package":"PACKAGE","installGeneration":"GENERATION"},"root":"data","path":"files"}
```

`root` 为 `data` 或 `apk`；`path` 是根内相对路径，空字符串导出整个根。data 包括该安装的 files、databases、shared_prefs、external-files、external-cache 等已存在目录。authority 校验精确安装代次；不要求启动客体或自动创建项目。guest 链接不能越出选定源子树。

```json
{"kind":"artifact","processGeneration":"PROCESS","artifactId":"ARTIFACT"}
```

引用来自 `artifacts_list`，只接收 COMPLETE 产物，并核对原产物大小和 SHA。DEX 场景为 `dex_snapshot → artifacts_list → file_pull`，无需获取内部物理路径。进程退出不影响已完成产物。

## 手机工具

| 工具 | 入参摘要 |
| --- | --- |
| file_query(action=stat) | source |
| file_query(action=list) | source、cursor=0、limit=1..128 |
| file_export(action=begin) | operationId、source、followSymlinks=false |
| file_query(action=export_status) | exportId |
| file_query(action=manifest) | exportId、cursor=0、limit=1..128 |
| file_query(action=read) | exportId、entryId、offset、length=1..262144 |
| file_export(action=cancel / release) | 新 operationId、exportId |

`begin` 创建后台任务，exportId 等于创建时的 operationId。同 ID、同内容查询原任务，改变内容拒绝。PREPARING 阶段返回进度；只有 READY 可以读取清单和文件。分页结束为 nextCursor=-1，清单根条目的 path=""。元数据页另外受 UTF-8 字节预算限制，返回条目可能少于 limit。

目录不是事务级快照；复制期间发现源变化、无法读取子项、特殊文件、配额耗尽或链接循环时，整个任务失败并释放暂存，不静默跳过。followSymlinks=false 拒绝递归遇到的链接；顶层 `/sdcard` 等显式路径别名正常解析。true 显式允许跟随，但仍检查循环和 guest 边界。导出区不能包含自身。零长度文件支持；可读、零长度元数据的伪文件按有界流读取，仍不承诺原子一致性。

上限：单文件 1 GiB、单任务和全局暂存 4 GiB、10000 条目、深度 64；最多两个准备任务，不排队。READY 导出最后访问超过 24 小时后回收；可主动 release。准备中应用进程退出，重启后标为 INTERRUPTED 并清理半成品，不重新读取可能变化的源。原 operation_get 回执与导出状态可查询。网络断开不等于准备任务已取消。

## stdio 本地下载

```json
{"action":"start","operationId":"PULL_UUID","source":{"kind":"path","path":"/sdcard/Download/example"},"destination":"/absolute/computer/path/example","followSymlinks":false}
```

调用本地 `file_pull(action=start)` 后返回任务状态；用 `file_pull_status({operationId})` 查询，`file_pull({action:"cancel",operationId})` 暂停。相同参数再次 file_pull(action=start) 续传；同一 ID 改变参数拒绝。适配器必须保持运行；退出会暂停下载。

命令行下载也复用同一接收器：

```sh
node tools/file-pull.mjs /private/connection.json PULL_UUID '{"kind":"path","path":"/sdcard/Download/example"}' /absolute/computer/path/example
```

Ctrl-C 暂停；保留 .part 和手机快照后，用相同命令续传。连接配置只包含HTTP端点，不包含证书或私钥。退出期间服务器端点或来源身份变化时拒绝错误续传。

## MCP 客户端接入与执行位置

文件接收器只属于 Tripoder 的电脑侧 MCP 服务。Sentinel 与其他客户端都通过标准 MCP 工具调用它，不需要客户端原生下载工具或专用结果组件。

在 Sentinel 中连接电脑侧 stdio 服务，在 Profile 和会话工具配置中显式选择该连接下的 `file_pull`、`file_pull_status`。只连接手机 HTTP 时，只有 `file_export` / `file_query`，不能直接写入客户端电脑。两种入口不自动切换。

`destination` 必须是运行 MCP 服务的电脑上的绝对路径；若服务部署在另一台机器或容器内，文件就保存在那台机器或容器内，不自动映射到 Sentinel 的工作目录。

下载结果只包含状态、字节进度、最终路径、manifest 路径及 SHA-256。单文件返回文件 `sha256`，目录每文件的哈希在 manifest 内，`manifestSha256` 固定清单字节。STARTED/PREPARING/DOWNLOADING 不是完成，只有 COMPLETE 表示所有文件校验并提交。FAILED 查询返回 MCP `isError=true` 与 `ok=false`；取消仅暂停正在运行的任务。

MCP 服务重启后内存任务列表为空。用同一 `operationId`、`source`、`destination`、`followSymlinks` 再次 `file_pull(action=start)`，从本地私有暂存和同一手机快照恢复；不会重新准备可能变化的源。已完成文件会重新核验。服务器端点、参数或已提交文件发生变化时明确拒绝。

默认拒绝已存在目标和同名 `.manifest.json`。先写私有 `.part`，验证每个文件的大小和 SHA 后再提交。目录旁生成 `example.manifest.json`，记录来源、相对路径和每文件 SHA。路径越界、符号链接落盘和大小写/Unicode 归一化冲突均拒绝。校验失败保留半成品，手机源文件不因下载或取消而删除。最多两个本地下载同时运行，超出直接返回 FILE_PULL_LOCAL_BUSY，不排队。

手机快照可用 file_export(action=release) 主动释放。电脑 `.part` 中保留恢复元数据；完成后可自行删除该目录。SQLite 一致性需要先停止写入或通过备份接口生成副本，pull 不自动停应用、不宣称已完成完整脱壳或 APK 重建。

## 主机验证

```sh
python3 android/workbench/test/run-mcp-tests.py
```

验证生产 Java HTTP/MCP/文件导出与 Node MCP 接收器，包括目录、分块、暂停恢复、服务器/来源身份变化、MCP 状态和错误语义。Workbench/Binder 控制层是主机适配器，不代表 Android 权限、真机或 Sentinel 实际模型会话；这些验收单独记录。

## DEX 分阶段验收

电脑侧 MCP `artifact_verify({operationId,destination})` 只读检查原下载产物。启动服务时可明确配置 `--dex-validator /absolute/path/to/dexdump`；模型不能指定任意可执行文件或命令。消费者只接收固定 `-f FILE` 参数，运行有时间和输出上限，结果仅返回退出码和输出哈希。

捕获、下载、格式、消费者可用性分别返回状态。文件下载 COMPLETE 仅表示下载完成；NOT_CHECKED 或 FAILED 均不能标记整个产物已验收。消费者解析通过也仅证明该消费者能解析文件，不代表 App 业务运行或全部方法重建。校验不修改原文件、不重算头部、不使用派生文件替换原样证据。
