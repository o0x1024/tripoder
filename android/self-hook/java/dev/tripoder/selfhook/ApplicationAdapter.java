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
        prepareOwnershipNative(host,members.fields,members.methods);
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
}
