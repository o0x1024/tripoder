package dev.tripoder.runtime;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;

/** Resolve typed references with the SDK over sealed code; never loads a guest factory or class. */
final class RuntimeManifestFilters {
    private RuntimeManifestFilters(){}
    static final class ResourcesUnavailable extends IllegalStateException {
        ResourcesUnavailable(Throwable cause){super("MANIFEST_FILTER_RESOURCES_UNAVAILABLE",cause);}
    }
    static void prepare(Context host,ArtifactSet artifacts,PackageInfo parsed){
        if(parsed.applicationInfo==null||!artifacts.packageName.equals(parsed.packageName)||!artifacts.packageName.equals(parsed.applicationInfo.packageName))throw new SecurityException("FILTER_RESOURCE_PACKAGE_IDENTITY");
        ApplicationInfo code=new ApplicationInfo(parsed.applicationInfo);
        code.sourceDir=artifacts.base.getPath();code.publicSourceDir=code.sourceDir;
        String[] splits=artifacts.parts.stream().skip(1).map(part->part.file.getPath()).toArray(String[]::new);
        code.splitSourceDirs=splits.length==0?null:splits.clone();code.splitPublicSourceDirs=splits.length==0?null:splits.clone();
        Resources[] resources={null};
        artifacts.prepareFilters(id->{
            try{
                if(resources[0]==null)resources[0]=host.getPackageManager().getResourcesForApplication(code);
                return resources[0].getInteger(id);
            }catch(PackageManager.NameNotFoundException|RuntimeException error){throw new ResourcesUnavailable(error);}
        });
    }
}
