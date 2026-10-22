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

Ctrl-C 暂停；保留 .part 和手机快照后，用相同命令续传。连接配置权限仍要求 0600。退出期间认证/证书/服务器身份变化时拒绝错误续传。

## Sentinel HTTP 本地下载

Sentinel 内置工具 `mcp_file_pull` 调用已连接服务器，不需要把 Token 再写入脚本或传给模型：

```json
{"action":"start","operation_id":"PULL_UUID","server_name":"tripoder-http","source":{"kind":"path","path":"/sdcard/Download/example"},"destination":"downloads/example","follow_symlinks":false}
```

用相同 operation_id、server_name 和 action=status 查询；action=cancel 暂停；action=resume 携带原 source、destination 和 follow_symlinks 恢复。相对路径基于当前工作目录。Docker 只能下载到已有 host bind mount 的工作目录；不伪称手机路径可在电脑或容器中使用。结果包含宿主 destination、runtimePath 和 runtime 元数据。

默认拒绝已存在目标和同名 `.manifest.json`。先写私有 `.part`，验证每个文件的大小和 SHA 后再提交。目录旁生成 `example.manifest.json`，记录来源、相对路径和每文件 SHA。路径越界、符号链接落盘和大小写/Unicode 归一化冲突均拒绝。校验失败保留半成品，修复本地损坏后才能续传。手机源文件始终不因下载或取消而删除。

手机快照可用 file_export(action=release) 主动释放。电脑 `.part` 中保留恢复元数据；完成后可自行删除该目录。SQLite 一致性需要先停止写入或通过备份接口生成副本，pull 不自动停应用、不宣称已完成完整脱壳或 APK 重建。

## 主机验证

```sh
python3 android/workbench/test/run-mcp-tests.py
TRIPODER_SENTINEL_WORKSPACE=/absolute/sentinel-ai-pro python3 android/workbench/test/run-mcp-tests.py
```

第二条在生产 Java AgentHttp / AgentMcp / FileExports 主机 fixture 存活期间调用 Sentinel 原生接收器，验证带认证和受信证书的 HTTPS 目录下载。fixture 的 Workbench/Binder 控制层是主机适配器；不代表 Android 权限、真机、安装后的桌面或真实模型验收。

按本次要求不进行真机验收、不安装 APK、不重启或修改现有桌面连接。源码修改需重建升级 Tripoder 与 Sentinel 后生效。
