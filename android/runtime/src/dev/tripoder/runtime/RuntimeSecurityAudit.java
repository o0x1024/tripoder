package dev.tripoder.runtime;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.tripoder.runtime.script.JavaHookValues;
import dev.tripoder.runtime.script.ScriptValues;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

/** In-process client-side and API security assessment primitives for the active guest container. */
final class RuntimeSecurityAudit {
    private static final int MAX_TRAFFIC_RING = 256;
    private static final AtomicLong TRAFFIC_SEQ = new AtomicLong();
    private static final ArrayDeque<Map<String,Object>> TRAFFIC_LOG = new ArrayDeque<>();
    private static final Set<String> SENSITIVE_KEYS = Set.of(
        "token","access_token","refresh_token","secret","password","passwd","pwd","apikey","api_key",
        "private_key","session","sessionid","cookie","jwt","authorization","auth","phone","mobile","idcard","cert"
    );

    static Map<String,Object> inspectStorage(File directory,String category, int limit) throws Exception {
        if (!Set.of("all", "shared_prefs", "databases", "files").contains(category))
            throw new IllegalArgumentException("AUDIT_STORAGE_CATEGORY:" + category);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("AUDIT_STORAGE_LIMIT");
        if(java.nio.file.Files.isSymbolicLink(directory.toPath()))throw new SecurityException("AUDIT_STORAGE_SYMLINK");
        File root = directory.getCanonicalFile();
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("category", category);
        out.put("completeness","BOUNDED_LIVE_VIEW");out.put("limitPerCategory",limit);out.put("fileDepthLimit",4);
        if ("all".equals(category) || "shared_prefs".equals(category))
            out.put("sharedPreferences", inspectSharedPrefs(root, new File(root, "shared_prefs"), limit));
        if ("all".equals(category) || "databases".equals(category))
            out.put("databases", inspectDatabases(root, new File(root, "databases"), limit));
        if ("all".equals(category) || "files".equals(category))
            out.put("files", inspectFiles(root, limit));
        return Collections.unmodifiableMap(out);
    }

    private static List<Map<String,Object>> inspectSharedPrefs(File sandboxRoot, File prefsDir, int limit) throws Exception {
        if(java.nio.file.Files.isSymbolicLink(prefsDir.toPath()))throw new SecurityException("AUDIT_STORAGE_SYMLINK");
        if (!prefsDir.exists()) return List.of();
        if(!prefsDir.isDirectory())throw new java.io.IOException("AUDIT_STORAGE_NOT_DIRECTORY");
        File canonicalDir = prefsDir.getCanonicalFile();
        if (!canonicalDir.getPath().startsWith(sandboxRoot.getPath() + File.separator)) throw new SecurityException("AUDIT_STORAGE_OUTSIDE_INSTALL");
        File[] files = canonicalDir.listFiles((d, name) -> name.endsWith(".xml"));
        if (files == null) throw new java.io.IOException("AUDIT_STORAGE_UNREADABLE");
        Arrays.sort(files, Comparator.comparing(File::getName));
        List<Map<String,Object>> result = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, files.length); i++) {
            if(java.nio.file.Files.isSymbolicLink(files[i].toPath()))throw new SecurityException("AUDIT_STORAGE_SYMLINK");
            File file = files[i].getCanonicalFile();
            if (!file.getPath().startsWith(canonicalDir.getPath() + File.separator) || !file.isFile() || file.length() > 512 * 1024)
                continue;
            List<Map<String,Object>> entries = new ArrayList<>();
            try (InputStream in = new FileInputStream(file)) {
                XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
                parser.setInput(in, "UTF-8");
                int event = parser.getEventType();
                while (event != XmlPullParser.END_DOCUMENT && entries.size() < 64) {
                    if (event == XmlPullParser.START_TAG) {
                        String tag = parser.getName();
                        String key = parser.getAttributeValue(null, "name");
                        if (key != null && ! "map".equals(tag)) {
                            String val = parser.getAttributeValue(null, "value");
                            if (val == null && "string".equals(tag)) val = parser.nextText();
                            if (val != null && val.length() > 200) val = val.substring(0, 200) + "...";
                            boolean sensitive = isSensitiveKey(key);
                            Map<String,Object> entry = new LinkedHashMap<>();
                            entry.put("key", key.length() > 128 ? key.substring(0, 128) : key);
                            entry.put("type", tag);
                            entry.put("valuePreview", val == null ? "" : val);
                            entry.put("sensitiveKeyMatched", sensitive);
                            entries.add(Collections.unmodifiableMap(entry));
                        }
                    }
                    event = parser.next();
                }
            } catch (Exception parseError) {
                Map<String,Object> err = new LinkedHashMap<>();
                err.put("file", file.getName());
                err.put("error", parseError.getClass().getSimpleName());
                result.add(Collections.unmodifiableMap(err));
                continue;
            }
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("file", file.getName());
            item.put("sizeBytes", (double)file.length());
            item.put("entries", List.copyOf(entries));
            result.add(Collections.unmodifiableMap(item));
        }
        return List.copyOf(result);
    }

    private static boolean isSensitiveKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String token : SENSITIVE_KEYS) if (lower.contains(token)) return true;
        return false;
    }

    private static List<Map<String,Object>> inspectDatabases(File sandboxRoot, File dbDir, int limit) throws Exception {
        if(java.nio.file.Files.isSymbolicLink(dbDir.toPath()))throw new SecurityException("AUDIT_STORAGE_SYMLINK");
        if (!dbDir.exists()) return List.of();
        if(!dbDir.isDirectory())throw new java.io.IOException("AUDIT_STORAGE_NOT_DIRECTORY");
        File canonicalDir = dbDir.getCanonicalFile();
        if (!canonicalDir.getPath().startsWith(sandboxRoot.getPath() + File.separator)) throw new SecurityException("AUDIT_STORAGE_OUTSIDE_INSTALL");
        File[] files = canonicalDir.listFiles();
        if (files == null) throw new java.io.IOException("AUDIT_STORAGE_UNREADABLE");
        Arrays.sort(files, Comparator.comparing(File::getName));
        List<Map<String,Object>> result = new ArrayList<>();
        for(File candidate:files)if(java.nio.file.Files.isSymbolicLink(candidate.toPath()))throw new SecurityException("AUDIT_STORAGE_SYMLINK");
        for (File candidate : files) {
            if (result.size() >= limit) break;
            String name = candidate.getName();
            if (name.endsWith("-journal") || name.endsWith("-wal") || name.endsWith("-shm")) continue;
            File file = candidate.getCanonicalFile();
            if (!file.getPath().startsWith(canonicalDir.getPath() + File.separator) || !file.isFile() || file.length() < 100)
                continue;
            byte[] header = new byte[16];
            try (FileInputStream in = new FileInputStream(file)) {
                if (in.read(header) != 16 || !"SQLite format 3\0".equals(new String(header, StandardCharsets.US_ASCII)))
                    continue;
            }
            List<Map<String,Object>> tables = new ArrayList<>();
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
                List<String> tableNames = new ArrayList<>();
                try (Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' ORDER BY name LIMIT 32", null)) {
                    while (c.moveToNext()) {
                        String t = c.getString(0);
                        if (t != null && t.matches("[A-Za-z0-9_]{1,64}")) tableNames.add(t);
                    }
                }
                for (String table : tableNames) {
                    List<String> cols = new ArrayList<>();
                    try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
                        while (c.moveToNext() && cols.size() < 32) cols.add(c.getString(1) + ":" + c.getString(2));
                    }
                    long rows = 0;
                    try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
                        if (c.moveToFirst()) rows = c.getLong(0);
                    }
                    Map<String,Object> tbl = new LinkedHashMap<>();
                    tbl.put("table", table);
                    tbl.put("columns", List.copyOf(cols));
                    tbl.put("rowCount", (double)rows);
                    tables.add(Collections.unmodifiableMap(tbl));
                }
            } catch (Exception dbError) {
                Map<String,Object> err = new LinkedHashMap<>();
                err.put("file", file.getName());
                err.put("error", dbError.getClass().getSimpleName());
                result.add(Collections.unmodifiableMap(err));
                continue;
            }
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("file", file.getName());
            item.put("sizeBytes", (double)file.length());
            item.put("tables", List.copyOf(tables));
            result.add(Collections.unmodifiableMap(item));
        }
        return List.copyOf(result);
    }

    private static List<Map<String,Object>> inspectFiles(File sandboxRoot, int limit) throws Exception {
        List<Map<String,Object>> out = new ArrayList<>();
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        for (String sub : List.of("files", "cache")) {
            File dir = new File(sandboxRoot, sub);
            if(java.nio.file.Files.isSymbolicLink(dir.toPath()))throw new SecurityException("AUDIT_STORAGE_SYMLINK");
            if (dir.isDirectory()) queue.add(new Object[]{dir.getCanonicalFile(), sub, 0});
        }
        while (!queue.isEmpty() && out.size() < limit) {
            Object[] cur = queue.removeFirst();
            File dir = (File)cur[0];
            String relPrefix = (String)cur[1];
            int depth = (Integer)cur[2];
            if (!dir.getPath().startsWith(sandboxRoot.getPath() + File.separator) || depth > 4) continue;
            File[] children = dir.listFiles();
            if (children == null) throw new java.io.IOException("AUDIT_STORAGE_UNREADABLE");
            Arrays.sort(children, Comparator.comparing(File::getName));
            for (File child : children) {
                if (out.size() >= limit) break;
                if(java.nio.file.Files.isSymbolicLink(child.toPath()))throw new SecurityException("AUDIT_STORAGE_SYMLINK");
                File canonical = child.getCanonicalFile();
                if (!canonical.getPath().startsWith(sandboxRoot.getPath() + File.separator)) throw new SecurityException("AUDIT_STORAGE_OUTSIDE_INSTALL");
                String rel = relPrefix + "/" + child.getName();
                if (canonical.isDirectory()) {
                    queue.addLast(new Object[]{canonical, rel, depth + 1});
                } else if (canonical.isFile()) {
                    Map<String,Object> item = new LinkedHashMap<>();
                    item.put("path", rel);
                    item.put("sizeBytes", (double)canonical.length());
                    out.add(Collections.unmodifiableMap(item));
                }
            }
        }
        return List.copyOf(out);
    }

    static void recordNetworkExchange(String method, String url, Map<String,String> reqHeaders, String reqBody,
                                      int statusCode, Map<String,String> respHeaders, String respBody, long durationMs) {
        long seq = TRAFFIC_SEQ.incrementAndGet();
        Map<String,Object> entry = new LinkedHashMap<>();
        entry.put("seq", (double)seq);
        entry.put("timestampMs", (double)System.currentTimeMillis());
        entry.put("method", truncate(method, 16));
        entry.put("url", truncate(url, 1024));
        entry.put("requestHeaders", boundedHeaders(reqHeaders));
        entry.put("requestBodyPreview", truncate(reqBody, 1024));
        entry.put("statusCode", (double)statusCode);
        entry.put("responseHeaders", boundedHeaders(respHeaders));
        entry.put("responseBodyPreview", truncate(respBody, 2048));
        entry.put("durationMs", (double)Math.max(0L, durationMs));
        synchronized (TRAFFIC_LOG) {
            if (TRAFFIC_LOG.size() >= MAX_TRAFFIC_RING) TRAFFIC_LOG.pollFirst();
            TRAFFIC_LOG.addLast(Collections.unmodifiableMap(entry));
        }
    }

    static Map<String,Object> listNetworkTraffic(long sinceSeq, int limit) {
        if (sinceSeq < 0) throw new IllegalArgumentException("AUDIT_TRAFFIC_SEQ");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("AUDIT_TRAFFIC_LIMIT");
        List<Map<String,Object>> items = new ArrayList<>();
        long nextCursor = sinceSeq;
        synchronized (TRAFFIC_LOG) {
            for (Map<String,Object> entry : TRAFFIC_LOG) {
                long seq = ((Number)entry.get("seq")).longValue();
                if (seq > sinceSeq) {
                    items.add(entry);
                    nextCursor = seq;
                    if (items.size() >= limit) break;
                }
            }
        }
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("exchanges", List.copyOf(items));
        out.put("nextCursor", (double)nextCursor);
        return Collections.unmodifiableMap(out);
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> sendHttpRequest(Map<String,Object> spec) throws Exception {
        var checked=dev.tripoder.runtime.script.HttpRequestPreflight.validate(spec);
        String method=checked.method(),urlText=checked.uri().toString(),body=checked.body();URI uri=checked.uri();int timeoutMs=checked.timeoutMs();Map<String,String> reqHeaders=checked.headers();
        long startNs = System.nanoTime();
        RuntimeEnvMock.HttpRule mockRule = RuntimeEnvMock.matchHttp(method, urlText);
        if (mockRule != null) {
            if (mockRule.delayMs > 0) {
                try { Thread.sleep(mockRule.delayMs); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
            String preview = mockRule.body.length() > 4096 ? mockRule.body.substring(0, 4096) : mockRule.body;
            boolean truncated = mockRule.body.length() > 4096;
            recordNetworkExchange(method, urlText, reqHeaders, body == null ? "" : body, mockRule.statusCode, mockRule.headers, preview, durationMs);
            Map<String,Object> out = new LinkedHashMap<>();
            out.put("statusCode", (double)mockRule.statusCode);
            out.put("headers", mockRule.headers);
            out.put("body", preview);
            out.put("bodyTruncated", truncated);
            out.put("durationMs", (double)durationMs);
            out.put("mocked", true);
            return Collections.unmodifiableMap(out);
        }
        // Calling a handler/proxy may already execute code or dispatch. Only the pure
        // HttpRequestPreflight stage certifies rejection; later failures stay UNKNOWN.
        HttpURLConnection conn = (HttpURLConnection)uri.toURL().openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestMethod(method);
        for (Map.Entry<String,String> h : reqHeaders.entrySet()) conn.setRequestProperty(h.getKey(), h.getValue());
        try {
            if (body != null && !Set.of("GET","HEAD").contains(method)) {
                conn.setDoOutput(true);
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = conn.getOutputStream()) { out.write(payload); }
            }
            int status = conn.getResponseCode();
            Map<String,String> respHeaders = new LinkedHashMap<>();
            for (Map.Entry<String,List<String>> e : conn.getHeaderFields().entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty() && respHeaders.size() < 32)
                    respHeaders.put(truncate(e.getKey(), 128), truncate(String.join(", ", e.getValue()), 1024));
            }
            InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] respBytes = readBounded(stream, 8192);
            boolean truncated = respBytes.length > 4096;
            String respPreview = new String(respBytes, 0, Math.min(4096, respBytes.length), StandardCharsets.UTF_8);
            long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
            recordNetworkExchange(method, urlText, reqHeaders, body == null ? "" : body, status, respHeaders, respPreview, durationMs);
            Map<String,Object> out = new LinkedHashMap<>();
            out.put("statusCode", (double)status);
            out.put("headers", Collections.unmodifiableMap(respHeaders));
            out.put("body", respPreview);
            out.put("bodyTruncated", truncated);
            out.put("durationMs", (double)durationMs);
            return Collections.unmodifiableMap(out);
        } finally {
            conn.disconnect();
        }
    }

    static Map<String,Object> invokeGuestMethod(Map<String,Object> target) throws Exception {
        RuntimeEngine engine = RuntimeEngine.current;
        if (engine == null || engine.loader == null) throw new IllegalStateException("INVOKE_ENGINE_MISSING");
        if (target == null) throw new IllegalArgumentException("INVOKE_TARGET_REQUIRED");
        Object rawClass = target.get("className"), rawMethod = target.get("methodName");
        Object rawTypes = target.get("argumentTypes"), rawArgs = target.get("arguments");
        if (!(rawClass instanceof String className) || className.isEmpty() || className.length() > 512)
            throw new IllegalArgumentException("INVOKE_CLASS_NAME");
        if (!(rawMethod instanceof String methodName) || methodName.isEmpty() || methodName.length() > 128 || (methodName.startsWith("<") && !"<init>".equals(methodName)))
            throw new IllegalArgumentException("INVOKE_METHOD_NAME");
        if (!(rawTypes instanceof List<?> typeList) || !(rawArgs instanceof List<?> argList) || typeList.size() != argList.size() || typeList.size() > 16)
            throw new IllegalArgumentException("INVOKE_ARGUMENTS_MISMATCH");

        Class<?> owner = Class.forName(className, false, engine.loader);
        if (!engine.ownsLoader(owner.getClassLoader())) throw new SecurityException("INVOKE_CLASS_OUTSIDE_GUEST");

        if ("<init>".equals(methodName)) {
            Constructor<?> matchedCtor = null;
            for (Constructor<?> candidate : owner.getDeclaredConstructors()) {
                if (candidate.getParameterCount() != typeList.size()) continue;
                Class<?>[] params = candidate.getParameterTypes();
                boolean ok = true;
                for (int i = 0; i < params.length; i++) {
                    if (!(typeList.get(i) instanceof String expected)) { ok = false; break; }
                    String actual = params[i].getName();
                    ok &= actual.equals(expected) || (params[i] == byte[].class && "byte[]".equals(expected));
                }
                if (ok) {
                    if (matchedCtor != null) throw new IllegalArgumentException("INVOKE_METHOD_AMBIGUOUS");
                    matchedCtor = candidate;
                }
            }
            if (matchedCtor == null) throw new IllegalArgumentException("INVOKE_METHOD_NOT_FOUND");
            Class<?>[] paramTypes = matchedCtor.getParameterTypes();
            for (Class<?> pt : paramTypes) JavaHookValues.requireInvocationType(pt, false);
            List<Object> normalizedArgs = new ArrayList<>(argList.size());
            for (int i = 0; i < argList.size(); i++) {
                Object raw = argList.get(i);
                if (paramTypes[i] == long.class && raw instanceof String s && s.matches("-?[0-9]{1,20}"))
                    normalizedArgs.add(new java.math.BigInteger(s));
                else normalizedArgs.add(raw);
            }
            Object[] physicalArgs = JavaHookValues.encodeArguments(paramTypes, normalizedArgs);
            matchedCtor.setAccessible(true);
            Object instance = matchedCtor.newInstance(physicalArgs);
            String createdId = RuntimeLiveInstances.register(instance);
            Map<String,Object> out = new LinkedHashMap<>();
            out.put("className", owner.getName());
            out.put("methodName", "<init>");
            out.put("returnType", owner.getName());
            out.put("result", createdId);
            out.put("instanceId", createdId);
            return Collections.unmodifiableMap(out);
        }

        Method matched = null;
        for (Method candidate : owner.getDeclaredMethods()) {
            if (!candidate.getName().equals(methodName) || candidate.getParameterCount() != typeList.size()) continue;
            Class<?>[] params = candidate.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < params.length; i++) {
                if (!(typeList.get(i) instanceof String expected)) { ok = false; break; }
                String actual = params[i].getName();
                ok &= actual.equals(expected) || (params[i] == byte[].class && "byte[]".equals(expected));
            }
            if (ok) {
                if (matched != null) throw new IllegalArgumentException("INVOKE_METHOD_AMBIGUOUS");
                matched = candidate;
            }
        }
        if (matched == null) throw new IllegalArgumentException("INVOKE_METHOD_NOT_FOUND");
        Class<?>[] paramTypes = matched.getParameterTypes();
        Class<?> returnType = matched.getReturnType();
        for (Class<?> pt : paramTypes) JavaHookValues.requireInvocationType(pt, false);
        JavaHookValues.requireInvocationType(returnType, true);

        List<Object> normalizedArgs = new ArrayList<>(argList.size());
        for (int i = 0; i < argList.size(); i++) {
            Object raw = argList.get(i);
            if (paramTypes[i] == long.class && raw instanceof String s && s.matches("-?[0-9]{1,20}"))
                normalizedArgs.add(new java.math.BigInteger(s));
            else normalizedArgs.add(raw);
        }
        Object[] physicalArgs = JavaHookValues.encodeArguments(paramTypes, normalizedArgs);
        matched.setAccessible(true);
        Object rawInstanceId = target.get("instanceId");
        String usedInstanceId = null;
        Object receiver = null;
        if (!Modifier.isStatic(matched.getModifiers())) {
            if (rawInstanceId instanceof String instId && !instId.isEmpty()) {
                receiver = RuntimeLiveInstances.requireInstance(instId, owner);
                usedInstanceId = instId;
            } else {
                Constructor<?> ctor = owner.getDeclaredConstructor();
                ctor.setAccessible(true);
                receiver = ctor.newInstance();
            }
        }
        Object rawResult = matched.invoke(receiver, physicalArgs);
        Object decoded = JavaHookValues.decode(returnType, rawResult);
        Object wireResult = decoded == ScriptValues.UNDEFINED ? null : decoded instanceof java.math.BigInteger bi ? bi.toString() : decoded;
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("className", owner.getName());
        out.put("methodName", matched.getName());
        out.put("returnType", returnType.getName());
        out.put("result", wireResult);
        if (usedInstanceId != null) out.put("instanceId", usedInstanceId);
        return Collections.unmodifiableMap(out);
    }

    private static Map<String,String> boundedHeaders(Map<String,String> headers) {
        if (headers == null || headers.isEmpty()) return Map.of();
        Map<String,String> out = new LinkedHashMap<>();
        for (Map.Entry<String,String> e : headers.entrySet()) {
            if (out.size() >= 32) break;
            if (e.getKey() != null && e.getValue() != null)
                out.put(truncate(e.getKey(), 128), truncate(e.getValue(), 512));
        }
        return Collections.unmodifiableMap(out);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static byte[] readBounded(InputStream in, int maxBytes) throws Exception {
        if (in == null) return new byte[0];
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int total = 0, n;
            while (total < maxBytes && (n = s.read(buf, 0, Math.min(buf.length, maxBytes - total))) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            return out.toByteArray();
        }
    }

    private RuntimeSecurityAudit() {}
}
