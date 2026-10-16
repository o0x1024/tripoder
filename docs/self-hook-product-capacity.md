# 固定产品安装容量与成员清点

2026-10-16 当前容量为4096，实际Runtime固定绑定2177（其中manager1523），动态目标复用同一预算；H15完整4096容量已独立通过8192 enabled、4096 disabled、8192 original调用及132 writer事件/全部owner移动。新增1515个manager安装实测5.4199秒；完整Runtime单进程实测约11.3–14.9秒，实际长流程性能仍在核验。下方2048/1270/F1内容为2026-10-15阶段历史，最新架构与证据见 [自研启动链](self-hook-runtime-bootstrap.md)。


2026-10-15。用户已选择在客体首次执行前初始化、之后动态Hook。该选择已经确定，不再要求任意旧AOT帧迁移。本页记录后续产品接入的实际容量和固定成员基础，不代表产品已经切换。

## 由真实安装数量确定预算

现有六类系统服务有81项支持能力，但未知owned方法也必须被拦截并拒绝，因此只计算支持名单是不够的。固定设备上的Native注册表清点如下，未调用系统服务：

| 类 | 真实声明实例方法 |
| --- | ---: |
| ActivityManager | 177 |
| ConnectivityManager | 168 |
| TelephonyManager | 495 |
| AudioManager | 334 |
| BatteryManager | 8 |
| PowerManager | 88 |
| 总计 | 1270 |

这些数值来自完整固定ART声明方法数组；不是公开SDK列表。各组还保存构造器、static、native、abstract、跳过项及排序后的完整descriptor/access flags SHA。六组实例方法中native/abstract均为0；这本身不证明caller-sensitive等语义已获准Hook。

Native只扩展既有FrameworkAccess的boot类根与只读成员枚举。Application成员组仍是准确的7 owners/12 fields/9 methods；Manager清点和Service组是同一注册表中的独立语义组，不另建普通反射解析器。`requireManagedHookTarget`没有因清点结果而放开所有boot方法。固定清单见[manager-inventory.json](../android/self-hook/manager-inventory.json)。

## 同一进程预算

`tripoder_hook_limits.h`为C及汇编提供2048槽位上限，Java的`HookCapacity`在任何初始化或prepare前与已加载Native值核对。ManagedHook、HelperDex、原调用备份、每类ClassExt根数组、writer登记表、两个汇编leaf均使用该容量。不是只扩大Java数组。

2048槽容纳当前1270个manager声明，并留下778个槽位供其余基础适配和动态目标分配；这不是最终产品安装数量已经满足预算的承诺。最终完整安装计划必须在发布前检查总预算，任何超额明确拒绝。相同目标继续使用已有Handle；停用不释放进程生命周期资源。`remainingCapacity()`公开的是原有used计数，没有第二套持久配额或所有权表。

每类根数组仍使用全局slot位置，所有长度校验和分配同步为2048。大量根快照改为暂停前分配堆内存，格式化缓冲区按实际已用槽位计算，避免大C栈数组和原64KiB结果截断。预算范围保持显式且有界。

独立DEX oracle覆盖63、64、2047以及越界拒绝；LLVM核对两个leaf确实扫描2048槽。真机使用16个独立声明类、2048个不同方法，逐个验证激活/停用值和原调用次数，最后一槽接受真实ART writer事件；结果见[H4a证据](evidence/self-hook-h4a-2026-10-15/README.md)。

## Service固定成员组

新增F1的两个字段和一个方法：`Service.mActivityManager`、`Service.mToken`、`IActivityManager.stopServiceToken(ComponentName,IBinder,int)Z`。同一个FrameworkAccess固定注册表校验owner、字段类型、static属性和完整方法descriptor；不猜测其他签名。

`ServiceTokenAccess`一次准备两个class全局根及JNI field/method IDs。调用时使用JNI字段直接读取真实manager/token，在原线程调用一次原interface方法；boolean和int不转换，原异常保持，不以false吞掉失败。manager或token缺失时，在调用之前拒绝。准备失败不重试。

产品`RuntimeSelfHookFrameworkCalls.stopServiceToken`已直接委托此API，Framework准备阶段也加入它。构建/主机编译纳入该Java类；其余FrameworkCalls、真实Service入口安装及Engine最终切换仍未完成。不能把一个默认方法实现或非空installer视为整项SERVICES已安装。

自有设备夹具使用真实Service字段和真实固定interface元数据，manager为自有Proxy；23项检查覆盖6次调用、两个线程、极值、bool、原RemoteException身份、物理字段不变与缺失字段拒绝。未向真实系统Binder发送停止请求，真实Service生命周期仍须在产品自有组件验收。

## 下一步

补F2的Receiver/Provider/flags固定成员与F4/F5 manager构造/历史值，再实现使用完整固定清单的真实安装器。安装器必须在开放任何facade之前完成目标准入、全量prepare、激活和核验。随后接入Runtime的guest首次执行边界、控制/审批链，并验证启动成本和Java执行性能。当前全容量压力测试的总耗时包含2048次准备、调用、GC及停用，不能当作产品启动性能通过。
