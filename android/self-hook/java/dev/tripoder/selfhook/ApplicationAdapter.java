package dev.tripoder.selfhook;

import android.app.Application;
import android.app.AppComponentFactory;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.os.Looper;
import android.os.Process;

/** One immutable logical application per process; fixed framework access only. */
public final class ApplicationAdapter {
    private ApplicationAdapter() {}
    private static volatile boolean factoryEvaluationAttempted,factoryEvaluationCompleted,factoryPreparedOwnerVerified;
    private static native void prepareOwnershipNative(Application host,java.lang.reflect.Field[] fields,Object[] methods);
    private static native void beginViewNative();
    private static native Context createNative(Application host,Application guest,ApplicationInfo info,
        ClassLoader loader,ClassLoader owner,AppComponentFactory factory,Resources resources);
    private static native void preparePackageNative(Application host,ApplicationInfo info,ClassLoader loader,ClassLoader owner,Resources resources);
    private static native void publishFactoryNative(AppComponentFactory factory);
    private static native void publishLoaderNative(ClassLoader loader);
    private static native boolean neutralizeScoutNative();
    private static native boolean decompressCsoNative(String srcPath, String dstPath, String zstdLibPath);
    private static native boolean patchXWebNative();
    public static void preparePackage(Application host,ApplicationInfo info,ClassLoader loader,ClassLoader owner,Resources resources,String process,java.util.List<android.content.pm.ProviderInfo> providers){
        preparePackage(host,info,loader,owner,resources,process,providers,false);
    }
    public static void prepareIsolatedPackage(Application host,ApplicationInfo info,ClassLoader loader,Resources resources,String process){
        preparePackage(host,info,loader,loader,resources,process,java.util.List.of(),true);
    }
    private static boolean fixedUid(Application host,ApplicationInfo info,boolean isolated){
        return host!=null&&info!=null&&(isolated?Process.isIsolated()&&host.getApplicationInfo().uid!=Process.myUid()&&info.uid==host.getApplicationInfo().uid:info.uid==Process.myUid()&&!Process.isIsolated());
    }
    private static void preparePackage(Application host,ApplicationInfo info,ClassLoader loader,ClassLoader owner,Resources resources,String process,java.util.List<android.content.pm.ProviderInfo> providers,boolean isolated){
        mainThread();if(!fixedUid(host,info,isolated)||loader==null||owner==null||resources==null||process==null||info.packageName==null||info.dataDir==null)throw new IllegalArgumentException("APPLICATION_FIXED_IDENTITY_REQUIRED");
        preparePackageNative(host,info,loader,owner,resources);
        neutralizeSystemScout();
        ApplicationBinding.publish(host,info,packageInfoNative(),process,providers);
        configureGuestCompatibility(info, loader);
    }
    private static void configureGuestCompatibility(ApplicationInfo info, ClassLoader loader) {
        if ("com.xingin.xhs".equals(info.packageName)) {
            // 1. Universal ByteHook & ShadowHook neutralization at engine level
            try {
                Class<?> byteHookClass = Class.forName("com.bytedance.android.bytehook.ByteHook", false, loader);
                java.lang.reflect.Field bhA = byteHookClass.getDeclaredField("a");
                bhA.setAccessible(true);
                bhA.setBoolean(null, true);
                java.lang.reflect.Field bhB = byteHookClass.getDeclaredField("b");
                bhB.setAccessible(true);
                bhB.setInt(null, 0); // ERRNO_OK
                android.util.Log.i("TripoderXHS", "Neutralized ByteHook.init -> OK");
            } catch (Throwable t) {
                android.util.Log.w("TripoderXHS", "ByteHook neutralization skipped", t);
            }
            try {
                Class<?> shadowHookClass = Class.forName("com.bytedance.shadowhook.ShadowHook", false, loader);
                java.lang.reflect.Field shA = shadowHookClass.getDeclaredField("a");
                shA.setAccessible(true);
                shA.setBoolean(null, true);
                java.lang.reflect.Field shB = shadowHookClass.getDeclaredField("b");
                shB.setAccessible(true);
                shB.setInt(null, 0); // ERRNO_OK
                android.util.Log.i("TripoderXHS", "Neutralized ShadowHook.init -> OK");
            } catch (Throwable t) {
                android.util.Log.w("TripoderXHS", "ShadowHook neutralization skipped", t);
            }

            // 2. APM SentryNCrashProxy & SentryRecordImpl neutralization
            try {
                Class<?> sentryProxyClass = Class.forName("com.xingin.hook.SentryNCrashProxy", false, loader);
                java.lang.reflect.Field hashInitField = sentryProxyClass.getDeclaredField("hashInit");
                hashInitField.setAccessible(true);
                hashInitField.setBoolean(null, true);
                java.lang.reflect.Field nEnableField = sentryProxyClass.getDeclaredField("nEnable");
                nEnableField.setAccessible(true);
                nEnableField.set(null, Boolean.FALSE);
                android.util.Log.i("TripoderXHS", "Neutralized SentryNCrashProxy flags");
            } catch (Throwable t) {
                android.util.Log.w("TripoderXHS", "SentryNCrashProxy flag neutralization skipped", t);
            }
            try {
                Class<?> recordImplClass = Class.forName("com.xingin.record.SentryRecordImpl", false, loader);
                java.lang.reflect.Field aField = recordImplClass.getDeclaredField("a");
                aField.setAccessible(true);
                aField.setBoolean(null, true);
                java.lang.reflect.Field bField = recordImplClass.getDeclaredField("b");
                bField.setAccessible(true);
                bField.setBoolean(null, true);
                android.util.Log.i("TripoderXHS", "Neutralized SentryRecordImpl flags");
            } catch (Throwable t) {
                android.util.Log.w("TripoderXHS", "SentryRecordImpl flag neutralization skipped", t);
            }

            // 3. APM Sentry Core & xCrash NativeHandler neutralization (Layer 2)
            try {
                Class<?> t3eEClass = Class.forName("t3e.e", false, loader);
                java.lang.reflect.Field bField = t3eEClass.getDeclaredField("b");
                bField.setAccessible(true);
                bField.setBoolean(null, true);
                try {
                    java.lang.reflect.Field cField = t3eEClass.getDeclaredField("c");
                    cField.setAccessible(true);
                    cField.setBoolean(null, true);
                } catch (Throwable ignored) {}
                android.util.Log.i("TripoderXHS", "Neutralized t3e.e.b -> true");
            } catch (Throwable t) {
                android.util.Log.w("TripoderXHS", "t3e.e neutralization skipped", t);
            }
            try {
                Class<?> k0Class = Class.forName("io.sentry.core.k0", false, loader);
                java.lang.reflect.Field aField = k0Class.getDeclaredField("a");
                aField.setAccessible(true);
                aField.setBoolean(null, true);
                android.util.Log.i("TripoderXHS", "Neutralized io.sentry.core.k0.a -> true");
            } catch (Throwable t) {
                android.util.Log.w("TripoderXHS", "io.sentry.core.k0 neutralization skipped", t);
            }
            try {
                Class<?> nhClass = Class.forName("io.sentry.core.NativeHandler", false, loader);
                java.lang.reflect.Field instField = nhClass.getDeclaredField("instance");
                instField.setAccessible(true);
                Object nhInstance = instField.get(null);
                if (nhInstance != null) {
                    java.lang.reflect.Field okField = nhClass.getDeclaredField("initNativeLibOk");
                    okField.setAccessible(true);
                    okField.setBoolean(nhInstance, true);
                }
                android.util.Log.i("TripoderXHS", "Neutralized NativeHandler.initNativeLibOk -> true");
            } catch (Throwable t) {
                android.util.Log.w("TripoderXHS", "NativeHandler neutralization skipped", t);
            }
            try {
                Class<?> cfgClass = Class.forName("io.sentry.core.SentryCoreConfig", false, loader);
                java.lang.reflect.Field sInst = cfgClass.getDeclaredField("sInstance");
                sInst.setAccessible(true);
                Object cfg = sInst.get(null);
                if (cfg != null) {
                    try {
                        java.lang.reflect.Field eField = cfgClass.getDeclaredField("enable");
                        eField.setAccessible(true);
                        eField.setBoolean(cfg, false);
                    } catch (Throwable ignored) {}
                    try {
                        java.lang.reflect.Field enh = cfgClass.getDeclaredField("enableNativeHook");
                        enh.setAccessible(true);
                        enh.setBoolean(cfg, false);
                    } catch (Throwable ignored) {}
                    try {
                        java.lang.reflect.Field ejh = cfgClass.getDeclaredField("enableJavaHook");
                        ejh.setAccessible(true);
                        ejh.setBoolean(cfg, false);
                    } catch (Throwable ignored) {}
                    try {
                        java.lang.reflect.Field ecr = cfgClass.getDeclaredField("enableCrashRecord");
                        ecr.setAccessible(true);
                        ecr.setBoolean(cfg, false);
                    } catch (Throwable ignored) {}
                    try {
                        java.lang.reflect.Field exh = cfgClass.getDeclaredField("enableXdlHook");
                        exh.setAccessible(true);
                        exh.setBoolean(cfg, false);
                    } catch (Throwable ignored) {}
                }
                android.util.Log.i("TripoderXHS", "Neutralized SentryCoreConfig flags");
            } catch (Throwable t) {
                android.util.Log.w("TripoderXHS", "SentryCoreConfig neutralization skipped", t);
            }

            // 4. CapaNativeHook flag neutralization
            try {
                Class<?> bc3DClass = Class.forName("bc3.d", false, loader);
                java.lang.reflect.Field bField = bc3DClass.getDeclaredField("b");
                bField.setAccessible(true);
                bField.setBoolean(null, true);
                android.util.Log.i("TripoderXHS", "Neutralized bc3.d.b -> true");
            } catch (Throwable t) {
                android.util.Log.w("TripoderXHS", "bc3.d neutralization skipped", t);
            }

            // 5. APM LogHook flag neutralization
            try {
                Class<?> logHookClass = Class.forName("com.xingin.xhs.hook.LogHook", false, loader);
                java.lang.reflect.Field initedField = logHookClass.getDeclaredField("inited");
                initedField.setAccessible(true);
                initedField.setBoolean(null, true);
                android.util.Log.i("TripoderXHS", "Neutralized LogHook flags");
            } catch (Throwable t) {
                android.util.Log.w("TripoderXHS", "LogHook flag neutralization skipped", t);
            }

            // 7. MaliOptimizer flag neutralization
            try {
                Class<?> maliClass = Class.forName("com.xingin.xhs.sysoptimizer.optimise.stability.MaliOptimizer", false, loader);
                java.lang.reflect.Field aField = maliClass.getDeclaredField("a");
                aField.setAccessible(true);
                aField.setBoolean(null, true);
                android.util.Log.i("TripoderXHS", "Neutralized MaliOptimizer flags");
            } catch (Throwable t) {
                android.util.Log.w("TripoderXHS", "MaliOptimizer flag neutralization skipped", t);
            }

            // 8. MSA OAID SDK neutralization (prevents libmsaoaidsec.so SEGV_ACCERR crash on modern kernel linker)
            try {
                Class<?> msaClass = Class.forName("com.xingin.xhs.manager.MsaAllianceManager", false, loader);
                try {
                    java.lang.reflect.Field certField = msaClass.getDeclaredField("isCertInit");
                    certField.setAccessible(true);
                    certField.setBoolean(null, true);
                } catch (Throwable ignored) {}
                try {
                    java.lang.reflect.Field loadField = msaClass.getDeclaredField("isLoading");
                    loadField.setAccessible(true);
                    loadField.setBoolean(null, false);
                } catch (Throwable ignored) {}
                try {
                    java.lang.reflect.Field oaidField = msaClass.getDeclaredField("oaid");
                    oaidField.setAccessible(true);
                    oaidField.set(null, "00000000-0000-0000-0000-000000000000");
                } catch (Throwable ignored) {}
                try {
                    java.lang.reflect.Field adsOaidField = msaClass.getDeclaredField("adsOaid");
                    adsOaidField.setAccessible(true);
                    adsOaidField.set(null, "00000000-0000-0000-0000-000000000000");
                } catch (Throwable ignored) {}
                android.util.Log.i("TripoderXHS", "Neutralized MsaAllianceManager flags");
            } catch (Throwable t) {
                android.util.Log.w("TripoderXHS", "MsaAllianceManager flag neutralization skipped", t);
            }

            // 9. Robust PatchProxy interception for APM & security hookers
            try {
                Class<?> proxyClass = Class.forName("com.xingin.robust.PatchProxy", false, loader);
                java.lang.reflect.Field mapField = proxyClass.getDeclaredField("changeQuickRedirects");
                mapField.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.Map<Object, Object> map = (java.util.Map<Object, Object>) mapField.get(null);
                Class<?> cqrClass = Class.forName("com.xingin.robust.base.ChangeQuickRedirect", false, loader);

                // DiskManager redirect: intercept isDeviceInBlackList ("290459") -> true, initByteHook ("290461") -> void
                Object diskMgrRedirect = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{cqrClass}, (p, m, args) -> {
                    String name = m.getName();
                    if ("isSupport".equals(name)) {
                        String methodId = (String) args[0];
                        return "290459".equals(methodId) || "290461".equals(methodId);
                    }
                    if ("accessDispatch".equals(name)) {
                        String methodId = (String) args[0];
                        if ("290459".equals(methodId)) return Boolean.TRUE;
                        return null;
                    }
                    return null;
                });

                // Void no-op redirect for pure-void APM hook classes
                Object voidNoOpRedirect = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{cqrClass}, (p, m, args) -> {
                    String name = m.getName();
                    if ("isSupport".equals(name)) {
                        return Boolean.TRUE;
                    }
                    return null; // void return
                });

                // FileHook redirect: ONLY intercept hookOpen ("293037") and unhookOpen ("293039") -> return void
                Object fileHookRedirect = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{cqrClass}, (p, m, args) -> {
                    String name = m.getName();
                    if ("isSupport".equals(name)) {
                        String methodId = (String) args[0];
                        return "293037".equals(methodId) || "293039".equals(methodId);
                    }
                    return null;
                });

                // MsaAllianceManager redirect: intercept initMdidSdk / initAdMdidSdk, invoke callback, prevent libmsaoaidsec crash
                Object msaRedirect = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{cqrClass}, (p, m, args) -> {
                    String name = m.getName();
                    if ("isSupport".equals(name)) {
                        String methodId = (String) args[0];
                        return "270810".equals(methodId) || "270813".equals(methodId) || "270811".equals(methodId) || "270814".equals(methodId) || "270812".equals(methodId);
                    }
                    if ("accessDispatch".equals(name)) {
                        Object[] params = (Object[]) args[1];
                        if (params != null) {
                            for (Object param : params) {
                                if (param != null) {
                                    try {
                                        java.lang.reflect.Method inv = param.getClass().getMethod("invoke");
                                        inv.invoke(param);
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                        return null;
                    }
                    return null;
                });

                try {
                    Class<?> diskMgrClass = Class.forName("com.xingyin.disk_manager.XhsDiskManager", false, loader);
                    map.put(diskMgrClass, diskMgrRedirect);
                } catch (Throwable ignored) {}
                try {
                    Class<?> fileHookClass = Class.forName("com.xingyin.disk_manager.bytehook.FileHook", false, loader);
                    map.put(fileHookClass, fileHookRedirect);
                } catch (Throwable ignored) {}
                try {
                    Class<?> msaClass = Class.forName("com.xingin.xhs.manager.MsaAllianceManager", false, loader);
                    map.put(msaClass, msaRedirect);
                } catch (Throwable ignored) {}
                try {
                    Class<?> sentryProxyClass = Class.forName("com.xingin.hook.SentryNCrashProxy", false, loader);
                    map.put(sentryProxyClass, voidNoOpRedirect);
                } catch (Throwable ignored) {}
                try {
                    Class<?> sentryRecordClass = Class.forName("com.xingin.record.SentryRecordImpl", false, loader);
                    map.put(sentryRecordClass, voidNoOpRedirect);
                } catch (Throwable ignored) {}
                try {
                    Class<?> t3eEClass = Class.forName("t3e.e", false, loader);
                    map.put(t3eEClass, voidNoOpRedirect);
                } catch (Throwable ignored) {}
                try {
                    Class<?> tn2HClass = Class.forName("tn2.h", false, loader);
                    map.put(tn2HClass, voidNoOpRedirect);
                } catch (Throwable ignored) {}
                try {
                    Class<?> bc3DClass = Class.forName("bc3.d", false, loader);
                    map.put(bc3DClass, voidNoOpRedirect);
                } catch (Throwable ignored) {}
                try {
                    Class<?> logHookClass = Class.forName("com.xingin.xhs.hook.LogHook", false, loader);
                    map.put(logHookClass, voidNoOpRedirect);
                } catch (Throwable ignored) {}
                try {
                    Class<?> logHook30Class = Class.forName("com.xingin.xhs.hook.LogHookApi30", false, loader);
                    map.put(logHook30Class, voidNoOpRedirect);
                } catch (Throwable ignored) {}
                try {
                    Class<?> maliClass = Class.forName("com.xingin.xhs.sysoptimizer.optimise.stability.MaliOptimizer", false, loader);
                    map.put(maliClass, voidNoOpRedirect);
                } catch (Throwable ignored) {}

                android.util.Log.i("TripoderXHS", "Registered PatchProxy redirects for APM classes");
            } catch (Throwable t) {
                android.util.Log.w("TripoderXHS", "Failed to register XHS PatchProxy redirects", t);
            }
        }
        if ("com.hunantv.imgo.activity".equals(info.packageName)) {
            try {
                Class<?> lcClass = Class.forName("com.mgtv.config.l$c", true, loader);
                java.lang.reflect.Field aField = lcClass.getDeclaredField("a");
                aField.setAccessible(true);
                Object lInstance = aField.get(null);
                if (lInstance != null) {
                    Class<?> lClass = lInstance.getClass();
                    java.lang.reflect.Field bField = lClass.getDeclaredField("b");
                    bField.setAccessible(true);
                    bField.setBoolean(lInstance, true);
                    java.lang.reflect.Field cField = lClass.getDeclaredField("c");
                    cField.setAccessible(true);
                    cField.set(lInstance, "00000000-0000-0000-0000-000000000000");
                    java.lang.reflect.Field dField = lClass.getDeclaredField("d");
                    dField.setAccessible(true);
                    dField.setBoolean(lInstance, true);
                    android.util.Log.i("TripoderImgo", "Neutralized com.mgtv.config.l flags");
                }
            } catch (Throwable t) {
                android.util.Log.w("TripoderImgo", "com.mgtv.config.l neutralization skipped", t);
            }
        }
        if ("com.netease.cloudmusic".equals(info.packageName)) {
            try {
                Class<?> myAppClass = Class.forName("com.netease.nis.wrapper.MyApplication", true, loader);
                java.lang.reflect.Field strAppNameField = myAppClass.getDeclaredField("strAppName");
                strAppNameField.setAccessible(true);
                strAppNameField.set(null, "com.netease.cloudmusic.CloudMusicApplication");
                android.util.Log.i("TripoderCloudMusic", "Configured strAppName -> com.netease.cloudmusic.CloudMusicApplication");
            } catch (Throwable t) {
                android.util.Log.w("TripoderCloudMusic", "Failed to configure strAppName", t);
            }
        }
        if ("com.qiyi.video".equals(info.packageName)) {
            // Neutralize MSA OAID SDK to prevent libmsaoaidsec.so SEGV_ACCERR crash on Android 17 / HyperOS 2
            try {
                Class<?> oClass = Class.forName("yn3.o", true, loader);
                try {
                    java.lang.reflect.Field fField = oClass.getDeclaredField("f");
                    fField.setAccessible(true);
                    fField.setBoolean(null, true);
                } catch (Throwable ignored) {}
                try {
                    java.lang.reflect.Field dField = oClass.getDeclaredField("d");
                    dField.setAccessible(true);
                    dField.setBoolean(null, true);
                } catch (Throwable ignored) {}
                try {
                    java.lang.reflect.Field cField = oClass.getDeclaredField("c");
                    cField.setAccessible(true);
                    cField.setBoolean(null, true);
                } catch (Throwable ignored) {}
                try {
                    java.lang.reflect.Field eField = oClass.getDeclaredField("e");
                    eField.setAccessible(true);
                    eField.setInt(null, 0);
                } catch (Throwable ignored) {}
                try {
                    Class<?> oaidInfoClass = Class.forName("org.qiyi.video.util.oaid.OaidInfo", true, loader);
                    Object oaidInfo = oaidInfoClass.getConstructor().newInstance();
                    try {
                        java.lang.reflect.Field c0 = oaidInfoClass.getField("c0");
                        c0.setAccessible(true);
                        c0.set(oaidInfo, "00000000-0000-0000-0000-000000000000");
                    } catch (Throwable ignored) {}
                    try {
                        java.lang.reflect.Field d0 = oaidInfoClass.getField("d0");
                        d0.setAccessible(true);
                        d0.set(oaidInfo, "00000000-0000-0000-0000-000000000000");
                    } catch (Throwable ignored) {}
                    try {
                        java.lang.reflect.Field e0 = oaidInfoClass.getField("e0");
                        e0.setAccessible(true);
                        e0.set(oaidInfo, "00000000-0000-0000-0000-000000000000");
                    } catch (Throwable ignored) {}
                    try {
                        java.lang.reflect.Field b0 = oaidInfoClass.getField("b0");
                        b0.setAccessible(true);
                        b0.setBoolean(oaidInfo, true);
                    } catch (Throwable ignored) {}
                    java.lang.reflect.Field aField = oClass.getDeclaredField("a");
                    aField.setAccessible(true);
                    aField.set(null, oaidInfo);
                } catch (Throwable ignored) {}
                android.util.Log.i("TripoderQiyi", "Neutralized yn3.o flags (f=true, d=true, c=true, e=0, a=oaidInfo)");
            } catch (Throwable t) {
                android.util.Log.w("TripoderQiyi", "yn3.o neutralization skipped", t);
            }
            // ByteHook & ShadowHook neutralization for iQiyi
            try {
                Class<?> byteHookClass = Class.forName("com.bytedance.android.bytehook.ByteHook", false, loader);
                java.lang.reflect.Field bhA = byteHookClass.getDeclaredField("a");
                bhA.setAccessible(true);
                bhA.setBoolean(null, true);
                java.lang.reflect.Field bhB = byteHookClass.getDeclaredField("b");
                bhB.setAccessible(true);
                bhB.setInt(null, 0); // ERRNO_OK
                android.util.Log.i("TripoderQiyi", "Neutralized ByteHook.init -> OK");
            } catch (Throwable ignored) {}
            try {
                Class<?> shadowHookClass = Class.forName("com.bytedance.shadowhook.ShadowHook", false, loader);
                java.lang.reflect.Field shA = shadowHookClass.getDeclaredField("a");
                shA.setAccessible(true);
                shA.setBoolean(null, true);
                java.lang.reflect.Field shB = shadowHookClass.getDeclaredField("b");
                shB.setAccessible(true);
                shB.setInt(null, 0); // ERRNO_OK
                android.util.Log.i("TripoderQiyi", "Neutralized ShadowHook.init -> OK");
            } catch (Throwable ignored) {}
        }
        if ("ctrip.android.view".equals(info.packageName)) {
            try {
                Class<?> soLoaderClass = Class.forName("com.facebook.soloader.SoLoader", false, loader);
                Class<?> soFileLoaderClass = Class.forName("com.facebook.soloader.SoFileLoader", false, loader);
                Class<?> implClass = Class.forName("com.facebook.soloader.SoFileLoaderImpl", false, loader);
                Class<?> instrumentedClass = Class.forName("com.facebook.soloader.InstrumentedSoFileLoader", false, loader);

                Object impl = implClass.getConstructor().newInstance();
                Object instrumented = instrumentedClass.getConstructor(soFileLoaderClass).newInstance(impl);

                Object proxy = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{soFileLoaderClass}, (p, method, args) -> {
                    if ("load".equals(method.getName()) && args != null && args.length >= 1 && args[0] instanceof String) {
                        String pathToSo = (String) args[0];
                        String name = new java.io.File(pathToSo).getName();
                        java.io.File physical = new java.io.File(info.nativeLibraryDir, name);
                        if (physical.exists()) {
                            args[0] = physical.getAbsolutePath();
                        }
                    }
                    return method.invoke(instrumented, args);
                });

                java.lang.reflect.Field sSoFileLoaderField = soLoaderClass.getDeclaredField("sSoFileLoader");
                sSoFileLoaderField.setAccessible(true);
                sSoFileLoaderField.set(null, proxy);
                android.util.Log.i("TripoderCtrip", "Configured SoLoader sSoFileLoader proxy with nativeLibraryDir: " + info.nativeLibraryDir);
            } catch (Throwable t) {
                android.util.Log.w("TripoderCtrip", "Failed to configure SoLoader sSoFileLoader", t);
            }
        }
        if ("com.tencent.mm".equals(info.packageName)) {
            try {
                try {
                    Thread.UncaughtExceptionHandler cur = Thread.getDefaultUncaughtExceptionHandler();
                    if (!(cur instanceof TripoderUncaughtHandler)) {
                        Thread.setDefaultUncaughtExceptionHandler(new TripoderUncaughtHandler(cur));
                        android.util.Log.i("TripoderWeChat", "Installed TripoderUncaughtHandler");
                    }
                } catch (Throwable t) {
                    android.util.Log.w("TripoderWeChat", "Failed to wrap uncaught exception handler", t);
                }

                try {
                    android.webkit.WebView.setWebContentsDebuggingEnabled(true);
                    android.util.Log.i("TripoderWeChat", "Enabled WebView debugging");
                } catch (Throwable t) {
                    android.util.Log.w("TripoderWeChat", "Failed to enable WebView debugging", t);
                }

                try {
                    Class<?> xlogClass = Class.forName("com.tencent.mars.xlog.Log", false, loader);
                    java.lang.reflect.Method setConsoleLogOpen = xlogClass.getMethod("setConsoleLogOpen", boolean.class);
                    setConsoleLogOpen.invoke(null, true);
                    java.lang.reflect.Method setLevel = xlogClass.getMethod("setLevel", int.class, boolean.class);
                    setLevel.invoke(null, 0, true);
                    android.util.Log.i("TripoderWeChat", "Enabled Mars XLog console logging");
                } catch (Throwable t) {
                    android.util.Log.w("TripoderWeChat", "Failed to enable Mars console log", t);
                }

                // Continuous watcher to wrap Log.logImp as soon as it initializes
                new Thread(() -> {
                    for (int i = 0; i < 60; i++) {
                        try {
                            Class<?> logClass = Class.forName("com.tencent.mars.xlog.Log", false, loader);
                            java.lang.reflect.Field f = logClass.getDeclaredField("logImp");
                            f.setAccessible(true);
                            Object imp = f.get(null);
                            if (imp != null && !java.lang.reflect.Proxy.isProxyClass(imp.getClass())) {
                                Class<?> logImpClass = Class.forName("com.tencent.mars.xlog.Log$LogImp", false, loader);
                                final Object originalImp = imp;
                                Object proxy = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{logImpClass}, (p, method, mArgs) -> {
                                    String mName = method.getName();
                                    if (mName.startsWith("log") && mArgs != null && mArgs.length >= 8) {
                                        String tag = String.valueOf(mArgs[1]);
                                        String filename = String.valueOf(mArgs[2]);
                                        String func = String.valueOf(mArgs[3]);
                                        Object line = mArgs[4];
                                        String msg = String.valueOf(mArgs[mArgs.length - 1]);
                                        android.util.Log.i("TripoderMarsLog", "[" + tag + " " + filename + ":" + line + " " + func + "] " + msg);
                                    } else if ("setConsoleLogOpen".equals(mName) && mArgs != null && mArgs.length >= 2) {
                                        mArgs[1] = Boolean.TRUE;
                                    }
                                    return method.invoke(originalImp, mArgs);
                                });
                                f.set(null, proxy);
                                java.lang.reflect.Method setConsoleLogOpen = logClass.getMethod("setConsoleLogOpen", boolean.class);
                                setConsoleLogOpen.invoke(null, true);
                                android.util.Log.i("TripoderWeChat", "Successfully wrapped Log.logImp and enabled console logging");
                                break;
                            }
                        } catch (Throwable ignored) {}
                        try {
                            Thread.UncaughtExceptionHandler curHandler = Thread.getDefaultUncaughtExceptionHandler();
                            if (curHandler != null && !(curHandler instanceof TripoderUncaughtHandler)) {
                                Thread.setDefaultUncaughtExceptionHandler(new TripoderUncaughtHandler(curHandler));
                            }
                        } catch (Throwable ignored) {}
                        try { Thread.sleep(250); } catch (Throwable ignored) {}
                    }
                }, "TripoderXLogWatcher").start();

                // Continuous watcher to patch libxwebcore.so as soon as it is mapped into memory
                new Thread(() -> {
                    for (int i = 0; i < 600; i++) { // Poll for up to 300 seconds
                        try {
                            if (patchXWebNative()) {
                                android.util.Log.i("TripoderWeChat", "TripoderXWebWatcher: successfully patched and hooked libxwebcore.so!");
                                break;
                            }
                        } catch (Throwable t) {
                            android.util.Log.w("TripoderWeChat", "TripoderXWebWatcher error", t);
                        }
                        try { Thread.sleep(200); } catch (Throwable ignored) {}
                    }
                }, "TripoderXWebWatcher").start();

                // 1. Neutralize APM signal hook & watchdog flags in memory
                try {
                    Class<?> fv0U0 = Class.forName("fv0.u0", false, loader);
                    java.lang.reflect.Field bField = fv0U0.getDeclaredField("b");
                    bField.setAccessible(true);
                    bField.setBoolean(null, true);
                    android.util.Log.i("TripoderWeChat", "Neutralized fv0.u0 (SignalHookHandler.isAlreadyHooked -> true)");
                } catch (Throwable t) {
                    android.util.Log.w("TripoderWeChat", "fv0.u0 neutralization skipped", t);
                }

                try {
                    Class<?> threadInspect = Class.forName("com.tencent.wxperf.thread.ThreadInspect", false, loader);
                    java.lang.reflect.Field initField = threadInspect.getDeclaredField("initialized");
                    initField.setAccessible(true);
                    initField.setBoolean(null, true);
                    android.util.Log.i("TripoderWeChat", "Neutralized ThreadInspect.initialized -> true");
                } catch (Throwable t) {
                    android.util.Log.w("TripoderWeChat", "ThreadInspect neutralization skipped", t);
                }

                try {
                    Class<?> jUnwindJni = Class.forName("com.tencent.matrix.hook.junwind.JUnwindJni", false, loader);
                    java.lang.reflect.Field aField = jUnwindJni.getDeclaredField("a");
                    aField.setAccessible(true);
                    aField.setBoolean(null, false);
                    android.util.Log.i("TripoderWeChat", "Neutralized JUnwindJni.a -> false");
                } catch (Throwable t) {
                    android.util.Log.w("TripoderWeChat", "JUnwindJni neutralization skipped", t);
                }

                try {
                    Class<?> pth = Class.forName("com.tencent.matrix.hook.junwind.PthreadHackJni", false, loader);
                    java.lang.reflect.Field bField = pth.getDeclaredField("b");
                    bField.setAccessible(true);
                    bField.setInt(null, -1);
                    android.util.Log.i("TripoderWeChat", "Neutralized PthreadHackJni.b -> -1");
                } catch (Throwable t) {
                    android.util.Log.w("TripoderWeChat", "PthreadHackJni neutralization skipped", t);
                }

                // 2. Setup native library loader for guest ClassLoader
                final java.lang.reflect.Method nativeLoadMethod;
                java.lang.reflect.Method nl = null;
                try {
                    nl = Runtime.class.getDeclaredMethod("nativeLoad", String.class, ClassLoader.class);
                    nl.setAccessible(true);
                } catch (Throwable t) {
                    android.util.Log.w("TripoderWeChat", "Failed to access Runtime.nativeLoad", t);
                }
                nativeLoadMethod = nl;

                final java.lang.reflect.Method findLibMethod;
                java.lang.reflect.Method flm = null;
                try {
                    flm = loader.getClass().getMethod("findLibrary", String.class);
                } catch (Throwable t1) {
                    try {
                        flm = ClassLoader.class.getDeclaredMethod("findLibrary", String.class);
                        flm.setAccessible(true);
                    } catch (Throwable ignored) {}
                }
                findLibMethod = flm;

                // Find physical canonical data directory from info.nativeLibraryDir
                // info.nativeLibraryDir = <dataCanonicalDir>/code/<pkg>-<gen>/lib/arm64
                java.io.File physicalData = null;
                if (info.nativeLibraryDir != null) {
                    java.io.File cur = new java.io.File(info.nativeLibraryDir);
                    java.io.File codeLib = cur.getParentFile();
                    java.io.File codeGen = codeLib != null ? codeLib.getParentFile() : null;
                    java.io.File codeDir = codeGen != null ? codeGen.getParentFile() : null;
                    if (codeDir != null && "code".equals(codeDir.getName())) {
                        java.io.File candidateData = codeDir.getParentFile();
                        if (candidateData != null && candidateData.isDirectory()) {
                            physicalData = candidateData;
                        }
                    }
                }
                if (physicalData == null) {
                    physicalData = new java.io.File(info.dataDir);
                }
                final java.io.File csoDir = new java.io.File(physicalData, "cso");
                csoDir.mkdirs();

                java.io.File artifactsLib = null;
                if (findLibMethod != null) {
                    try {
                        String p = (String) findLibMethod.invoke(loader, "c++_shared");
                        if (p == null) p = (String) findLibMethod.invoke(loader, "flutter");
                        if (p != null) artifactsLib = new java.io.File(p).getParentFile();
                    } catch (Throwable ignored) {}
                }
                final java.io.File artifactsLibDir = artifactsLib;

                // Prepend csoDir to PathClassLoader nativeLibraryDirectories so ClassLoader.findLibrary resolves decompressed ELFs
                try {
                    java.lang.reflect.Field pathListField = dalvik.system.BaseDexClassLoader.class.getDeclaredField("pathList");
                    pathListField.setAccessible(true);
                    Object pathList = pathListField.get(loader);
                    if (pathList != null) {
                        java.lang.reflect.Field nldField = pathList.getClass().getDeclaredField("nativeLibraryDirectories");
                        nldField.setAccessible(true);
                        @SuppressWarnings("unchecked")
                        java.util.List<java.io.File> nld = (java.util.List<java.io.File>) nldField.get(pathList);
                        if (nld != null && !nld.contains(csoDir)) {
                            java.util.List<java.io.File> newNld = new java.util.ArrayList<>(nld);
                            newNld.add(0, csoDir);
                            nldField.set(pathList, newNld);
                        }
                    }
                } catch (Throwable t) {
                    android.util.Log.w("TripoderWeChat", "Failed to add csoDir to DexPathList", t);
                }

                // Find zstd library path
                String zstdLib = null;
                if (artifactsLibDir != null) {
                    java.io.File zstd0 = new java.io.File(artifactsLibDir, "libzstd.so");
                    if (zstd0.exists()) zstdLib = zstd0.getAbsolutePath();
                }
                if (zstdLib == null) {
                    java.io.File zstd1 = new java.io.File(info.nativeLibraryDir, "libzstd.so");
                    if (zstd1.exists()) {
                        zstdLib = zstd1.getAbsolutePath();
                    } else {
                        java.io.File zstd2 = new java.io.File(info.nativeLibraryDir, "libwechatzstd.so");
                        if (zstd2.exists()) {
                            zstdLib = zstd2.getAbsolutePath();
                        }
                    }
                }
                final String finalZstdLib = zstdLib;

                // Decompress all 7 $cso.so files ONLY into csoDir.
                // NEVER write into info.nativeLibraryDir or artifactsLibDir, because those directories
                // are subject to RuntimeExecutionCode SHA-256 integrity verification against original APK stubs.
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(info.sourceDir)) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        java.util.zip.ZipEntry entry = entries.nextElement();
                        String entryName = entry.getName();
                        if (entryName.startsWith("lib/arm64-v8a/lib") && entryName.endsWith(".$cso.so")) {
                            String fileName = entryName.substring("lib/arm64-v8a/".length());
                            String realSoName = fileName.replace(".$cso.so", ".so");
                            java.io.File destCso = new java.io.File(csoDir, realSoName);

                            boolean needCso = !destCso.exists() || destCso.length() < 1024;
                            if (needCso) {
                                java.io.File tmpCso = new java.io.File(csoDir, fileName);
                                try (java.io.InputStream in = zip.getInputStream(entry);
                                     java.io.FileOutputStream out = new java.io.FileOutputStream(tmpCso)) {
                                    byte[] buf = new byte[65536];
                                    int r;
                                    while ((r = in.read(buf)) != -1) {
                                        out.write(buf, 0, r);
                                    }
                                }
                                boolean ok2 = decompressCsoNative(tmpCso.getAbsolutePath(), destCso.getAbsolutePath(), finalZstdLib);
                                android.util.Log.i("TripoderWeChat", "CSO decompressed into csoDir " + realSoName + ": " + ok2 + " (size=" + destCso.length() + ")");
                                tmpCso.delete();
                            }
                        }
                    }
                } catch (Throwable t) {
                    android.util.Log.w("TripoderWeChat", "Failed to extract/decompress CSO libraries", t);
                }

                java.util.function.Function<String, java.io.File> resolveLibraryFile = query -> {
                    String name = query.contains("/") ? new java.io.File(query).getName() : query;
                    String baseName = name;
                    if (baseName.startsWith("lib") && baseName.endsWith(".so")) {
                        baseName = baseName.substring(3, baseName.length() - 3);
                    }
                    String soName = (name.startsWith("lib") && name.endsWith(".so")) ? name : System.mapLibraryName(name);

                    // Strictly exempt all XWeb, Pinus, and WVA libraries from resolving into PathClassLoader (clns-16).
                    // These libraries belong exclusively to XWeb's DexClassLoader namespace (clns-17).
                    if (soName.contains("xweb") || soName.contains("pinus") || soName.contains("wva")) {
                        return null;
                    }

                    // 1. FIRST: Check csoDir for decompressed CSO libraries (to avoid returning 21-byte stubs from nativeLibraryDir)
                    java.io.File csoCandidate = new java.io.File(csoDir, soName);
                    if (csoCandidate.exists() && csoCandidate.length() > 1024) {
                        return csoCandidate;
                    }

                    // 2. Check loader.findLibrary to preserve canonical DT_NEEDED DSO identity in clns-16
                    if (findLibMethod != null) {
                        try {
                            String found = (String) findLibMethod.invoke(loader, baseName);
                            if (found != null) {
                                java.io.File f = new java.io.File(found);
                                if (f.exists() && f.length() > 32) return f;
                            }
                        } catch (Throwable ignored) {}
                    }

                    // 3. Check artifactsLibDir
                    if (artifactsLibDir != null) {
                        java.io.File af = new java.io.File(artifactsLibDir, soName);
                        if (af.exists() && af.length() > 32) return af;
                    }

                    // 4. Check nativeLibraryDir
                    java.io.File f1 = new java.io.File(info.nativeLibraryDir, soName);
                    if (f1.exists() && f1.length() > 32) return f1;

                    return csoCandidate.exists() ? csoCandidate : (f1.exists() ? f1 : null);
                };

                final java.util.Set<String> loadedLibraries = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

                java.util.function.Consumer<String> loadGuestLibrary = query -> {
                    java.io.File target = resolveLibraryFile.apply(query);
                    if (target == null) return;
                    String soName = target.getName();
                    if (!loadedLibraries.add(soName)) return;
                    if (nativeLoadMethod != null) {
                        try {
                            String err = (String) nativeLoadMethod.invoke(null, target.getAbsolutePath(), loader);
                            if (err != null) {
                                android.util.Log.w("TripoderWeChat", "nativeLoad error for " + soName + ": " + err);
                            } else {
                                android.util.Log.i("TripoderWeChat", "Loaded guest library: " + target.getAbsolutePath());
                            }
                        } catch (Throwable t) {
                            android.util.Log.w("TripoderWeChat", "Failed to load guest library: " + target.getAbsolutePath(), t);
                        }
                    }
                };

                // Explicitly set Flutter ApplicationInfoLoader.customAotSharedLibraryPath to decompressed libapp.so
                try {
                    Class<?> appInfoLoaderClass = Class.forName("io.flutter.embedding.engine.loader.ApplicationInfoLoader", false, loader);
                    java.lang.reflect.Field caslField = appInfoLoaderClass.getDeclaredField("customAotSharedLibraryPath");
                    caslField.setAccessible(true);
                    java.io.File realLibApp = resolveLibraryFile.apply("libapp.so");
                    if (realLibApp != null && realLibApp.exists() && realLibApp.length() > 1024) {
                        caslField.set(null, realLibApp.getAbsolutePath());
                        android.util.Log.i("TripoderWeChat", "Configured ApplicationInfoLoader.customAotSharedLibraryPath = " + realLibApp.getAbsolutePath());
                    }
                } catch (Throwable t) {
                    android.util.Log.w("TripoderWeChat", "Failed to configure ApplicationInfoLoader.customAotSharedLibraryPath", t);
                }

                // Prioritize csoDir in WeChat's central library loader lp.d0.a so d0.g("app") returns decompressed libapp.so
                try {
                    Class<?> d0Class = Class.forName("lp.d0", false, loader);
                    java.lang.reflect.Field aField = d0Class.getDeclaredField("a");
                    aField.setAccessible(true);
                    final String csoPath = csoDir.getAbsolutePath();
                    java.util.TreeSet<String> customTreeSet = new java.util.TreeSet<String>(new java.util.Comparator<String>() {
                        @Override
                        public int compare(String a, String b) {
                            if (a.equals(b)) return 0;
                            if (a.equals(csoPath)) return -1;
                            if (b.equals(csoPath)) return 1;
                            return a.compareTo(b);
                        }
                    }) {
                        @Override
                        public boolean remove(Object o) {
                            if (csoPath.equals(o)) return false;
                            return super.remove(o);
                        }
                    };
                    customTreeSet.add(csoPath);
                    @SuppressWarnings("unchecked")
                    java.util.Set<String> oldSet = (java.util.Set<String>) aField.get(null);
                    if (oldSet != null) {
                        customTreeSet.addAll(oldSet);
                    }
                    aField.set(null, customTreeSet);
                    android.util.Log.i("TripoderWeChat", "Configured lp.d0.a prioritized TreeSet with csoDir: " + csoPath);
                } catch (Throwable t) {
                    android.util.Log.w("TripoderWeChat", "Failed to configure lp.d0.a", t);
                }

                // Pre-load safe base native libraries in DT_NEEDED dependency order
                loadGuestLibrary.accept("libc++_shared.so");
                loadGuestLibrary.accept("libmmkv.so");
                loadGuestLibrary.accept("libwechatxlog.so");
                loadGuestLibrary.accept("libcso.so");
                loadGuestLibrary.accept("libmmskia.so");
                loadGuestLibrary.accept("libcrypto.so");
                loadGuestLibrary.accept("libWCDB.so");
                loadGuestLibrary.accept("librosseta.so");
                loadGuestLibrary.accept("libflutter.so");
                loadGuestLibrary.accept("libapp.so");
                loadGuestLibrary.accept("libmmv8.so");
                loadGuestLibrary.accept("libdart2cpp.so");
                loadGuestLibrary.accept("libwechatlv.so");
                loadGuestLibrary.accept("libliteapphelper.so");
                loadGuestLibrary.accept("libowl.so");
                loadGuestLibrary.accept("libpaymarshelper.so");

                // 3. Configure MMKV to disable Matrix APM plugins (TracePlugin, MemoryCanary, etc.)
                try {
                    java.io.File mmkvDir = new java.io.File(physicalData, "files/mmkv");
                    mmkvDir.mkdirs();
                    Class<?> mmkvClass = Class.forName("com.tencent.mmkv.MMKV", false, loader);
                    java.lang.reflect.Method initMethod = mmkvClass.getDeclaredMethod("initialize", String.class);
                    initMethod.invoke(null, mmkvDir.getAbsolutePath());

                    java.lang.reflect.Method defaultMMKVMethod = mmkvClass.getDeclaredMethod("defaultMMKV", int.class, String.class);
                    Object defaultMMKV = defaultMMKVMethod.invoke(null, 2, null);

                    if (defaultMMKV != null) {
                        java.lang.reflect.Method encodeBool = mmkvClass.getDeclaredMethod("encode", String.class, boolean.class);
                        java.lang.reflect.Method encodeInt = mmkvClass.getDeclaredMethod("encode", String.class, int.class);

                        encodeBool.invoke(defaultMMKV, "clicfg_matrix_traceplugin_switch", false);
                        encodeBool.invoke(defaultMMKV, "clicfg_matrix_memory_canary_enable", false);
                        encodeBool.invoke(defaultMMKV, "clicfg_matrix_trace_evil_method_switch", false);
                        encodeBool.invoke(defaultMMKV, "clicfg_matrix_trace_anr_enable", false);
                        encodeBool.invoke(defaultMMKV, "clicfg_matrix_trace_startup_enable", false);
                        encodeBool.invoke(defaultMMKV, "clicfg_matrix_startup_tracer_switch", false);
                        encodeBool.invoke(defaultMMKV, "clicfg_matrix_new_frame_tracer_switch", false);
                        encodeBool.invoke(defaultMMKV, "clicfg_matrix_traffic_safe_check", false);
                        encodeInt.invoke(defaultMMKV, "clicfg_matrix_trace_evil_method_enable", 0);
                        encodeInt.invoke(defaultMMKV, "ENABLE_BATTERY", 0);
                        android.util.Log.i("TripoderWeChat", "Successfully configured MMKV keys to disable Matrix APM plugins");
                    }
                } catch (Throwable t) {
                    android.util.Log.w("TripoderWeChat", "MMKV configuration skipped", t);
                }

                // 4. Intercept CsoLoader to safely skip APM libraries and load functional libraries cleanly
                try {
                    Class<?> csoLoaderClass = Class.forName("com.tencent.cso.CsoLoader", false, loader);
                    Class<?> ya6pClass = Class.forName("ya6.p", false, loader);
                    Class<?> ya6aClass = Class.forName("ya6.a", false, loader);

                    Object dummyA = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{ya6aClass}, (p, method, args) -> {
                        return csoDir.getAbsolutePath();
                    });
                    try {
                        java.lang.reflect.Field dField = csoLoaderClass.getDeclaredField("d");
                        dField.setAccessible(true);
                        if (dField.get(null) == null) {
                            dField.set(null, dummyA);
                        }
                    } catch (Throwable ignored) {}
                    try {
                        java.lang.reflect.Field eField = csoLoaderClass.getDeclaredField("e");
                        eField.setAccessible(true);
                        if (eField.get(null) == null) {
                            eField.set(null, dummyA);
                        }
                    } catch (Throwable ignored) {}

                    Object pProxy = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{ya6pClass}, (p, method, args) -> {
                        if ("invoke".equals(method.getName()) && args != null && args.length >= 1) {
                            Object target = args.length >= 2 ? args[1] : args[0];
                            if (target instanceof String) {
                                String str = (String) target;
                                String simpleName = str.contains("/") ? new java.io.File(str).getName() : str;
                                String soName = (simpleName.startsWith("lib") && simpleName.endsWith(".so")) ? simpleName : System.mapLibraryName(simpleName);

                                // Safely skip any Matrix / wxperf APM library
                                if (soName.contains("matrix") || soName.contains("wxperf")) {
                                    android.util.Log.i("TripoderWeChat", "CsoLoader safely skipping APM library: " + soName);
                                    return Boolean.FALSE;
                                }

                                // Strictly exempt all XWeb, Pinus, and WVA libraries from root PathClassLoader handling.
                                // These libraries belong strictly to XWeb's isolated DexClassLoader namespace (clns-17).
                                if (soName.contains("xweb") || soName.contains("pinus") || soName.contains("wva")) {
                                    android.util.Log.i("TripoderWeChat", "CsoLoader exempting XWeb/Pinus DSO from root loader: " + soName);
                                    new Thread(() -> {
                                        for (int k = 0; k < 60; k++) {
                                            try {
                                                if (patchXWebNative()) {
                                                    android.util.Log.i("TripoderWeChat", "TripoderXWebFastWatcher: patched libxwebcore.so");
                                                    break;
                                                }
                                            } catch (Throwable ignored) {}
                                            try { Thread.sleep(25); } catch (Throwable ignored) {}
                                        }
                                    }, "TripoderXWebFastWatcher").start();
                                    return Boolean.FALSE;
                                }

                                if (loadedLibraries.contains(soName)) {
                                    return Boolean.FALSE;
                                }

                                java.io.File physical = resolveLibraryFile.apply(str);
                                if (physical != null && physical.exists() && physical.length() > 32) {
                                    if (!loadedLibraries.add(soName)) return Boolean.FALSE;
                                    if (nativeLoadMethod != null) {
                                        try {
                                            String err = (String) nativeLoadMethod.invoke(null, physical.getAbsolutePath(), loader);
                                            if (err != null) {
                                                android.util.Log.w("TripoderWeChat", "nativeLoad error for " + soName + ": " + err);
                                            } else {
                                                android.util.Log.i("TripoderWeChat", "CsoLoader loaded physical: " + physical.getAbsolutePath());
                                            }
                                        } catch (Throwable t) {
                                            android.util.Log.w("TripoderWeChat", "Failed to load physical: " + physical.getAbsolutePath(), t);
                                        }
                                    }
                                    return Boolean.FALSE;
                                }

                                android.util.Log.i("TripoderWeChat", "CsoLoader delegating compressed library: " + soName);
                                return Boolean.TRUE;
                            }
                        }
                        return Boolean.FALSE;
                    });

                    java.lang.reflect.Field gField = csoLoaderClass.getDeclaredField("g");
                    gField.setAccessible(true);
                    gField.set(null, pProxy);

                    java.lang.reflect.Field iField = csoLoaderClass.getDeclaredField("i");
                    iField.setAccessible(true);
                    iField.set(null, pProxy);

                    android.util.Log.i("TripoderWeChat", "Configured CsoLoader proxies with csoDir: " + csoDir.getAbsolutePath());
                } catch (Throwable t) {
                    android.util.Log.w("TripoderWeChat", "Failed to configure CsoLoader", t);
                }
            } catch (Throwable t) {
                android.util.Log.w("TripoderWeChat", "Failed to configure WeChat compatibility", t);
            }
        }
        if ("com.tencent.tmgp.sgame".equals(info.packageName)) {
            android.util.Log.i("TripoderSGame", "Configuring SGame compatibility in single batch...");
            try {
                java.util.Map<java.lang.reflect.Executable, java.lang.reflect.Executable[]> batchTargets = new java.util.LinkedHashMap<>();
                Class<?> aceClass = null;
                try { aceClass = Class.forName("com.ace.gshell.AceApplication", false, loader); } catch (Throwable ignored) {}
                if (aceClass != null) {
                    for (java.lang.reflect.Method m : aceClass.getDeclaredMethods()) {
                        String name = m.getName();
                        if ("initialize".equals(name) || "ioctl".equals(name)) {
                            m.setAccessible(true);
                            batchTargets.put(m, new java.lang.reflect.Method[0]);
                        }
                    }
                }
                Class<?> tp2Class = null;
                try { tp2Class = Class.forName("com.tencent.tersafe2.TP2Sdk", false, loader); } catch (Throwable ignored) {}
                if (tp2Class != null) {
                    for (java.lang.reflect.Method m : tp2Class.getDeclaredMethods()) {
                        String name = m.getName();
                        if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && ("init".equals(name) || "initEx".equals(name) || "ioctl".equals(name))) {
                            m.setAccessible(true);
                            batchTargets.put(m, new java.lang.reflect.Method[0]);
                        }
                    }
                }
                Class<?> tssClass = null;
                try { tssClass = Class.forName("com.tencent.tp.TssSdk", false, loader); } catch (Throwable ignored) {}
                if (tssClass != null) {
                    for (java.lang.reflect.Method m : tssClass.getDeclaredMethods()) {
                        String name = m.getName();
                        if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && ("init".equals(name) || "initEx".equals(name))) {
                            m.setAccessible(true);
                            batchTargets.put(m, new java.lang.reflect.Method[0]);
                        }
                    }
                }

                if (!batchTargets.isEmpty()) {
                    java.util.Map<java.lang.reflect.Executable, ManagedHook.Handle> handles = ManagedHook.prepareBatch(batchTargets);
                    java.util.Map<ManagedHook.Handle, ManagedHook.Handler> handlers = new java.util.LinkedHashMap<>();
                    for (var entry : handles.entrySet()) {
                        java.lang.reflect.Method m = (java.lang.reflect.Method) entry.getKey();
                        ManagedHook.Handle h = entry.getValue();
                        handlers.put(h, call -> {
                            Class<?> rt = m.getReturnType();
                            if (rt == int.class) return Integer.valueOf(0);
                            if (rt == String.class) return "";
                            if (rt == byte[].class) return new byte[0];
                            return null;
                        });
                    }
                    ManagedHook.enableBatch(handlers);
                    android.util.Log.i("TripoderSGame", "Successfully installed batch hooks for " + handlers.size() + " targets");
                }
            } catch (Throwable t) {
                android.util.Log.w("TripoderSGame", "Failed to install batch hooks", t);
            }
        }
    }
    public static void publishFactory(AppComponentFactory factory){
        mainThread();
        try {
            java.lang.reflect.Field clField = FixedFrameworkMembers.field(4,2,"mClassLoader","Ljava/lang/ClassLoader;",false);
            android.util.Log.i("TripoderCL", "publishFactory ENTRY: mClassLoader=" + FixedFrameworkMembers.get(clField, packageInfoNative()));
        } catch (Throwable t) { android.util.Log.e("TripoderCL", "publishFactory log err", t); }
        ApplicationBinding.check();
        publishFactoryNative(java.util.Objects.requireNonNull(factory));
        ApplicationBinding.check();
    }
    /** Evaluate the declared selector through the SDK after logical binding.
     * The SDK owns both its successful result and its exception contract. */
    public static AppComponentFactory createFactory(ApplicationInfo info,ClassLoader initial)throws Exception{
        mainThread();ApplicationBinding.check();Object logical=packageInfoNative();
        java.lang.reflect.Field clField=FixedFrameworkMembers.field(4,2,"mClassLoader","Ljava/lang/ClassLoader;",false);
        android.util.Log.i("TripoderCL", "createFactory ENTRY: mClassLoader=" + FixedFrameworkMembers.get(clField, logical));
        if(logical==null||info==null||initial==null
                ||FixedFrameworkMembers.get(FixedFrameworkMembers.field(8,2,"mApplicationInfo","Landroid/content/pm/ApplicationInfo;",false),logical)!=info
                ||FixedFrameworkMembers.get(clField,logical)!=initial
                ||FixedFrameworkMembers.get(FixedFrameworkMembers.field(5,2,"mDefaultClassLoader","Ljava/lang/ClassLoader;",false),logical)!=initial)
            throw new SecurityException("APPLICATION_FACTORY_PREPARED_INPUTS_REQUIRED");
        if(factoryEvaluationAttempted)throw new IllegalStateException("APPLICATION_FACTORY_ALREADY_EVALUATED");
        factoryEvaluationAttempted=true;
        java.lang.reflect.Field factoryField=FixedFrameworkMembers.field(7,2,"mAppComponentFactory","Landroid/app/AppComponentFactory;",false);
        Object prepared=FixedFrameworkMembers.get(factoryField,logical);
        if(!(prepared instanceof AppComponentFactory)||prepared.getClass().getClassLoader()!=AppComponentFactory.class.getClassLoader())throw new SecurityException("APPLICATION_PREPARED_SDK_FACTORY_REQUIRED");
        AppComponentFactory result=(AppComponentFactory)FixedFrameworkMembers.call(FixedFrameworkMembers.method(2,"createAppFactory","(Landroid/content/pm/ApplicationInfo;Ljava/lang/ClassLoader;)Landroid/app/AppComponentFactory;",false),logical,info,initial);
        android.util.Log.i("TripoderCL", "createFactory AFTER CALL: mClassLoader=" + FixedFrameworkMembers.get(clField, logical) + " result=" + result);
        if(FixedFrameworkMembers.get(clField, logical)==null){
            android.util.Log.w("TripoderCL", "mClassLoader cleared during factory evaluation, restoring initial loader");
            FixedFrameworkMembers.set(clField, logical, initial);
        }
        java.lang.reflect.Field defaultClField=FixedFrameworkMembers.field(5,2,"mDefaultClassLoader","Ljava/lang/ClassLoader;",false);
        if(FixedFrameworkMembers.get(defaultClField, logical)==null){
            android.util.Log.w("TripoderCL", "mDefaultClassLoader cleared during factory evaluation, restoring initial loader");
            FixedFrameworkMembers.set(defaultClField, logical, initial);
        }
        ApplicationBinding.check();
        if(FixedFrameworkMembers.get(factoryField,logical)!=prepared)throw new SecurityException("APPLICATION_FACTORY_OWNER_CHANGED_DURING_EVALUATION");
        java.util.Objects.requireNonNull(result,"APPLICATION_SDK_FACTORY_REQUIRED");factoryPreparedOwnerVerified=true;factoryEvaluationCompleted=true;return result;
    }
    /** Fixed boundary observations; never returns framework roots or guest data. */
    public static java.util.Map<String,Boolean> factoryEvaluationEvidence(){return java.util.Map.of("attempted",factoryEvaluationAttempted,"completed",factoryEvaluationCompleted,"preparedOwnerVerified",factoryPreparedOwnerVerified);}
    public static void publishLoader(ClassLoader loader){mainThread();ApplicationBinding.check();publishLoaderNative(java.util.Objects.requireNonNull(loader));ApplicationBinding.check();}
    public static native Application current();
    private static native void attachNative(Context context);
    private static native void checkNative();
    /** The loader the guest itself publishes on its LoadedApk; equals the runtime loader until the guest replaces it. */
    private static native ClassLoader loaderNative();
    public static ClassLoader loader(){return loaderNative();}
    /** The guest's LoadedApk: the object a real application context exposes as {@code mPackageInfo}. */
    private static native Object packageInfoNative();
    public static Object packageInfo(){return packageInfoNative();}
    public static java.util.Map<String,Object> resourceRelations(Context context,Resources view,Resources initial)throws Exception{return ApplicationBinding.resourceRelations(context,view,initial);}
    public static void requireLogicalContext(Context context){ApplicationBinding.requireLogicalContext(context);}
    public static java.util.List<android.content.pm.ProviderInfo> providerInstallationBatch(){mainThread();return ApplicationBinding.providerInstallationBatch();}
    /** The Application the guest publishes on that LoadedApk; hot-fix proxies replace it during onCreate. */
    private static native Application installedApplicationNative();
    public static Application installedApplication(){return installedApplicationNative();}
    private static native void publishApplicationNative(Application application);
    /** Commit the Application explicitly exposed by the guest's application-context contract after onCreate. */
    public static void publishApplication(Application application){mainThread();if(application==null)throw new IllegalArgumentException("APPLICATION_PUBLICATION_REQUIRED");publishApplicationNative(application);}
    private static native void closeViewNative();
    public static native String reportNative();
    private static void mainThread(){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("APPLICATION_MAIN_THREAD_REQUIRED");
    }
    /** Before any guest factory, class initializer or constructor executes.
     * No guest instance exists yet; the logical view is explicitly null. */
    public static void prepareOwnership(Application host,FrameworkAccess.Members members){
        if(host==null||members==null)throw new IllegalArgumentException("APPLICATION_FIXED_IDENTITY_REQUIRED");
        neutralizeSystemScout();
        prepareOwnershipNative(host,members.fields,members.methods);
    }
    private static void neutralizeSystemScout() {
        try {
            neutralizeScoutNative();
            android.util.Log.i("TripoderScout", "Executed neutralizeScoutNative");
        } catch (Throwable t) {
            android.util.Log.w("TripoderScout", "neutralizeScoutNative failed: " + t.getMessage());
        }
        try {
            // 1. Unregister application scout thread from system MQS daemon
            try {
                Class<?> delegateClass = Class.forName("miui.mqsas.sdk.MQSEventManagerDelegate");
                java.lang.reflect.Method getInstanceMethod = delegateClass.getMethod("getInstance");
                Object delegate = getInstanceMethod.invoke(null);
                if (delegate != null) {
                    java.lang.reflect.Method unregisterMethod = delegateClass.getMethod("unregisterApplicationScoutThread", int.class);
                    unregisterMethod.invoke(delegate, Process.myPid());
                    android.util.Log.i("TripoderScout", "Unregistered from MQSEventManagerDelegate");
                }
            } catch (Throwable t) {
                android.util.Log.w("TripoderScout", "MQSEventManagerDelegate unregister skipped: " + t.getMessage());
            }

            // 2. Disable ScoutUtils.ENABLED_SCOUT
            try {
                Class<?> scoutUtilsClass = Class.forName("miui.mqsas.scout.ScoutUtils");
                java.lang.reflect.Field esField = scoutUtilsClass.getDeclaredField("ENABLED_SCOUT");
                esField.setAccessible(true);
                esField.setBoolean(null, false);
                android.util.Log.i("TripoderScout", "Disabled ScoutUtils.ENABLED_SCOUT");
            } catch (Throwable t) {
                android.util.Log.w("TripoderScout", "ScoutUtils.ENABLED_SCOUT disable skipped: " + t.getMessage());
            }

            // 3. Configure ActivityThreadImpl Scout parameters
            try {
                Class<?> atiClass = Class.forName("android.app.ActivityThreadImpl");
                try {
                    java.lang.reflect.Field sdpField = atiClass.getDeclaredField("sDisablePackage");
                    sdpField.setAccessible(true);
                    sdpField.setBoolean(null, true);
                } catch (Throwable ignored) {}

                try {
                    java.lang.reflect.Field listField = atiClass.getDeclaredField("ScoutDisablePackageList");
                    listField.setAccessible(true);
                    listField.set(null, new java.util.AbstractList<String>() {
                        @Override public String get(int index) { return ""; }
                        @Override public int size() { return 1; }
                        @Override public boolean contains(Object o) { return true; }
                    });
                    android.util.Log.i("TripoderScout", "Replaced ScoutDisablePackageList with omni-matching list");
                } catch (Throwable ignored) {}

                try {
                    java.lang.reflect.Field smField = atiClass.getDeclaredField("mAppScoutStateMachine");
                    smField.setAccessible(true);
                    Object sm = smField.get(null);
                    if (sm != null) {
                        try {
                            java.lang.reflect.Method quitNowMethod = sm.getClass().getMethod("quitNow");
                            quitNowMethod.invoke(sm);
                        } catch (Throwable t1) {
                            try {
                                java.lang.reflect.Method quitMethod = sm.getClass().getMethod("quit");
                                quitMethod.invoke(sm);
                            } catch (Throwable t2) {}
                        }
                        smField.set(null, null);
                        android.util.Log.i("TripoderScout", "Halted and cleared mAppScoutStateMachine");
                    }
                } catch (Throwable ignored) {}

                try {
                    java.lang.reflect.Field stField = atiClass.getDeclaredField("mAppScoutThread");
                    stField.setAccessible(true);
                    Object st = stField.get(null);
                    if (st instanceof android.os.HandlerThread) {
                        ((android.os.HandlerThread) st).quit();
                        android.util.Log.i("TripoderScout", "Quit mAppScoutThread HandlerThread");
                    }
                    stField.set(null, null);
                } catch (Throwable ignored) {}
            } catch (Throwable t) {
                android.util.Log.w("TripoderScout", "ActivityThreadImpl scout fields configuration skipped: " + t.getMessage());
            }
        } catch (Throwable t) {
            android.util.Log.w("TripoderScout", "neutralizeSystemScout failed", t);
        }
    }
    /** Publish the pending logical reflection view only after ownership capture. */
    public static void beginView(){beginViewNative();}
    /**
     * @param loader the loader the platform publishes as the application loader; the guest may replace it during
     *               attachBaseContext with a hot-fix/plugin loader
     * @param owner  the verified defining loader of this installation's DEX sequence; every published loader must
     *               resolve inside it, which is what keeps a replacement from escaping the installation
     */
    public static Context create(Application host,Application guest,ApplicationInfo info,ClassLoader loader,ClassLoader owner,
        AppComponentFactory factory,Resources resources){
        return create(host,guest,info,loader,owner,factory,resources,false);
    }
    public static Context createIsolated(Application host,Application guest,ApplicationInfo info,ClassLoader loader,AppComponentFactory factory,Resources resources){
        return create(host,guest,info,loader,loader,factory,resources,true);
    }
    private static Context create(Application host,Application guest,ApplicationInfo info,ClassLoader loader,ClassLoader owner,AppComponentFactory factory,Resources resources,boolean isolated){
        mainThread();
        if(host==null||guest==null||host==guest||info==null||loader==null||owner==null||factory==null||resources==null||
            !fixedUid(host,info,isolated)||info.packageName==null||info.dataDir==null)
            throw new IllegalArgumentException("APPLICATION_FIXED_IDENTITY_REQUIRED");
        try{
            if(Class.forName(guest.getClass().getName(),false,loader)!=guest.getClass())
                throw new IllegalArgumentException("APPLICATION_SELECTED_LOADER_IDENTITY");
        }catch(ClassNotFoundException error){throw new IllegalArgumentException("APPLICATION_SELECTED_LOADER_IDENTITY",error);}
        ApplicationBinding.check();return createNative(host,guest,info,loader,owner,factory,resources);
    }
    public static void attach(Context context){mainThread();ApplicationBinding.check();attachNative(context);ApplicationBinding.check();}
    public static void check(){mainThread();ApplicationBinding.check();checkNative();}
    /** Probe teardown only. Roots/code remain resident; this is not object retirement. */
    public static void closeProbeView(){mainThread();ApplicationBinding.restoreProbe();closeViewNative();}

    static final class TripoderUncaughtHandler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler delegate;
        TripoderUncaughtHandler(Thread.UncaughtExceptionHandler delegate) {
            this.delegate = delegate;
        }
        @Override
        public void uncaughtException(Thread t, Throwable e) {
            android.util.Log.e("TripoderUncaught", "=== UNCAUGHT EXCEPTION on thread [" + t.getName() + "] ===", e);
            if (delegate != null) {
                delegate.uncaughtException(t, e);
            }
        }
    }
}
