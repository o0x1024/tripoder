package dev.tripoder.hooklab;

import android.content.pm.PackageManager;

/** Forwarding adapter for the SDK's abstract PackageManager surface; no Binder replacement. */
@SuppressWarnings("deprecation")
class ForwardingPackageManager extends PackageManager {
    final PackageManager delegate;
    ForwardingPackageManager(PackageManager delegate) { this.delegate=delegate; }
    @Override public void addPackageToPreferred(java.lang.String p0) { delegate.addPackageToPreferred(p0); }
    @Override public boolean addPermission(android.content.pm.PermissionInfo p0) { return delegate.addPermission(p0); }
    @Override public boolean addPermissionAsync(android.content.pm.PermissionInfo p0) { return delegate.addPermissionAsync(p0); }
    @Override public void addPreferredActivity(android.content.IntentFilter p0, int p1, android.content.ComponentName[] p2, android.content.ComponentName p3) { delegate.addPreferredActivity(p0, p1, p2, p3); }
    @Override public boolean canRequestPackageInstalls() { return delegate.canRequestPackageInstalls(); }
    @Override public java.lang.String[] canonicalToCurrentPackageNames(java.lang.String[] p0) { return delegate.canonicalToCurrentPackageNames(p0); }
    @Override public int checkPermission(java.lang.String p0, java.lang.String p1) { return delegate.checkPermission(p0, p1); }
    @Override public int checkSignatures(int p0, int p1) { return delegate.checkSignatures(p0, p1); }
    @Override public int checkSignatures(java.lang.String p0, java.lang.String p1) { return delegate.checkSignatures(p0, p1); }
    @Override public void clearInstantAppCookie() { delegate.clearInstantAppCookie(); }
    @Override public void clearPackagePreferredActivities(java.lang.String p0) { delegate.clearPackagePreferredActivities(p0); }
    @Override public java.lang.String[] currentToCanonicalPackageNames(java.lang.String[] p0) { return delegate.currentToCanonicalPackageNames(p0); }
    @Override public void extendVerificationTimeout(int p0, int p1, long p2) { delegate.extendVerificationTimeout(p0, p1, p2); }
    @Override public android.graphics.drawable.Drawable getActivityBanner(android.content.ComponentName p0) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getActivityBanner(p0); }
    @Override public android.graphics.drawable.Drawable getActivityBanner(android.content.Intent p0) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getActivityBanner(p0); }
    @Override public android.graphics.drawable.Drawable getActivityIcon(android.content.ComponentName p0) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getActivityIcon(p0); }
    @Override public android.graphics.drawable.Drawable getActivityIcon(android.content.Intent p0) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getActivityIcon(p0); }
    @Override public android.content.pm.ActivityInfo getActivityInfo(android.content.ComponentName p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getActivityInfo(p0, p1); }
    @Override public android.graphics.drawable.Drawable getActivityLogo(android.content.ComponentName p0) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getActivityLogo(p0); }
    @Override public android.graphics.drawable.Drawable getActivityLogo(android.content.Intent p0) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getActivityLogo(p0); }
    @Override public java.util.List<android.content.pm.PermissionGroupInfo> getAllPermissionGroups(int p0) { return delegate.getAllPermissionGroups(p0); }
    @Override public android.graphics.drawable.Drawable getApplicationBanner(android.content.pm.ApplicationInfo p0) { return delegate.getApplicationBanner(p0); }
    @Override public android.graphics.drawable.Drawable getApplicationBanner(java.lang.String p0) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getApplicationBanner(p0); }
    @Override public int getApplicationEnabledSetting(java.lang.String p0) { return delegate.getApplicationEnabledSetting(p0); }
    @Override public android.graphics.drawable.Drawable getApplicationIcon(android.content.pm.ApplicationInfo p0) { return delegate.getApplicationIcon(p0); }
    @Override public android.graphics.drawable.Drawable getApplicationIcon(java.lang.String p0) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getApplicationIcon(p0); }
    @Override public android.content.pm.ApplicationInfo getApplicationInfo(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getApplicationInfo(p0, p1); }
    @Override public java.lang.CharSequence getApplicationLabel(android.content.pm.ApplicationInfo p0) { return delegate.getApplicationLabel(p0); }
    @Override public android.graphics.drawable.Drawable getApplicationLogo(android.content.pm.ApplicationInfo p0) { return delegate.getApplicationLogo(p0); }
    @Override public android.graphics.drawable.Drawable getApplicationLogo(java.lang.String p0) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getApplicationLogo(p0); }
    @Override public android.content.pm.ChangedPackages getChangedPackages(int p0) { return delegate.getChangedPackages(p0); }
    @Override public int getComponentEnabledSetting(android.content.ComponentName p0) { return delegate.getComponentEnabledSetting(p0); }
    @Override public android.graphics.drawable.Drawable getDefaultActivityIcon() { return delegate.getDefaultActivityIcon(); }
    @Override public android.graphics.drawable.Drawable getDrawable(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) { return delegate.getDrawable(p0, p1, p2); }
    @Override public java.util.List<android.content.pm.ApplicationInfo> getInstalledApplications(int p0) { return delegate.getInstalledApplications(p0); }
    @Override public java.util.List<android.content.pm.PackageInfo> getInstalledPackages(int p0) { return delegate.getInstalledPackages(p0); }
    @Override public java.lang.String getInstallerPackageName(java.lang.String p0) { return delegate.getInstallerPackageName(p0); }
    @Override public byte[] getInstantAppCookie() { return delegate.getInstantAppCookie(); }
    @Override public int getInstantAppCookieMaxBytes() { return delegate.getInstantAppCookieMaxBytes(); }
    @Override public android.content.pm.InstrumentationInfo getInstrumentationInfo(android.content.ComponentName p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getInstrumentationInfo(p0, p1); }
    @Override public android.content.Intent getLaunchIntentForPackage(java.lang.String p0) { return delegate.getLaunchIntentForPackage(p0); }
    @Override public android.content.Intent getLeanbackLaunchIntentForPackage(java.lang.String p0) { return delegate.getLeanbackLaunchIntentForPackage(p0); }
    @Override public java.lang.String getNameForUid(int p0) { return delegate.getNameForUid(p0); }
    @Override public int[] getPackageGids(java.lang.String p0) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getPackageGids(p0); }
    @Override public int[] getPackageGids(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getPackageGids(p0, p1); }
    @Override public android.content.pm.PackageInfo getPackageInfo(android.content.pm.VersionedPackage p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getPackageInfo(p0, p1); }
    @Override public android.content.pm.PackageInfo getPackageInfo(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getPackageInfo(p0, p1); }
    @Override public android.content.pm.PackageInstaller getPackageInstaller() { return delegate.getPackageInstaller(); }
    @Override public int getPackageUid(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getPackageUid(p0, p1); }
    @Override public java.lang.String[] getPackagesForUid(int p0) { return delegate.getPackagesForUid(p0); }
    @Override public java.util.List<android.content.pm.PackageInfo> getPackagesHoldingPermissions(java.lang.String[] p0, int p1) { return delegate.getPackagesHoldingPermissions(p0, p1); }
    @Override public android.content.pm.PermissionGroupInfo getPermissionGroupInfo(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getPermissionGroupInfo(p0, p1); }
    @Override public android.content.pm.PermissionInfo getPermissionInfo(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getPermissionInfo(p0, p1); }
    @Override public int getPreferredActivities(java.util.List<android.content.IntentFilter> p0, java.util.List<android.content.ComponentName> p1, java.lang.String p2) { return delegate.getPreferredActivities(p0, p1, p2); }
    @Override public java.util.List<android.content.pm.PackageInfo> getPreferredPackages(int p0) { return delegate.getPreferredPackages(p0); }
    @Override public android.content.pm.ProviderInfo getProviderInfo(android.content.ComponentName p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getProviderInfo(p0, p1); }
    @Override public android.content.pm.ActivityInfo getReceiverInfo(android.content.ComponentName p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getReceiverInfo(p0, p1); }
    @Override public android.content.res.Resources getResourcesForActivity(android.content.ComponentName p0) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getResourcesForActivity(p0); }
    @Override public android.content.res.Resources getResourcesForApplication(android.content.pm.ApplicationInfo p0) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getResourcesForApplication(p0); }
    @Override public android.content.res.Resources getResourcesForApplication(java.lang.String p0) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getResourcesForApplication(p0); }
    @Override public android.content.pm.ServiceInfo getServiceInfo(android.content.ComponentName p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.getServiceInfo(p0, p1); }
    @Override public java.util.List<android.content.pm.SharedLibraryInfo> getSharedLibraries(int p0) { return delegate.getSharedLibraries(p0); }
    @Override public android.content.pm.FeatureInfo[] getSystemAvailableFeatures() { return delegate.getSystemAvailableFeatures(); }
    @Override public java.lang.String[] getSystemSharedLibraryNames() { return delegate.getSystemSharedLibraryNames(); }
    @Override public java.lang.CharSequence getText(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) { return delegate.getText(p0, p1, p2); }
    @Override public android.graphics.drawable.Drawable getUserBadgedDrawableForDensity(android.graphics.drawable.Drawable p0, android.os.UserHandle p1, android.graphics.Rect p2, int p3) { return delegate.getUserBadgedDrawableForDensity(p0, p1, p2, p3); }
    @Override public android.graphics.drawable.Drawable getUserBadgedIcon(android.graphics.drawable.Drawable p0, android.os.UserHandle p1) { return delegate.getUserBadgedIcon(p0, p1); }
    @Override public java.lang.CharSequence getUserBadgedLabel(java.lang.CharSequence p0, android.os.UserHandle p1) { return delegate.getUserBadgedLabel(p0, p1); }
    @Override public android.content.res.XmlResourceParser getXml(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) { return delegate.getXml(p0, p1, p2); }
    @Override public boolean hasSystemFeature(java.lang.String p0) { return delegate.hasSystemFeature(p0); }
    @Override public boolean hasSystemFeature(java.lang.String p0, int p1) { return delegate.hasSystemFeature(p0, p1); }
    @Override public boolean isInstantApp() { return delegate.isInstantApp(); }
    @Override public boolean isInstantApp(java.lang.String p0) { return delegate.isInstantApp(p0); }
    @Override public boolean isPermissionRevokedByPolicy(java.lang.String p0, java.lang.String p1) { return delegate.isPermissionRevokedByPolicy(p0, p1); }
    @Override public boolean isSafeMode() { return delegate.isSafeMode(); }
    @Override public java.util.List<android.content.pm.ResolveInfo> queryBroadcastReceivers(android.content.Intent p0, int p1) { return delegate.queryBroadcastReceivers(p0, p1); }
    @Override public java.util.List<android.content.pm.ProviderInfo> queryContentProviders(java.lang.String p0, int p1, int p2) { return delegate.queryContentProviders(p0, p1, p2); }
    @Override public java.util.List<android.content.pm.InstrumentationInfo> queryInstrumentation(java.lang.String p0, int p1) { return delegate.queryInstrumentation(p0, p1); }
    @Override public java.util.List<android.content.pm.ResolveInfo> queryIntentActivities(android.content.Intent p0, int p1) { return delegate.queryIntentActivities(p0, p1); }
    @Override public java.util.List<android.content.pm.ResolveInfo> queryIntentActivityOptions(android.content.ComponentName p0, android.content.Intent[] p1, android.content.Intent p2, int p3) { return delegate.queryIntentActivityOptions(p0, p1, p2, p3); }
    @Override public java.util.List<android.content.pm.ResolveInfo> queryIntentContentProviders(android.content.Intent p0, int p1) { return delegate.queryIntentContentProviders(p0, p1); }
    @Override public java.util.List<android.content.pm.ResolveInfo> queryIntentServices(android.content.Intent p0, int p1) { return delegate.queryIntentServices(p0, p1); }
    @Override public java.util.List<android.content.pm.PermissionInfo> queryPermissionsByGroup(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException { return delegate.queryPermissionsByGroup(p0, p1); }
    @Override public void removePackageFromPreferred(java.lang.String p0) { delegate.removePackageFromPreferred(p0); }
    @Override public void removePermission(java.lang.String p0) { delegate.removePermission(p0); }
    @Override public android.content.pm.ResolveInfo resolveActivity(android.content.Intent p0, int p1) { return delegate.resolveActivity(p0, p1); }
    @Override public android.content.pm.ProviderInfo resolveContentProvider(java.lang.String p0, int p1) { return delegate.resolveContentProvider(p0, p1); }
    @Override public android.content.pm.ResolveInfo resolveService(android.content.Intent p0, int p1) { return delegate.resolveService(p0, p1); }
    @Override public void setApplicationCategoryHint(java.lang.String p0, int p1) { delegate.setApplicationCategoryHint(p0, p1); }
    @Override public void setApplicationEnabledSetting(java.lang.String p0, int p1, int p2) { delegate.setApplicationEnabledSetting(p0, p1, p2); }
    @Override public void setComponentEnabledSetting(android.content.ComponentName p0, int p1, int p2) { delegate.setComponentEnabledSetting(p0, p1, p2); }
    @Override public void setInstallerPackageName(java.lang.String p0, java.lang.String p1) { delegate.setInstallerPackageName(p0, p1); }
    @Override public void updateInstantAppCookie(byte[] p0) { delegate.updateInstantAppCookie(p0); }
    @Override public void verifyPendingInstall(int p0, int p1) { delegate.verifyPendingInstall(p0, p1); }
}
