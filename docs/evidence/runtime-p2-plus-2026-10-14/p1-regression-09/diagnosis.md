# 报告权限失败，未验收

runId `p1-e93dc19c5ae641a5`，APK `c466dd842fe249938a34c3e6ed492e868037e84e4054d4a9eced34658b19b966`。

原子发布后 final 报告大小为 61680 字节，模式 `0600`、owner `u0_a398`、group `ext_data_rw`。采集器执行 `adb shell cat` 返回 `Permission denied`。这是新的报告发布器选错模式，不能认定设备断言通过。明确诊断后，仅向本任务验证器 PID 53833 发送 SIGINT，执行 finally 保存原始观察与构建凭据；没有删除或改写设备报告。

修正：自研验收报告目录允许已授权 adb 采集器读取，报告使用 0660，业务/账户数据不在此目录发布。验证器只等待 `No such file or directory`，其他读取错误立即失败，不继续盲等。该失败目录不覆盖，下一构建重新验收。
