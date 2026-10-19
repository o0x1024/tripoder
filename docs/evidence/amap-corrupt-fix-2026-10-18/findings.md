# 高德地图 (com.autonavi.minimap) — slow load / login crash / "not installed" — root cause + fixes (2026-10-18)

User report: 高德 UI+content load extremely slow; app crashes when about to tap the not-logged-in
("未登录") tab to log in; afterwards the app list shows it as **not installed** while the project
binding is retained ("项目保留"). Installed as a guest in space `p6-20261014`, generation
`a7dbdd13-2e30-4583-a8d6-4769158eee3b`.

Four distinct architectural causes were found and fixed. All fixes are pure Java (runtime layer).

## 1. "Shows not installed / 项目保留" = a transient verification race latched as permanent CORRUPT
`ApkStore.get()` re-verifies a published artifact through `ArtifactIntegrityCache.get()`, which stamps
the (immutable) artifact tree, fully verifies every byte+signature, then stamps again and threw
`ARTIFACT_CHANGED_DURING_VERIFICATION` if any lstat field (incl. nanosecond ctime) changed across the
two snapshots. This is a TOCTOU "revalidate" signal, not proof of bad bytes — the class comment even
says "any ... change **revalidates**". But `ApkStore.get()`'s blanket `catch(Exception)` flipped the
install to **CORRUPT** in SQLite on ANY exception, including this transient one. Once CORRUPT the read
path never revisits the row, so the install is permanently bricked: UI shows
`INSTALL_CORRUPT`→"安装包校验失败，请重新添加这个应用", the project binding survives. The race fired during
the cold-broker boot window (`recover()` running concurrently). **Proof it was a false positive:**
`reverify` succeeded 3/3 and restored COMMITTED with the bytes unchanged.
- Fix A `ArtifactIntegrityCache.get()`: actually revalidate — re-verify against the settled tree a
  bounded number of times (STABILITY_ATTEMPTS=4); only a never-settling tree throws the new typed
  `VerificationUnstable extends IOException`.
- Fix B `ApkStore.get()`: catch `VerificationUnstable` and rethrow WITHOUT latching CORRUPT (install
  stays COMMITTED; next resolve retries). All other exceptions still latch CORRUPT as before.
- Safety preserved: genuine byte corruption still surfaces as `ARTIFACT_FILE_CORRUPT` (never the
  transient signal) and still latches. Verified by runtime-probe **phase p1 = 236/236 checks**
  (`corrupt-install-not-repaired`, `cold-corrupt-install-remains-unusable`,
  `native-corruption-rejected`, `cache-invalidates-on-ctime-change` all pass) + 5× cold-boot stress
  kept 高德 COMMITTED. Evidence: `p1-regression-2/`.

## 2. Slow load + first crash = DEX_LOAD_REPORT_PATH_DENIED on the guest's own artifact lib
高德's Alibaba SecurityGuard SDK loads `libsgmain.so` (a native lib carrying a secondary dex) and
reports it to PMS via the dex-load-report API. `RuntimeWebView.notifyDexLoad()` allowed the guest's
MUTABLE data dir (`ownData`) and an allowlist (base.apk, splits, webview) but NOT the guest's own
authenticated artifact `lib/` dir, so it threw `SecurityException: DEX_LOAD_REPORT_PATH_DENIED:
.../artifacts/<gen>/lib/libsgmain.so`. The SDK retried dozens of times/sec (the hang = "极慢" +
`APP_SCOUT_HANG` ANRs), then its `CrashDispatcher` rethrew uncaught → `Process.killProcess(SIG 9)`.
- Fix `RuntimeWebView.notifyDexLoad()`: also accept any path under the guest's artifact tree
  (`new File(engine.info.nativeLibraryDir).getParentFile()` = `.../artifacts/<gen>/`). That tree is
  hash-verified + sealed immutable by the install verifier — strictly MORE conservative than the
  already-allowed mutable `ownData` dir. Verified: DEX_DENIED → 0, app progressed past it (against the
  known-good native .so 37fd084d).

## 3. Login crash = SERVICE_INTENT_SHAPE_UNSUPPORTED on external service binds
The Alibaba `DeviceInfoCapturerFull` binds optional system services by action+package with no
component (`com.xiaomi.account`, `com.tencent.soter.soterserver`). `RuntimeServiceIntent.digest()`
(via `RuntimeServices.bind`) requires an explicit guest component and threw
`IllegalArgumentException: SERVICE_INTENT_SHAPE_UNSUPPORTED` on the SDK's worker thread → uncaught →
crash. Real Android resolves such an intent within the named package and returns **false** when no
service matches (it only throws "must be explicit" when NEITHER component nor package is set).
- Fix `RuntimeServices.bind()`: a bind whose intent has no component resolves to no guest-world
  service → return false (Android-faithful), except a fully-implicit intent (no component AND no
  package/selector) still throws "must be explicit". Verified: SHAPE crash gone, guest survived the
  full 90s window, stability 8/8 (against known-good native).

## 4. Next blocker reached = SERVICE_INTENT_STUB_MISMATCH on ACCS self-dispatched onStartCommand
With #2/#3 fixed the app reached the Taobao ACCS push service (embedded via the Alibaba SDK), whose
`BaseService$1.handleMessage` self-dispatches `onStartCommand` directly from its own Handler, passing
a plain guest intent that bears neither the engine's physical stub component nor the per-instance
lease. `RuntimeServices.visible()` assumed every intent is an engine-routed framework delivery and
threw `SERVICE_INTENT_STUB_MISMATCH` → uncaught → crash.
- Fix `RuntimeServices.visible()`: keep the engine-routed path (stub component AND lease both present)
  byte-identical; keep rejecting the inconsistent half-pair cases (one present, not the other); when
  NEITHER is present it is a legal same-UID in-process self-call — present the intent as-is with the
  guest classloader instead of throwing. Verified: STUB_MISMATCH → 0.

## Native-tree caveat (why no single clean end-to-end "map loads" capture)
A concurrent session has uncommitted, coupled native+Java self-hook work in the shared tree
(`runtime/native/path_policy.c`, `self_hook_storage.c`, `self-hook/native/art_managed_entry.c`,
`RuntimeSelfHook{Activities,ManagerExtras,ManagerPolicy,Managers}.java`). Builds 1–4 of this session
used the known-good native .so **37fd084d** (guests launch; 抖音/高德/滴滴 passed) and verified fixes
#1–#3 against it. After their native landed, a fresh build produces .so **9c9964d0**; running 高德
against it re-triggered `DEX_LOAD_REPORT_PATH_DENIED` at `RuntimeWebView.java:105` via `!file.isFile()`
for `.../artifacts/<gen>/lib/libsgmain.so` — i.e. their new path_policy/storage native regressed file
access to the artifact dir (NOT this session's Java: #2 was proven working against 37fd084d). Pinning
37fd084d onto the current Java is impossible because their new self-hook Java couples to the new .so
(`SELF_APPLICATION_ENGINE_IDENTITY_MISMATCH`). Each of the four fixes is verified in the environment
where it applied; a single consolidated launch is blocked by the other session's in-flight native work,
not by these changes.

Also: after 高德's earlier crash loop it shows its own "安全模式"/"尝试修复" recovery screen (app-level
state from the prior crashes, self-clears once it stops crashing).
