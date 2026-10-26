package dev.tripoder.runtime;

import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import java.io.File;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Exact signed-install files as read-only FDs, never private authority paths. */
final class RuntimeIsolatedCode {
    private static final long MAX_DEX=64L*1024*1024,MAX_TOTAL=256L*1024*1024;
    private record Dex(File path,ArtifactSet.FileRecord entry){}
    private static List<Dex> dex(ApkStore.BundleInstall install)throws java.io.IOException{
        List<Dex> result=new ArrayList<>();for(int i=0;i<install.artifacts.parts.size();i++)for(var entry:RuntimeDexLoader.orderedDex(install.artifacts.parts.get(i),false))result.add(new Dex(new File(install.artifacts.directory,ArtifactSet.dexExtractedPath(i,entry.path)),entry));return result;
    }
    static void preflight(ApkStore.BundleInstall install)throws java.io.IOException{
        long total=0;int count=0;for(var target:dex(install)){var f=target.entry;if(f.size<=0||f.size>MAX_DEX)throw new UnsupportedOperationException("ISOLATED_DEX_SIZE_UNSUPPORTED");total+=f.size;count++;}
        if(count==0||total>MAX_TOTAL)throw new UnsupportedOperationException("ISOLATED_DEX_SET_UNSUPPORTED");
    }
    static Bundle publish(ApkStore.BundleInstall install,int ownerUid,RuntimePublishedNativeCode.Plan nativeCode,RuntimeIsolatedLibraries.Plan libraries,RuntimeIsolatedNativeService.Plan nativeService)throws Exception{
        preflight(install);Bundle out=new Bundle();var apks=new ArrayList<ParcelFileDescriptor>();var dex=new ArrayList<ParcelFileDescriptor>();out.putParcelableArrayList("apks",apks);out.putParcelableArrayList("dex",dex);
        if(!install.artifacts.nativeFiles.isEmpty()&&nativeCode==null)throw new SecurityException("ISOLATED_NATIVE_PUBLICATION_REQUIRED");
        out.putString("space",install.space);out.putString("installGeneration",install.generation);out.putString("descriptorSha",install.artifacts.descriptorSha);if(nativeCode!=null)out.putBundle("nativeCode",nativeCode.wire.deepCopy());if(libraries!=null)out.putBundle("libraries",libraries.wire.deepCopy());if(nativeService!=null)out.putBundle("nativeService",nativeService.wire.deepCopy());
        var apkHashes=new ArrayList<String>();var dexHashes=new ArrayList<String>();out.putStringArrayList("apkHashes",apkHashes);out.putStringArrayList("dexHashes",dexHashes);
        try{
            for(var part:install.artifacts.parts){apks.add(ParcelFileDescriptor.open(part.file,ParcelFileDescriptor.MODE_READ_ONLY));apkHashes.add(part.sha256);}
            for(var target:dex(install)){dex.add(ParcelFileDescriptor.open(target.path,ParcelFileDescriptor.MODE_READ_ONLY));dexHashes.add(target.entry.sha256);}
            ApplicationInfo info=new ApplicationInfo(install.info.applicationInfo);info.uid=ownerUid;info.dataDir="/data/user/0/"+install.info.packageName;info.nativeLibraryDir=null;info.sharedLibraryFiles=RuntimeIsolatedLibraries.files(libraries);
            out.putParcelable("info",info);return out;
        }catch(Exception error){closeDescriptors(out);throw error;}
    }
    static byte[] bytes(ParcelFileDescriptor fd,String hash,long max)throws Exception{
        long size=fd.getStatSize();if(size<=0||size>max)throw new SecurityException("ISOLATED_FD_SIZE_INVALID");
        byte[] bytes=new byte[(int)size];int at=0;while(at<bytes.length){int n=Os.pread(fd.getFileDescriptor(),bytes,at,bytes.length-at,at);if(n<=0)throw new java.io.EOFException("ISOLATED_FD_TRUNCATED");at+=n;}
        if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(hash))throw new SecurityException("ISOLATED_FD_DIGEST_CHANGED");return bytes;
    }
    static void verifyApk(ParcelFileDescriptor fd,String hash)throws Exception{
        long size=fd.getStatSize();if(size<=0||size>ArtifactSet.MAX_APK_BYTES)throw new SecurityException("ISOLATED_APK_SIZE_INVALID");
        MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[65536];long at=0;while(at<size){int n=Os.pread(fd.getFileDescriptor(),bytes,0,(int)Math.min(bytes.length,size-at),at);if(n<=0)throw new java.io.EOFException("ISOLATED_APK_TRUNCATED");md.update(bytes,0,n);at+=n;}
        if(!HexFormat.of().formatHex(md.digest()).equals(hash))throw new SecurityException("ISOLATED_APK_DIGEST_CHANGED");
    }
    static ClassLoader loader(Bundle input,RuntimePublishedNativeCode.Verified nativeCode)throws Exception{
        var dex=input.getParcelableArrayList("dex",ParcelFileDescriptor.class);var hashes=input.getStringArrayList("dexHashes");
        if(dex==null||hashes==null||dex.isEmpty()||dex.size()!=hashes.size()||dex.size()>256)throw new SecurityException("ISOLATED_DEX_INPUT_INVALID");
        List<ByteBuffer> buffers=new ArrayList<>();long total=0;
        for(int i=0;i<dex.size();i++){byte[] raw=bytes(dex.get(i),hashes.get(i),MAX_DEX);total+=raw.length;if(total>MAX_TOTAL)throw new SecurityException("ISOLATED_DEX_SET_TOO_LARGE");ByteBuffer buffer=ByteBuffer.allocateDirect(raw.length);buffer.put(raw).flip();buffers.add(buffer.asReadOnlyBuffer());}
        ClassLoader boot=RuntimeIsolatedCode.class.getClassLoader().getParent();var info=input.getParcelable("info",ApplicationInfo.class);if(info==null)throw new SecurityException("ISOLATED_INFO_REQUIRED");
        // Library loaders exist before the guest loader, exactly as in an application loader's construction.
        var libraries=RuntimeIsolatedLibraries.open(input.getBundle("libraries"),boot,nativeCode==null?"":nativeCode.search,nativeCode==null?"":nativeCode.permitted,info.targetSdkVersion);
        ClassLoader loader=nativeCode==null?new dalvik.system.InMemoryDexClassLoader(buffers.toArray(ByteBuffer[]::new),boot):new dalvik.system.InMemoryDexClassLoader(buffers.toArray(ByteBuffer[]::new),nativeCode.search,boot);
        dev.tripoder.selfhook.ClassLoaderNamespaceAccess.attachSharedLibraries(loader,libraries.before,libraries.after);
        if(nativeCode!=null)dev.tripoder.selfhook.ClassLoaderNamespaceAccess.initializeInMemory(loader,info.targetSdkVersion,nativeCode.search,nativeCode.permitted,libraries.nativeNames);
        else if(!libraries.nativeNames.isEmpty())throw new SecurityException("ISOLATED_PLATFORM_NATIVE_LIBRARY_WITHOUT_GUEST_NATIVE_NOT_IMPLEMENTED");
        return loader;
    }
    static void closeDescriptors(Bundle bundle){
        for(String key:List.of("apks","dex")){var values=bundle.getParcelableArrayList(key,ParcelFileDescriptor.class);if(values!=null)for(var fd:values)try{fd.close();}catch(java.io.IOException error){android.util.Log.w("TripoderIsolated","FD_CLOSE_FAILED",error);}}
    }
    private RuntimeIsolatedCode(){}
}
