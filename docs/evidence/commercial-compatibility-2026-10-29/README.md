# Commercial App Compatibility Verification Report (2026-10-29)

## 1. Executive Summary

This report documents the resolution of startup and lifecycle compatibility challenges for top-tier commercial Android applications running on **HyperOS 2 / Android 17 / API 37** (Device: Xiaomi 17 Pro `d861a4fa`, ARM64-v8a).

Specifically, full 9/9 and 10/10 automated verification milestones and continuous 30-second stability runs were achieved for:
1. **Honor of Kings (`com.tencent.tmgp.sgame`)** — Version `1201010103`, APK SHA-256 `6841b1eae92b3d87b00e986f86086cbe436bcb52e4b6a3c6829f368714538384`
2. **WeChat (`com.tencent.mm`)** — Version `8.0.53`, APK SHA-256 `f2a725bc04302ad218a563533ae79356e66b64aa711b275bc919a8d432f7e15d`
3. **Ctrip (`ctrip.android.view`)** — Version `8.74.2`, APK SHA-256 `d0ad3e5b40cafa0e020d8bee45bd9d76473ef7c236f7ff39b58809ac72e886b7`

All tests executed without any fallback strategies, adhering strictly to first-principles analysis of the Android 17 runtime, Bionic dynamic linker, and AOSP framework contracts.

---

## 2. Honor of Kings (`com.tencent.tmgp.sgame`) Compatibility

### 2.1 Root Cause Analysis

Honor of Kings incorporates Tencent Anti-Cheat Expert (ACE) security modules (`libtprt.so`, `libtersafe2.so`, and `libtersafe.so`). The application bootstrap follows the inheritance chain:
`com.tencent.tmgp.Common.SGameApplicaton` -> `androidx.multidex.MultiDexApplication` -> `com.ace.gshell.AceApplication`.

During `AceApplication.attachBaseContext(Context)`, ACE invokes native `initialize(...)`, spawning background monitoring threads that inspect:
1. `Settings.Global.adb_enabled` and `development_settings_enabled`.
2. `ActivityThread.mInitialApplication` and `ActivityThread.mAllApplications`.
3. Process UID / GID consistency and virtual filesystem layouts.

When security checks fail, ACE executes an intentional suicide dispatch routine:
```assembly
// libtprt.so +0xf4e5c .. +0xf4e84
bl      random
and     x0, x0, #0xfff     ; generates unaligned address e.g. 0xea
br      x0                 ; triggers Fatal signal 7 (SIGBUS), code 1 (BUS_ADRALN), fault addr 0xea
```
This PC alignment fault kills the worker thread, causing the main thread to deadlock on `__sem_timedwait` waiting for the security worker, eventually leading to process death and `DeadObjectException` in Workbench.

Furthermore, naive hook installation strategies that iteratively compile Dex methods in loops trigger mutator suspension pauses totaling >20 seconds, triggering `PROCESS_START_TIMEOUT_UNKNOWN:phase=APPLICATION`.

### 2.2 First-Principles Resolution

1. **System Settings Shielding**:
   - In [`SettingsReadProvider.java`](file:///Users/like/code/tripoder/android/runtime/src/dev/tripoder/runtime/SettingsReadProvider.java#L31-L34), intercepted `Settings.Global.adb_enabled` and `development_settings_enabled` to return `Bundle[{value="0"}]`.
   - Prevented ACE from detecting developer / debugging modes.

2. **AOSP `ActivityThread` Alignment**:
   - In [`RuntimeEngine.java`](file:///Users/like/code/tripoder/android/runtime/src/dev/tripoder/runtime/RuntimeEngine.java#L288), invoked `updateActivityThreadApplication(application)` prior to `RuntimeBridge.attachApplication`.
   - Guaranteed that `ActivityThread.mInitialApplication` points directly to the guest `Application` object when `attachBaseContext` executes, and updated `mAllApplications` list insertion at index 0.

3. **High-Performance Single-Batch Managed Hooks**:
   - In [`ApplicationAdapter.java`](file:///Users/like/code/tripoder/android/self-hook/java/dev/tripoder/selfhook/ApplicationAdapter.java#L929-L985), configured single-batch compilation:
     - Gathered target reflection methods: `com.ace.gshell.AceApplication` (`initialize`, `ioctl`), `com.tencent.tersafe2.TP2Sdk` (`init`, `initEx`, `ioctl`), `com.tencent.tp.TssSdk` (`init`, `initEx`).
     - Executed a single `ManagedHook.prepareBatch` call and a single `ManagedHook.enableBatch` call.
     - Reduced hook setup latency from 22,000ms to under 50ms, eliminating startup timeouts.

### 2.3 Verification Results (`control-30ac3334b28e4a82`)
- **Checks Passed**: 9 / 9 (100%)
- **Target First Activity**: `com.tencent.tmgp.sgame.SGameActivity` acknowledged in 43ms.
- **Stability Observation**: 30 consecutive samples across 30,139ms with 0 crashes or drops.
- **Worker Process**: PID `19919` remained alive and responsive throughout.

---

## 3. WeChat (`com.tencent.mm`) Compatibility

### 3.1 Root Cause Analysis

WeChat 8.0.53 leverages proprietary compressed shared libraries (`.cso`) using Zstandard (Zstd) compression, multi-process architecture (`com.tencent.mm` and `com.tencent.mm:push`), and Flutter/DartVM native components:
1. **Compressed Dynamic Libraries (.cso)**:
   - Dynamic libraries such as `libwechatxlog.so`, `libwechatmm.so`, and `libwechatnetwork.so` are packed as `.cso` files in `app_cso` or APK assets.
   - On Android 17, `W^X` memory protection and namespace restrictions strictly forbid dynamic library execution from writable memory or decompression into unapproved paths without appropriate file permissions and atomic writes.
2. **Flutter DartVM Loader Sequence**:
   - DartVM initialization depends on unified DSO namespaces and strict symbol resolution order.

### 3.2 First-Principles Resolution

1. **Native Zstd CSO Decompression Engine**:
   - In [`application_adapter.c`](file:///Users/like/code/tripoder/android/self-hook/native/application_adapter.c), implemented `decompressCsoNative` utilizing dynamically resolved `libzstd.so` decompression (`ZSTD_decompress`, `ZSTD_isError`).
   - Performed decompression into staging buffers, atomic rename with `0755` permissions, and validation against header sizes.
2. **Bionic Namespace and Split-Brain DSO Unification**:
   - Mapped `libwechatlv.so` and core library paths into the container's native library search paths before JNI activation.
3. **Multi-Process Ingress & Service Lifecycle Support**:
   - AOSP `ServiceTokenAccess` alignment permitted both main (`com.tencent.mm`) and background push (`com.tencent.mm:push`) processes to bind cleanly.

### 3.3 Verification Results (`control-bbbabee934c94917`)
- **Checks Passed**: 9 / 9 (100%)
- **Target First Activity**: `com.tencent.mm.ui.LauncherUI` acknowledged in 168ms.
- **Stability Observation**: 30 consecutive samples across 30,131ms with 0 crashes.
- **Multi-Process Verification**: Both PID `21374` (`com.tencent.mm`) and PID `21636` (`com.tencent.mm:push`) maintained continuous liveliness.

---

## 4. Ctrip (`ctrip.android.view`) Regression Verification

### 4.1 Verification Results (`control-9982f58298a84243`)
- **Checks Passed**: 10 / 10 (100%)
- **Target First Activity**: `ctrip.business.splash.CtripSplashActivity` acknowledged in 36ms.
- **Stability Observation**: 30 consecutive samples across 30,133ms.
- **Worker Process**: PID `22897` stable throughout.

---

## 5. Artifacts and Evidence Cross-Reference

| Application | Package | Checks | Stability | Result Evidence File |
| :--- | :--- | :---: | :---: | :--- |
| **Honor of Kings** | `com.tencent.tmgp.sgame` | **9 / 9 PASS** | **30s (30 samples)** | [`sgame-result.json`](file:///Users/like/code/tripoder/docs/evidence/commercial-compatibility-2026-10-29/sgame-result.json) |
| **WeChat** | `com.tencent.mm` | **9 / 9 PASS** | **30s (30 samples)** | [`wechat-result.json`](file:///Users/like/code/tripoder/docs/evidence/commercial-compatibility-2026-10-29/wechat-result.json) |
| **Ctrip** | `ctrip.android.view` | **10 / 10 PASS** | **30s (30 samples)** | [`ctrip-result.json`](file:///Users/like/code/tripoder/docs/evidence/commercial-compatibility-2026-10-29/ctrip-result.json) |
