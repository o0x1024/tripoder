package dev.tripoder.hooklab;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.*;

/** Bounded editor for this controlled guest's data directory, not arbitrary host files. */
final class GuestFiles {
    private final File root;
    GuestFiles(File root) { this.root = root; }
    private File resolve(String relative) throws Exception {
        if (relative == null || relative.isEmpty() || relative.length() > 240 || relative.startsWith("/") || relative.contains("\\")) throw new IOException("Invalid relative path");
        File current = root;
        for (String part : relative.split("/", -1)) {
            if (part.equals(".") || part.equals("..") || part.isEmpty()) throw new IOException("Path traversal rejected");
            current = new File(current, part);
            if (Files.isSymbolicLink(current.toPath())) throw new IOException("Symbolic links are unavailable");
        }
        if (!current.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IOException("Path escapes guest data");
        return current;
    }
    JSONObject operate(String operation, String path, String content) throws Exception {
        if (operation.equals("list")) {
            JSONArray entries = new JSONArray(); File[] files = root.listFiles();
            if (files != null) for (File f : files) if (entries.length() < 100) entries.put(new JSONObject().put("name", f.getName()).put("directory", f.isDirectory()).put("bytes", f.length()));
            return new JSONObject().put("entries", entries);
        }
        File file = resolve(path);
        if (operation.equals("write")) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 65536) throw new IOException("Text exceeds 64 KiB");
            if (!file.getParentFile().isDirectory()) throw new IOException("Parent directory does not exist");
            // Unique temporary file + no-follow checks; directory is owned by the controlled guest.
            File temporary = File.createTempFile("edit-", ".tmp", file.getParentFile());
            try { Files.write(temporary.toPath(), bytes); Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(temporary.toPath()); }
        } else if (!operation.equals("read")) throw new IOException("Unknown file operation");
        try (InputStream input = Files.newInputStream(resolve(path).toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return new JSONObject().put("path", path).put("content", AiClient.readLimited(input, 65536));
        }
    }
}
