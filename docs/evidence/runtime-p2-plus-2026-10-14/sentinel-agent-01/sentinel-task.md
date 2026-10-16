执行 Tripoder 自研计数器 MCP 验收，标识 control-9cabfcd024354de6。只使用 tripoder 的 MCP 工具，不访问文件、终端或其他应用数据。手机测试程序会配合审批准确源码、操作计数器和检查原始行为。

顺序：
1. session_get 与 process_get，确认 project=e23c5c44-cebd-4102-af76-7b06517efb32、pid=24276，不匹配立即停止。
2. hook_observe，operationId=control-9cabfcd024354de6-observe，selectors=[{"id":"counter","kind":"java","className":"dev.tripoder.previewguest.CounterActivity","methodName":"formatCount","argumentTypes":["int"]}]。
3. 使用返回 evidence.id 调用 hook_prepare，operationId=control-9cabfcd024354de6-prepare，source 必须为下方代码块的准确内容（保留末尾换行）。
4. 查询 session_get，直到 approvedSourceSha256 等于 prepare 返回的 sourceSha256；等待期间每次间隔几秒，最多 20 次。未批准不能 activate。
5. hook_activate，operationId=control-9cabfcd024354de6-activate，使用刚准备的 token 与源码 SHA。必须读回执 ok=true、state=ACTIVE。
6. 查询 hook_status，直到 hits>=2 且 errors=0。手机会实际点击；最多 20 次，不要过早停用。
7. hook_disable，operationId=control-9cabfcd024354de6-disable，revision 必须取当前 hook_status。确认 DISABLED；不要重新启动应用。手机测试程序将实际验证后续调用恢复原始计数，并撤销控制会话。
8. 此时结束工具调用，报告各操作 ID、命中数与停用结果。不要提前调用 session_close，以便手机完成原始行为验证；由手机测试程序执行撤销。工具外层成功不代表业务成功；如 UNKNOWN 只查原 operation_get，不更换 ID 重放。

准确 source（SHA-256 03dc989e34469e94818c3649fbdefda651b00a47455ce384511538aa10798026）：
```typescript
import { definePlan } from '@abox/hook';
export default definePlan({
  id: 'counter-mcp', target, params: {},
  setup(ctx) {
    ctx.java.method('dev.tripoder.previewguest.CounterActivity', 'formatCount', ['int'])
      .replace(({args}) => 'Hook ' + args[0]);
  }
});
```
