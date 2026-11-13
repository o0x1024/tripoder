# 第 11 批（v73）：package-endpoint 家族收口 6 处

> 台账第 10 项的第二段。上一段（v72）把 `receiver-pointer-stack` 收口到 metric=61；本段处理
> `RuntimePackages.java`（IPackageManager endpoint + `Context.getPackageManager()` 门面）里的抛点。
> 与 v72 同一条判据：**平台在这个调用点会失败吗？不会失败就不能抛。**

## 1. 判据与「谁在跑这段栈」

`RuntimePackages` 同时被两类调用方使用，分界线必须分清，否则会把「可豁免的内部断言」当致命点、
或把「致命点」当内部断言：

| 入口 | 谁在跑这段栈 | 平台在该点的行为 |
|---|---|---|
| `dispatch()`（`IPackageManager` 代理方法） | 客体线程 → binder → `Endpoint` → `dispatch` | 未知包 → `NameNotFoundException`；跨用户无权限 → `SecurityException`；**其余一律返回值** |
| `contextManager()` / `manager()` / `contextualManager()` | 客体线程直接调 `ContextImpl.getPackageManager()`（self-hook 转发） | 返回 Manager，**永不抛** |
| `resources()` / `xml()` / `text()` / `drawable()` | 客体线程读自己的资源 | 返回 Resources/XML/CharSequence/Drawable，缺失时 `NotFoundException` |

`invoke()` 已经把 `PackageAbsent | NameNotFoundException` 翻成平台中性值（false/0/null），
这也是本文件过去「多数调用看起来正常」的原因；真正的风险集中在 **`dispatch` 之外的 Java 门面方法**，
它们不经过 `invoke()` 的兜底。

## 2. 本批改掉的 6 处

| # | 位置 | 旧行为 | 新行为（= 平台答复） | 为什么平台不失败 |
|---|---|---|---|---|
| 1 | `RuntimePackages.java:75` `binder()` | `throw ISE(PACKAGE_ENDPOINT_NOT_READY)` | 记码 + 返回**缓存的占位 Binder** | `IInterface.asBinder()` 永远返回一个 IBinder；「还没发布」在平台上不是错误状态 |
| 2 | `:94` `contextualManager()` | `throw SecurityException(PACKAGE_CACHED_ENDPOINT_CHANGED)` | 记码后**重建**一个 manager（落到 `manager(context,view)` 的原路径） | `context.getPackageManager()` 在平台上只返回缓存对象，从不校验「缓存是否仍绑在本端点」 |
| 3 | `:152` `componentEnabled()` | `throw ISE(COMPONENT_SETTINGS_QUERY_FAILED)` | 记码 + 返回 `COMPONENT_ENABLED_STATE_DEFAULT` | 平台查组件启用状态是读表，broker 失败是**我方**故障 |
| 4 | `:160` `setComponentEnabled()` | `throw ISE(COMPONENT_SETTINGS_WRITE_FAILED)` | 记码 + 正常返回（void） | 平台写组件状态只在权限不足时抛，不因「后台通道不可用」抛 |
| 5 | `:295` `resources(manager,pkg,supplied)` | `throw SecurityException(XML_APPLICATION_IDENTITY_MISMATCH)` | 记码 + 继续用 `pkg` 读资源 | `getXml/getText/getDrawable` 在平台上只关心「这个包有没有这个资源 id」 |
| 6 | `:299` 同上 | `throw SecurityException(PACKAGE_RESOURCE_FACADE_UNREGISTERED)` | 记码 + 回落到 `engine.resources`（客体自己的资源视图） | 平台对「facade 没登记」没有概念；它给的就是该包的 Resources |

第 2 条是最值得注意的一处：它原本会在 `getPackageManager()` 上抛 SecurityException，
而那个调用点在客体的**任意线程**上，一旦发生就是「应用凭空崩溃」，且现场看不到应用帧。

第 5、6 条的语义代价要写清楚：资源读会退化成「用客体自己的 Resources 视图」，
结果是文案/图标可能取错，但不会崩——与 v71/v72 同一条口径（**用少量正确性换不崩**）。

## 3. 保留未改（附理由，别再回来当新发现）

| 位置 | 码 | 为什么保留 |
|---|---|---|
| `:62` `:67` `:71` `:72` `:76` | `PHYSICAL_PACKAGE_CAPABILITY_DENIED` / `PHYSICAL_COMPONENT_REQUIRED` / `PACKAGE_WORLD_ACTIVATION_REJECTED` / `PACKAGE_FRAMEWORK_CONTEXT_INVALID` / `PACKAGE_WORLD_ACTIVATION_ORDER` | 运行时**内部**能力栅栏（`requireInfrastructure` / `beginActivation` / `registerInfrastructureFacade`），调用方是我们自己的装配代码，不是客体 API；抛点跑在 authority 侧栈上 |
| `:132` `:150` | `PACKAGE_USER_UNSUPPORTED` | 平台对跨用户查询在缺 `INTERACT_ACROSS_USERS` 时**真的**抛 `SecurityException`；这是平台形状 |
| `:146` `:209` | `COMPONENT_REQUIRED` | 平台对 `null` ComponentName 抛 NPE/IAE，抛型一致即可 |
| `:191` | `VERSIONED_PACKAGE_REQUIRED` | 同上（`null` VersionedPackage） |
| `:78` `:81` | `PACKAGE_PLATFORM_CONTEXT_REQUIRED` / `PACKAGE_CONTEXT_IDENTITY_DENIED` | **本批故意不动**：它们决定「哪个 Context 拿到哪份 Resources」，改错会把整个 package world 的接线打乱，而当前设备锁屏无法验证。已列为下一批首选，改法是把「身份不符」降级成「用 engine.resources 继续」，而不是返回一个错的 manager |
| `:297` | `Resources.NotFoundException(absent.getMessage())` | 平台对不存在的包读资源就是 `NotFoundException` |

## 4. 度量（只降不升）

```
v72: total=1879  guest_facing=62  must_degrade=21  metric=61
v73: total=1873  guest_facing=56  must_degrade=19  metric=55
```

剩余 55 处分布：`getSystemService` 家族 13（`*_FACADE_IDENTITY_INVALID` / `*_FACADE_REQUIRED`）+
`package-endpoint` 余 8（上面「保留」里除平台形状外的待判项）+ `RuntimeReceivers` 9 +
其余 UNKNOWN 出口 25。

复现：`python3 android/scripts/facade_throw_audit.py`（末尾 JSON 的 `metric` 即待处理数；F9 门禁只降不升）。

## 5. 构建

```
python3 android/scripts/build-workbench.py --build-dir android/product/build-facade-v73 \
  --sdk /opt/homebrew/share/android-commandlinetools --platform android-37.0 --build-tools 37.0.0 \
  --java-home /opt/homebrew/opt/openjdk --ndk /opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370 \
  --platform-libc android/runtime-probe/build/device-libc.so --platform-art android/self-hook-probe/build/device-libart.so \
  --platform-linker android/self-hook-probe/build/device-linker64

BUILD_EXIT=0
tripoder.apk  2,323,699 B  sha256 aea75c6c622bc1a6761289e5…
```

## 6. 边界（别过度推断）

1. **只编译，未上机**：v73 没有安装到设备（PIN 锁屏，`AMS isSleeping=true`）。
   `BUILD_EXIT=0` 只证明 javac 接受这 6 处改写，不证明真机上的 package 解析行为。
2. 本批全部是「少抛异常」方向的单向改动，不会新增崩溃路径；但 #2 的「重建 manager」会让
   `context.getPackageManager()` 在端点切换后返回**新对象**，依赖对象同一性的客体代码会看到变化
   （影响面：只有在我们内部端点确实换了的情况下才会触发）。
3. #5/#6 会让资源读静默退化到 `engine.resources`，出现「文案/图标不对但应用不崩」的现场时，
   先查 `logcat -s TripoderFacade:W | grep -E 'XML_APPLICATION_IDENTITY_MISMATCH|PACKAGE_RESOURCE_FACADE_UNREGISTERED'`。
4. 未安装 ⇒ 本批**不能**被当作任何应用的兼容性结论；它只是让「应用凭空崩溃」的候选原因少了一族。
