package dev.tripoder.runtime;

import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;

/** Install-time provenance of a guest package, answered the way Android answers for a sideloaded build.
 *
 *  <p>A guest copy is imported from a local APK file the user chose: no store delivered it, and the platform holds
 *  no install record for it. The truthful answer is therefore "no installer" ({@code null}) and an install source
 *  whose fields are all null with {@code PACKAGE_SOURCE_LOCAL_FILE} - exactly what the platform reports for a
 *  package installed from a local file.
 *
 *  <p>What must never happen: borrowing the provenance of the same-named package the device happens to have
 *  installed. That attributes delivery by a store to a copy no store delivered - a false statement handed to a
 *  third party's channel check - and it breaks AppOps/storage resolution of the real UID owner. Provenance is
 *  answered honestly instead, and capabilities that require an official source are declared unsupported rather
 *  than manufactured; see docs/identity-provenance-contract.md and docs/guest-provenance-capability-matrix.md.
 *
 *  <p>Self-identification (package, uid, version, signer) is answered from the verified guest archive and is not
 *  unknown: only the install-source facts are "unknown", because for this copy they genuinely are.
 */
final class GuestIdentity {
    /** Provenance plane: answered once, for both the SDK-facing facade and the framework call path.
     *
     *  The guest's own copy was imported from a local file, so no store delivered it and the platform holds no
     *  install record for it: the answer is {@code null}, which is Android's own answer for a sideload. The
     *  container's own package name must never be returned - "dev.tripoder.* installed me" is exactly the
     *  statement a channel check reads as an unofficial build, and the container is not even an installer of
     *  its own package.
     *
     *  The host package and WebView providers are infrastructure the platform really did install, so their own
     *  installer is the platform's to report. Every other name is outside the guest's package world: null.
     */
    /** One-run measurement authorized by the user on 2026-11-12: answer a store as the guest's installer to find
     *  out whether the guest's channel check is decided by local attribution at all. This deliberately crosses the
     *  honesty boundary of docs/identity-provenance-contract.md §6.4 and must stay false in any shipped build. */
    static boolean experimentStoreAttribution=false;
    static final String EXPERIMENT_INSTALLER="com.xiaomi.market";

    static String installerPackageName(RuntimeEngine engine,String packageName){
        if(engine==null||packageName==null)return null;
        IdentityAudit.note("installer",packageName);
        if(experimentStoreAttribution&&engine.packageName.equals(packageName)){
            android.util.Log.w("TripoderExperiment","installer->"+EXPERIMENT_INSTALLER+" pkg="+packageName);
            return EXPERIMENT_INSTALLER;
        }
        if(engine.packageName.equals(packageName))return null;
        if(!engine.host.getPackageName().equals(packageName)&&!engine.webView.owns(packageName))return null;
        try{
            return engine.host.getPackageManager().getInstallerPackageName(packageName);
        }catch(Throwable unavailable){
            // A package the platform never installed has no installer: null, as for a sideloaded app.
            android.util.Log.w("TripoderIdentity","installer unavailable pkg="+packageName,unavailable);
            return null;
        }
    }

    static InstallSourceInfo installSourceInfo(RuntimeEngine engine,String packageName)
            throws PackageManager.NameNotFoundException {
        if(engine==null||packageName==null)
            throw new PackageManager.NameNotFoundException(String.valueOf(packageName));
        IdentityAudit.note("installSource",packageName);
        if(experimentStoreAttribution&&engine.packageName.equals(packageName)){
            android.util.Log.w("TripoderExperiment","installSource->"+EXPERIMENT_INSTALLER+" pkg="+packageName);
            InstallSourceInfo forged=experimentStoreSource(packageName);
            if(forged!=null)return forged;
        }
        if(engine.packageName.equals(packageName))return importedFromLocalFile(packageName);
        try{
            return engine.host.getPackageManager().getInstallSourceInfo(packageName);
        }catch(PackageManager.NameNotFoundException absent){
            throw absent;
        }catch(Throwable unavailable){
            // No query on this platform: Android's own answer for a package it cannot resolve is the
            // name-not-found it would have thrown, never an unsupported operation the app cannot handle.
            android.util.Log.w("TripoderIdentity","installSource unavailable pkg="+packageName,unavailable);
            throw new PackageManager.NameNotFoundException(String.valueOf(packageName));
        }
    }

    /** The "a store delivered this copy" shape used only by the one-run experiment: the store name in every String
     *  field and the store source bit in the single int. Null when the class has no such constructor shape. */
    private static InstallSourceInfo experimentStoreSource(String packageName){
        int store=1;
        try{store=ApplicationInfo.class.getField("PACKAGE_SOURCE_STORE").getInt(null);}catch(Throwable absent){}
        for(java.lang.reflect.Constructor<?> candidate:InstallSourceInfo.class.getDeclaredConstructors()){
            Class<?>[] types=candidate.getParameterTypes();
            Object[] values=new Object[types.length];
            int ints=0;
            boolean buildable=true;
            for(int index=0;index<types.length;index++){
                if(types[index]==int.class){values[index]=store;ints++;}
                else if(types[index]==String.class)values[index]=EXPERIMENT_INSTALLER;
                else if(!types[index].isPrimitive())values[index]=null;
                else{buildable=false;break;}
            }
            if(!buildable||ints!=1)continue;
            try{
                candidate.setAccessible(true);
                return (InstallSourceInfo)candidate.newInstance(values);
            }catch(Throwable unreachable){
                RuntimeFailThrough.note("install-source","EXPERIMENT_SHAPE_UNREACHABLE");
                break;
            }
        }
        RuntimeFailThrough.note("install-source","EXPERIMENT_NO_SHAPE");
        return null;
    }

    /** The platform never handed this copy out through a store, so the only source that describes it is the file. */
    private static InstallSourceInfo importedFromLocalFile(String packageName)
            throws PackageManager.NameNotFoundException {
        int source=localFileSource();
        for(java.lang.reflect.Constructor<?> candidate:InstallSourceInfo.class.getDeclaredConstructors()){
            Class<?>[] types=candidate.getParameterTypes();
            Object[] values=new Object[types.length];
            boolean buildable=true;
            int sourceArguments=0;
            for(int index=0;index<types.length;index++){
                if(types[index]==int.class){values[index]=source;sourceArguments++;}
                else if(!types[index].isPrimitive())values[index]=null;
                else{buildable=false;break;}
            }
            // Every reference argument is null, so only the single int carries information and argument order
            // cannot be misread; a shape with zero or several int arguments is not this class' layout.
            if(!buildable||sourceArguments!=1)continue;
            try{
                candidate.setAccessible(true);
                return (InstallSourceInfo)candidate.newInstance(values);
            }catch(Throwable unreachable){
                RuntimeFailThrough.note("install-source","CONSTRUCTOR_UNREACHABLE");
                break;
            }
        }
        RuntimeFailThrough.note("install-source","NO_LOCAL_FILE_SHAPE");
        // Registering the trade-off: reporting the absence is the documented fallback (contract §2.2), and a
        // name-not-found is the only failure an install-source query is documented to raise.
        throw new PackageManager.NameNotFoundException(packageName);
    }

    /** {@code PACKAGE_SOURCE_LOCAL_FILE}, read reflectively because it is newer than the runtime's floor API level. */
    private static int localFileSource(){
        try{
            return ApplicationInfo.class.getField("PACKAGE_SOURCE_LOCAL_FILE").getInt(null);
        }catch(Throwable absent){
            return 2;
        }
    }

    static int checkSignatures(RuntimeEngine engine, String first, String second) {
        if (engine == null || first == null || second == null) return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
        IdentityAudit.note("checkSignatures", first + "|" + second);
        if (first.equals(second))
            return own(engine, first) ? PackageManager.SIGNATURE_MATCH : PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
        // Two different packages: only the platform can say whether they are signed alike.
        try {
            return engine.host.getPackageManager().checkSignatures(first, second);
        } catch (Throwable unavailable) {
            return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
        }
    }

    static int checkSignatures(RuntimeEngine engine, int first, int second) {
        IdentityAudit.note("checkSignatures", first + "|" + second);
        if (first == second && first == android.os.Process.myUid()) return PackageManager.SIGNATURE_MATCH;
        try {
            return engine.host.getPackageManager().checkSignatures(first, second);
        } catch (Throwable unavailable) {
            return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
        }
    }

    /** The uid this process really has: the guest and its host infrastructure both run under it. */
    static int packageUid(RuntimeEngine engine, String packageName) throws PackageManager.NameNotFoundException {
        if (engine == null || packageName == null)
            throw new PackageManager.NameNotFoundException(String.valueOf(packageName));
        if (own(engine, packageName)) return android.os.Process.myUid();
        throw new PackageManager.NameNotFoundException(packageName);
    }

    private static boolean own(RuntimeEngine engine, String packageName) {
        return engine.packageName.equals(packageName) || engine.host.getPackageName().equals(packageName);
    }

    private GuestIdentity() {}
}
