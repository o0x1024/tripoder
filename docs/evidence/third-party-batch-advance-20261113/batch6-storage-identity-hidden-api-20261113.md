# 第 6 批 · 存储身份投影：三条实现路线全部被隐藏 API 限制挡住（v68 / v69 / v70）

- 设备：`d861a4fa`，构建 `build-facade-v68..v70`（v70 sha256 `bf7eca26…`）
- 对象：`com.netease.cloudmusic`（网易云音乐）
- 结论：**修法方向对、但不是本地能一次做完的改造**。崩溃点不变
  （`java.lang.SecurityException: callingPackage does not match UID`），三次不同实现都失败，
  失败原因已被日志钉死到引擎能力边界上，而不是「重写逻辑写错了」。

## 1. 三次尝试与各自的判据

| 版本 | 实现 | 结果 | 判据（logcat 原文） |
|---|---|---|---|
| v68 | `prime()` 挂在 `publishApplication()` 之后；按事务码重写 Binder | 未执行 | `TripoderStorageIdentity` **零输出**；崩溃 `15:57:14.482` 早于该点 |
| v69 | `prime()` 提前到 `callApplicationOnCreate` 之前 + begin 日志 | 执行了，注入失败 | `prime begin guest=com.netease.cloudmusic host=dev.tripoder.q6` 后紧跟 `TRANSACTION_getVolumeList unavailable: NoSuchFieldException` → `prime: nothing to wrap (mount=android.os.BinderProxy@343ed55)` |
| v70 | 放弃事务码与 Binder 包装，改用 `java.lang.reflect.Proxy` 代理 `IStorageManager`，只改一个 String 参数 | 执行了，反射探不到接口形状 | `getTransactionName unavailable: NoSuchMethodException` / `TRANSACTION_getVolumeList unavailable: NoSuchFieldException` / **`getVolumeList shape not found`** → `prime failed: mount interface unavailable statics=` |

## 2. 这三条日志合起来说明什么

1. **v69 的作用点是对的**：`prime begin` 打出来了，说明 `RuntimeStorageIdentity.prime()` 在
   客体 `Application.onCreate` 之前、且 `RuntimeEngine.current` 已就绪时被调用。**位置问题已解决**，
   以后任何「框架缓存投影」都可以复用这个点。
2. **v70 的阻塞点不是权限也不是时机，而是反射可见性**：`Class.forName("android.os.storage.IStorageManager")`
   能加载，但 `getMethods()` 里**没有任何** `getVolumeList` 通过形状筛选，
   `$Stub` 上也没有 `getTransactionName` / `TRANSACTION_getVolumeList`。
   这三条同时为空，指向同一个原因：**`IStorageManager` 是 `@hide` 框架接口，
   在 targetSdk 33 的客体进程里，成员级反射被隐藏 API 策略剥掉了**。
   注意客体自己调进去是正常的（崩溃栈里 `IStorageManager$Stub$Proxy.getVolumeList` 帧完整），
   区别在于**直接引用 vs 反射**：编译期引用走 boot class 链接，运行期反射走 hidden-API 门。
3. 因此「用 Binder 边界重写 `callingPackage`」这条路在本机（Android 17 / API 37）
   对 `@hide` 服务**不可达**，不是逻辑问题。

## 3. 剩下的可行路线（按代价排序，均需一次独立改造）

1. **走仓库既有的 `FixedRuntimeHooks` 生成管线，给 `android.os.Environment` 加钩子**：
   目标是 `getExternalStorageDirectory()` / `getExternalStoragePublicDirectory(String)`，
   直接返回宿主的 `/storage/emulated/0`，**根本不进 Binder**。
   判据依据：崩溃栈里发起调用的是**客体自己的字节码**
   （`com.netease.cloudmusic.core.filemanager.FileManagerImp.register` → `gu.b$c.invoke` → `Environment.getExternalStorageDirectory`），
   属于「应用字节码调用方」，正是 self-hook 保证覆盖的那一类
   （对照：`preexistingCompiledFramesCovered:false` 只影响框架内部调用方）。
   代价：`FixedRuntimeHooks` 的 `OWNERS`/`HASHES` 是按 owner 索引 + 声明摘要（SHA-256）钉死的，
   新增 owner 必须走生成管线重算摘要，不能手改常量。
2. 用 ART 层反射绕过（`dalvik.system` 内部接口或 JNI `FromReflectedMethod`）读 `@hide` 成员：
   属于为一条投影引入不稳定的引擎依赖，不建议。
3. 不做兜底：这不是可以靠重试/换接口绕开的检查——同一个 `Environment.getExternalStorageDirectory()`
   每次启动都会先跑。

## 4. 边界

- 只测了网易云音乐一个应用、一个入口（冷启动到 `WorkPathCleanerTask`）。
- 未验证「钩了 Environment 之后」该应用是否还有第二个同类检查
  （`getStorageVolumes` / `getAllocatableBytes` / `Context.getExternalFilesDir`）。
- 本轮**没有**把 v70 入库为可用版本；v70 只是诊断构建。

## 5. 复现

```bash
python3 android/scripts/build-workbench.py --build-dir android/product/build-facade-v70 \
  --sdk /opt/homebrew/share/android-commandlinetools --platform android-37.0 \
  --build-tools 37.0.0 --java-home /opt/homebrew/opt/openjdk \
  --ndk /opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370 \
  --platform-libc android/runtime-probe/build/device-libc.so \
  --platform-art android/self-hook-probe/build/device-libart.so \
  --platform-linker android/self-hook-probe/build/device-linker64 \
  --package dev.tripoder.q6
adb -s d861a4fa install --no-incremental -r android/product/build-facade-v70/tripoder-q6.apk
adb -s d861a4fa logcat -c; adb -s d861a4fa logcat -b crash -c
adb -s d861a4fa shell am force-stop dev.tripoder.q6; adb -s d861a4fa shell am force-stop dev.tripoder.q7
adb -s d861a4fa shell am start -n dev.tripoder.q6/dev.tripoder.hooklab.WorkbenchActivity
python3 android/scripts/guest_ui.py tap "重新连接"
python3 android/scripts/guest_ui.py tap "重新连接"
python3 android/scripts/guest_ui.py tap "网易云音乐" --scroll
sleep 35
adb -s d861a4fa logcat -d | grep -E "TripoderStorageIdentity|storage-identity"
adb -s d861a4fa logcat -b crash -d | head -12
```
