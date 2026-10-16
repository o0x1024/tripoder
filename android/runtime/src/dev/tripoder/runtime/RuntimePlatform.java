package dev.tripoder.runtime;

import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import java.util.Arrays;

/** P0 admits only its measured platform. Expanding this list requires new acceptance evidence. */
final class RuntimePlatform {
    private static final String FINGERPRINT = "Xiaomi/madrid/madrid:17/CP2A.260605.016/OS4.0.24.0.XEOCNXM:user/release-keys";
    static void requireValidated() {
        if (Build.VERSION.SDK_INT != 37 || !FINGERPRINT.equals(Build.FINGERPRINT)
                || !Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")
                || Os.sysconf(OsConstants._SC_PAGESIZE) != 4096) {
            throw new IllegalStateException("PLATFORM_NOT_VALIDATED:" + Build.FINGERPRINT);
        }
        try{if(!ApkStore.digest(new java.io.File("/apex/com.android.runtime/lib64/bionic/libc.so")).equals("b963b539545b64aa8fbcaed3228d04bcb6466bfd47055aa16e4ccdcadfdc461e"))throw new SecurityException("LIBC_NOT_VALIDATED");}
        catch(Exception error){throw new IllegalStateException("NATIVE_PLATFORM_NOT_VALIDATED",error);}
        try{if(!ApkStore.digest(new java.io.File("/apex/com.android.art/lib64/libart.so")).equals("2d4a3575e5b71b537d89c176ac82ad3e32a56a3d43161009de3e728c962fab13"))throw new SecurityException("ART_NOT_VALIDATED");}
        catch(Exception error){throw new IllegalStateException("ART_PLATFORM_NOT_VALIDATED",error);}
        try{if(!ApkStore.digest(new java.io.File("/apex/com.android.runtime/bin/linker64")).equals("b6dd216b7a775b8f04c825cada286bfe7ddaeecf2c0c10e617287118965abc00"))throw new SecurityException("LINKER_NOT_VALIDATED");}
        catch(Exception error){throw new IllegalStateException("LINKER_PLATFORM_NOT_VALIDATED",error);}
    }
    private RuntimePlatform() {}
}
