package dev.tripoder.vmprobe;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import org.json.JSONObject;

final class VmFiles {
    static void prepare(Context context, java.util.function.Consumer<String> progress) throws Exception {
        File root = context.getFilesDir();
        JSONObject media;
        try (InputStream input = context.getAssets().open("media.json")) {
            media = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        for (String name : new String[]{"vda.qcow2", "vdb.qcow2", "efi-code.fd", "efi-vars.fd"}) {
            File destination = new File(root, name);
            // Mutable disks are retained only after a completed, hash-verified initial import.
            File receipt = new File(root, name + ".imported");
            String expected = media.getJSONObject(name).getString("sha256");
            if (destination.isFile() && receipt.isFile() && Files.readString(receipt.toPath()).equals(expected)) continue;
            File source = new File(context.getExternalFilesDir(null), "import/" + name);
            if (!source.isFile()) throw new IOException("请准备镜像文件：" + source);
            progress.accept("正在校验并导入 " + name);
            File temporary = new File(root, name + ".incoming");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[1024 * 1024]; int count;
                while ((count = input.read(buffer)) != -1) { digest.update(buffer, 0, count); output.write(buffer, 0, count); }
                output.getFD().sync();
            }
            StringBuilder actual = new StringBuilder();
            for (byte value : digest.digest()) actual.append(String.format("%02x", value & 255));
            if (!actual.toString().equals(expected)) { temporary.delete(); throw new IOException("镜像摘要不匹配：" + name); }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(receipt.toPath(), expected);
        }
    }
    static synchronized void write(File file, String text) throws IOException {
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        Files.writeString(temporary.toPath(), text);
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
