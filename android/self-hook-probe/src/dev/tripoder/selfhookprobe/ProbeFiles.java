package dev.tripoder.selfhookprobe;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

final class ProbeFiles {
    static String asset(Context context,String name) throws Exception {
        try(InputStream in=context.getAssets().open(name)){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}
    }
    static String read(String path) throws Exception {
        try(InputStream in=new FileInputStream(path)){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}
    }
    static void write(File file,String content) throws Exception {
        try(FileOutputStream out=new FileOutputStream(file)){out.write(content.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
    }
    /** The final filename is the commit signal read by the host. Never expose
     * it empty or partially written; the permanent claim forbids republishing. */
    static void publishResult(File directory,String content) throws Exception {
        File result=new File(directory,"result.json"),claim=new File(directory,".result.claim"),part=new File(directory,".result.part");
        require(!result.exists()&&claim.createNewFile(),"RESULT_ALREADY_CLAIMED");
        require(part.createNewFile(),"RESULT_STAGING_ALREADY_EXISTS");
        write(part,content);
        Files.move(part.toPath(),result.toPath(),StandardCopyOption.ATOMIC_MOVE);
    }
    static String sha(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[65536];int count;
            while((count=in.read(buffer))!=-1)digest.update(buffer,0,count);}
        StringBuilder result=new StringBuilder();
        for(byte value:digest.digest())result.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        return result.toString();
    }
    static void require(boolean condition,String error){if(!condition)throw new IllegalStateException(error);}
}
