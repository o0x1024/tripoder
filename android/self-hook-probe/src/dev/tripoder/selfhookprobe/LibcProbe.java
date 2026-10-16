package dev.tripoder.selfhookprobe;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Reuses production C path policy only inside this owned, offline process. */
final class LibcProbe {
    private static native String runNative(String data,String canonicalData,String artifacts,String canonicalArtifacts,
        String packageName,String logicalDirectory,String concurrentFile,String foreignOwnedPath,String evidenceDirectory);
    static JSONObject run(Context context,File evidence) throws Exception {
        File data=new File(context.getApplicationInfo().dataDir);
        File local=new File(context.getFilesDir(),"fixed-libc-"+evidence.getName());
        File artifacts=new File(evidence,"libc-artifacts");
        if(!local.mkdir()||!artifacts.mkdir())throw new IllegalStateException("LIBC_FIXTURE_DIRECTORY_EXISTS");
        if(!context.getFilesDir().getCanonicalPath().equals(new File(data,"files").getCanonicalPath()))
            throw new IllegalStateException("LIBC_FIXTURE_DATA_LAYOUT");
        File concurrent=new File(local,"concurrent");
        write(concurrent,"test");write(new File(artifacts,"seed.dat"),"artifact");
        String logical="/data/data/"+context.getPackageName()+"/files/"+local.getName();
        return new JSONObject(runNative(data.getAbsolutePath(),data.getCanonicalPath(),
            artifacts.getAbsolutePath(),artifacts.getCanonicalPath(),context.getPackageName(),logical,
            concurrent.getAbsolutePath(),new File(evidence,"forbidden-by-policy").getAbsolutePath(),evidence.getAbsolutePath()));
    }
    private static void write(File file,String value) throws Exception {
        if(!file.createNewFile())throw new IllegalStateException("LIBC_FIXTURE_EXISTS");
        try(FileOutputStream out=new FileOutputStream(file)){out.write(value.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
    }
}
