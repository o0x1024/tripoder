package dev.tripoder.provideridentity;

import android.content.Context;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

/** Shared metadata/resource contract, usable before any Activity or task exists. */
final class PackageMetadataChecks {
    private final Context context;
    private final JSONArray checks;
    private PackageMetadataChecks(Context context,JSONArray checks){this.context=context;this.checks=checks;}
    static void inspect(Context context,boolean baseline,JSONArray checks)throws Exception{new PackageMetadataChecks(context,checks).run(baseline);}
    private void run(boolean baseline)throws Exception{
        PackageManager pm = context.getPackageManager();
        String pkg = context.getPackageName();
        ApplicationInfo info = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA);
        ApplicationInfo label=new ApplicationInfo(info);label.nonLocalizedLabel=null;label.labelRes=0;label.name="OwnedNoLabel";
        check("package-label-keeps-platform-name-selection","OwnedNoLabel".contentEquals(pm.getApplicationLabel(label)));
        label.nonLocalizedLabel="  Owned label  ";check("package-label-keeps-nonlocalized-text","  Owned label  ".contentEquals(pm.getApplicationLabel(label)));
        check("application-metadata-from-manifest", info.metaData != null && "owned-installation".equals(info.metaData.getString("owned.label")));
        check("application-metadata-omitted-without-flag", pm.getApplicationInfo(pkg, 0).metaData == null);
        check("application-long-flags-preserve-metadata", "owned-installation".equals(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA)).metaData.getString("owned.label")));
        int resource = info.metaData.getInt("owned.xml");
        check("application-xml-resource-id-present", resource != 0);
        check("package-xml-null-info", ownedXml(pm.getXml(pkg, resource, null)));
        check("package-xml-application-info", ownedXml(pm.getXml(pkg, resource, info)));
        check("application-load-xml-metadata", ownedXml(info.loadXmlMetaData(pm, "owned.xml")));
        ComponentName component = new ComponentName(pkg, IdentityProvider.class.getName());
        var provider = pm.getProviderInfo(component, PackageManager.GET_META_DATA);
        check("provider-load-xml-metadata", ownedXml(provider.loadXmlMetaData(pm, "owned.xml")));
        check("provider-application-metadata-flag-bound", pm.getProviderInfo(component, 0).applicationInfo.metaData == null && "owned-installation".equals(provider.applicationInfo.metaData.getString("owned.label")));
        check("package-missing-xml-is-null", pm.getXml(pkg, 0x7fffEEEE, null) == null);
        if (!baseline) {
            // Tripoder protects its authoritative in-process record. Android may cache query objects.
            info.metaData.putString("owned.label", "tampered");
            check("application-metadata-copy-isolated", "owned-installation".equals(pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA).metaData.getString("owned.label")));
            ApplicationInfo forged = new ApplicationInfo(info);
            forged.sourceDir = "/not-an-installed-apk";
            forged.publicSourceDir = "/not-an-installed-apk";
            check("caller-paths-cannot-select-xml-source", ownedXml(pm.getXml(pkg, resource, forged)));
            forged.packageName = "dev.tripoder.notinstalled";
            check("xml-rejects-mismatched-application-identity", rejected(() -> pm.getXml(pkg, resource, forged), "XML_APPLICATION_IDENTITY_MISMATCH"));
            check("xml-other-package-never-delegates", rejected(() -> pm.getXml("dev.tripoder.notinstalled", resource, null), "OTHER_PACKAGE_QUERY_UNSUPPORTED"));
            check("application-unknown-long-flags-not-truncated", rejected(() -> pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(1L << 40)), "APPLICATION_INFO_FLAGS_UNSUPPORTED:0x10000000000"));
            ApplicationInfo contextInfo = context.getApplicationInfo();
            contextInfo.metaData.putString("owned.label", "context-tampered");
            check("context-metadata-cannot-mutate-package-record", "owned-installation".equals(pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA).metaData.getString("owned.label")));
        }
    }

    private static boolean ownedXml(XmlResourceParser parser) throws Exception {
        if (parser == null) return false;
        boolean root = false, child = false;
        try {
            for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event != XmlPullParser.START_TAG) continue;
                if (parser.getDepth() == 1) root = "paths".equals(parser.getName());
                if (parser.getDepth() == 2) child = "files-path".equals(parser.getName()) && "owned-fixture".equals(parser.getAttributeValue(null, "name")) && "payload/".equals(parser.getAttributeValue(null, "path"));
            }
            return root && child;
        } finally { parser.close(); }
    }

    private interface Action { void run() throws Exception; }
    private static boolean rejected(Action action, String expected) throws Exception {
        try { action.run(); return false; }
        catch (SecurityException | UnsupportedOperationException error) { return String.valueOf(error.getMessage()).contains(expected); }
    }
    private void check(String name, boolean passed) throws Exception {
        checks.put(new JSONObject().put("name", name).put("passed", passed));
        if (!passed) throw new AssertionError(name);
    }
}
