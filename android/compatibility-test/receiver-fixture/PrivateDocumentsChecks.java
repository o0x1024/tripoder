package dev.tripoder.closurefixture;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.ProviderInfo;
import java.util.List;

/** Instance settings never become a publication or cross-package capability. */
final class PrivateDocumentsChecks {
    private static ProviderInfo info(Activity activity,Class<?> type)throws Exception{return activity.getPackageManager().getProviderInfo(new ComponentName(activity,type),0);}
    private static boolean originalDeclaration(ProviderInfo value){return !value.exported&&value.readPermission==null&&value.writePermission==null&&!value.grantUriPermissions;}
    private static void install(Activity activity,ProviderInfo value)throws Exception{
        if(ProviderInstallationChecks.frameworkEntry==null)throw new AssertionError("OWNED_PROVIDER_ENTRY_NOT_OBSERVED");
        ProviderInstallationChecks.install(activity,ProviderInstallationChecks.frameworkEntry,ProviderInstallationChecks.frameworkThread,List.of(value),false);
    }
    static void run(Activity activity,ProviderInstallationChecks.Check check)throws Exception{
        ProviderInfo declared=info(activity,OwnedDocumentsProvider.class);
        check.test("private-documents-publication-keeps-original-private-manifest-policy",originalDeclaration(declared));
        check.test("omitted-private-documents-provider-has-no-bootstrap-side-effects",OwnedDocumentsProvider.normal.constructors.get()==0);
        install(activity,declared);
        var counts=OwnedDocumentsProvider.normal;OwnedDocumentsProvider instance=OwnedDocumentsProvider.original;
        check.test("private-documents-provider-runs-real-sdk-attach-and-onCreate-once",counts.constructors.get()==1&&counts.attachments.get()==1&&counts.creates.get()==1&&instance!=null);
        check.test("private-provider-instance-keeps-requested-document-permissions","android.permission.MANAGE_DOCUMENTS".equals(instance.getReadPermission())&&"android.permission.MANAGE_DOCUMENTS".equals(instance.getWritePermission()));
        check.test("instance-configuration-never-rewrites-canonical-publication-policy",originalDeclaration(info(activity,OwnedDocumentsProvider.class)));
        install(activity,declared);
        check.test("private-documents-provider-repeat-keeps-one-instance",counts.constructors.get()==1&&counts.attachments.get()==1&&counts.creates.get()==1&&OwnedDocumentsProvider.original==instance);
        ProviderInfo forged=info(activity,OwnedDocumentsProvider.Forged.class);
        for(int attempt=0;attempt<2;attempt++){
            boolean denied=false;try{install(activity,forged);}catch(Exception expected){for(Throwable cause=expected;cause!=null;cause=cause.getCause())if("PROVIDER_ATTACH_IDENTITY_CHANGED".equals(cause.getMessage()))denied=true;}
            var bad=OwnedDocumentsProvider.forged;
            check.test("private-provider-cannot-change-authority-or-retry-failed-attach-"+attempt,denied&&bad.constructors.get()==1&&bad.attachments.get()==1&&bad.creates.get()==0);
        }
        check.test("failed-private-provider-does-not-publish-forged-authority",activity.getPackageManager().resolveContentProvider(forged.authority+".foreign",0)==null);
    }
    private PrivateDocumentsChecks(){}
}
