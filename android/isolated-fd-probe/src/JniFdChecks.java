package dev.tripoder.isolatedfdprobe;

import android.os.ParcelFileDescriptor;
import dalvik.system.InMemoryDexClassLoader;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;

/** One explicit APK-FD/ART path; a failure never retries through manual dlopen. */
final class JniFdChecks {
    static String run(ParcelFileDescriptor input,String expectedDigest,boolean named,String publicNativeDirectory,String publicCodeArchive)throws Exception{
        if(publicNativeDirectory!=null&&publicCodeArchive!=null)throw new SecurityException("ONE_EXPLICIT_NATIVE_SOURCE_REQUIRED");
        try(var executable=ParcelFileDescriptor.adoptFd(NativeChecks.executableCopy(input.getFd()))){
            byte[] apkBytes=ProbeContract.bytes(executable);
            if(!expectedDigest.equals(ProbeContract.digest(apkBytes)))throw new SecurityException("OWNED_EXECUTABLE_DIGEST_CHANGED");
            byte[] dex=null;var libraries=new HashMap<String,byte[]>();
            try(var archive=new ZipInputStream(new ByteArrayInputStream(apkBytes))){
                for(var entry=archive.getNextEntry();entry!=null;entry=archive.getNextEntry()){
                    if(entry.getName().equals("classes.dex")){if(dex!=null)throw new SecurityException("OWNED_DEX_DUPLICATE");dex=archive.readAllBytes();}
                    if(entry.getName().matches("lib/arm64-v8a/libowned_jni_(named_)?(leaf|driver)\\.so"))libraries.put(entry.getName().substring("lib/arm64-v8a/".length()),archive.readAllBytes());
                }
            }
            if(dex==null||dex.length==0)throw new SecurityException("OWNED_DEX_MISSING");
            String apk="/proc/self/fd/"+executable.getFd();
            String nativeRoot=apk+"!/lib/arm64-v8a";
            if(publicNativeDirectory!=null){
                if(!publicNativeDirectory.startsWith("/data/app/")||libraries.size()!=4)throw new SecurityException("OWNED_PUBLIC_CODE_REQUIRED");
                for(var entry:libraries.entrySet())if(!ProbeContract.digest(entry.getValue()).equals(ProbeContract.digest(Files.readAllBytes(Path.of(publicNativeDirectory,entry.getKey())))))throw new SecurityException("OWNED_PUBLIC_NATIVE_DIGEST_CHANGED");
                nativeRoot=publicNativeDirectory;
            }
            if(publicCodeArchive!=null){
                if(!publicCodeArchive.startsWith("/data/app/")||libraries.size()!=4)throw new SecurityException("OWNED_PUBLIC_APK_REQUIRED");
                try(var archive=new java.util.zip.ZipFile(publicCodeArchive)){
                    for(var entry:libraries.entrySet()){
                        var item=archive.getEntry("lib/arm64-v8a/"+entry.getKey());
                        if(item==null||item.getMethod()!=java.util.zip.ZipEntry.STORED)throw new SecurityException("OWNED_PUBLIC_APK_LIBRARY_SHAPE_CHANGED");
                        try(var bytes=archive.getInputStream(item)){if(!ProbeContract.digest(entry.getValue()).equals(ProbeContract.digest(bytes.readAllBytes())))throw new SecurityException("OWNED_PUBLIC_APK_NATIVE_DIGEST_CHANGED");}
                    }
                }
                nativeRoot=publicCodeArchive+"!/lib/arm64-v8a";
            }
            ByteBuffer code=ByteBuffer.allocateDirect(dex.length);code.put(dex).flip();
            ClassLoader loader=new InMemoryDexClassLoader(new ByteBuffer[]{code.asReadOnlyBuffer()},nativeRoot,IsolatedService.class.getClassLoader().getParent());
            Class<?> type=Class.forName("dev.tripoder.isolatedfdpayload.NativePayload",true,loader);
            if(type.getClassLoader()!=loader)throw new SecurityException("OWNED_JNI_DEFINING_LOADER_MISMATCH");
            JSONObject result=new JSONObject((String)type.getMethod("run",String.class,boolean.class).invoke(null,nativeRoot,named));
            result.put("definingLoaderExact",true).put("executableDigestMatches",true).put("nativeSource",publicCodeArchive!=null?"self-installed-public-apk":publicNativeDirectory==null?"sealed-apk-fd":"self-installed-public-native-directory").put("publicNativeDigestsMatch",publicNativeDirectory!=null||publicCodeArchive!=null);
            return result.toString();
        }
    }
    private JniFdChecks(){}
}
