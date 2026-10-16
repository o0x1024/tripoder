package dev.tripoder.selfhookprobe;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Observes the requested entry state; never substitutes one compiler for another. */
final class ArtCompilation {
    static String kind(JSONObject sample) throws Exception {
        JSONArray words = sample.getJSONArray("words");
        long entry = words.getLong(6) | (words.getLong(7) << 32);
        String maps;
        try (FileInputStream input = new FileInputStream("/proc/self/maps")) {
            maps = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String line : maps.split("\n")) {
            String[] fields = line.trim().split("\\s+", 6);
            String[] range = fields[0].split("-");
            long start = Long.parseUnsignedLong(range[0], 16), end = Long.parseUnsignedLong(range[1], 16);
            if (start <= entry && entry + 4 <= end) {
                if (!fields[1].startsWith("r-x")) throw new IllegalStateException("COMPILED_ENTRY_NOT_RX");
                String path = fields.length == 6 ? fields[5] : "";
                if (path.contains("dalvik-jit-code-cache")) return "jit";
                if (!fields[4].equals("0") && OwnedCompilationPaths.aot(path,maps)) return "aot";
                return "runtime";
            }
        }
        throw new IllegalStateException("COMPILED_ENTRY_NOT_MAPPED");
    }
}
