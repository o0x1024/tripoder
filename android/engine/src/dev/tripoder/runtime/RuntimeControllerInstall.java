package dev.tripoder.runtime;

import android.os.*;
import android.system.*;
import java.util.*;

/** A trusted controller supplies bytes through read-only regular descriptors, never physical paths. */
final class RuntimeControllerInstall {
    static Bundle install(RuntimeBroker broker,Bundle input)throws Exception{
        RuntimeInstallRequest.validate(input);
        var names=input.getStringArrayList("names");var hashes=input.getStringArrayList("sha256");
        List<ApkStore.Source> sources=new ArrayList<>();
        for(int i=0;i<names.size();i++){
            ParcelFileDescriptor descriptor=input.getParcelable("fd"+i,ParcelFileDescriptor.class);
            var fd=descriptor.getFileDescriptor();var stat=Os.fstat(fd);
            if(!OsConstants.S_ISREG(stat.st_mode)||(Os.fcntlInt(fd,OsConstants.F_GETFL,0)&OsConstants.O_ACCMODE)!=OsConstants.O_RDONLY)throw new SecurityException("SOURCE_READ_ONLY_REGULAR_FILE_REQUIRED");
            if(stat.st_size<=0||stat.st_size>ArtifactSet.MAX_APK_BYTES||Os.lseek(fd,0,OsConstants.SEEK_CUR)!=0)throw new SecurityException("SOURCE_FILE_RANGE_INVALID");
            sources.add(new ApkStore.Source(names.get(i),hashes.get(i),()->new ParcelFileDescriptor.AutoCloseInputStream(descriptor)));
        }
        // All input validation finishes before admission. Endpoint closes every received FD, also on error.
        ApkStore.BundleInstall install=broker.apks.installForCaller(input.getString("operation"),"trusted-workbench",input.getString("space"),sources,input.getString("expectedGeneration"),ApkStore.NO_FAULT);
        Bundle out=new Bundle();out.putString("space",install.space);out.putString("package",install.info.packageName);out.putString("generation",install.generation);
        out.putString("sha256",install.sha256);out.putString("descriptorSha",install.artifacts.descriptorSha);out.putLong("version",install.artifacts.versionCode);return out;
    }
    private RuntimeControllerInstall(){}
}
