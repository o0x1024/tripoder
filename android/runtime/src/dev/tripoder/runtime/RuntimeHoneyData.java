package dev.tripoder.runtime;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Process-scoped virtual honeydata ContentProvider interceptor for {@code honeydata_control}
 * and {@code honeydata_query}.
 *
 * <p>Default is empty (no authorities intercepted). When configured for a category
 * ({@code contacts}, {@code sms}, or {@code call_log}), {@code ContentResolver.query}
 * against the matching system authority returns a {@link MatrixCursor} constructed
 * from the configured virtual rows.
 */
public final class RuntimeHoneyData {
    private static final Set<String> CATEGORIES = Set.of("contacts", "sms", "call_log");
    private static final Map<String, List<Map<String, Object>>> DATA = new LinkedHashMap<>();

    private RuntimeHoneyData() {}

    public static synchronized Map<String, Object> configure(String category, List<Map<String, Object>> rows) {
        if (category == null || !CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("HONEYDATA_CATEGORY_INVALID");
        }
        if (rows == null || rows.size() > 64) {
            throw new IllegalArgumentException("HONEYDATA_ROWS_LIMIT");
        }
        if (rows.isEmpty()) {
            DATA.remove(category);
            return status();
        }
        List<Map<String, Object>> copy = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            if (row == null || row.isEmpty() || row.size() > 16) {
                throw new IllegalArgumentException("HONEYDATA_ROW_INVALID");
            }
            Map<String, Object> clean = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String col = entry.getKey();
                if (col == null || !col.matches("^[A-Za-z0-9_]{1,64}$")) {
                    throw new IllegalArgumentException("HONEYDATA_COLUMN_INVALID:" + col);
                }
                Object val = entry.getValue();
                if (val != null && !(val instanceof String) && !(val instanceof Number) && !(val instanceof Boolean)) {
                    throw new IllegalArgumentException("HONEYDATA_VALUE_INVALID:" + col);
                }
                clean.put(col, val);
            }
            copy.add(Collections.unmodifiableMap(clean));
        }
        DATA.put(category, Collections.unmodifiableList(copy));
        return status();
    }

    public static synchronized Map<String, Object> clear() {
        DATA.clear();
        return status();
    }

    public static synchronized Cursor query(String authority, Uri uri, String[] projection) {
        if (DATA.isEmpty() || authority == null) return null;
        String category = categoryForAuthority(authority);
        if (category == null) return null;
        List<Map<String, Object>> rows = DATA.get(category);
        if (rows == null || rows.isEmpty()) return null;

        String[] columns;
        if (projection != null && projection.length > 0) {
            columns = projection.clone();
        } else {
            LinkedHashSet<String> allCols = new LinkedHashSet<>();
            for (Map<String, Object> row : rows) {
                allCols.addAll(row.keySet());
            }
            columns = allCols.toArray(new String[0]);
        }
        MatrixCursor cursor = new MatrixCursor(columns, rows.size());
        for (Map<String, Object> row : rows) {
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                Object raw = row.get(columns[i]);
                if (raw instanceof Boolean b) {
                    values[i] = b ? 1 : 0;
                } else if (raw instanceof Double d && d == Math.rint(d) && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) {
                    values[i] = d.longValue();
                } else {
                    values[i] = raw;
                }
            }
            cursor.addRow(values);
        }
        return cursor;
    }

    private static String categoryForAuthority(String authority) {
        switch (authority) {
            case "com.android.contacts":
            case "contacts":
                return "contacts";
            case "sms":
            case "mms-sms":
            case "telephony":
                return "sms";
            case "call_log":
            case "call_log_shadow":
                return "call_log";
            default:
                return null;
        }
    }

    public static synchronized Map<String, Object> status() {
        Map<String, Object> categories = new LinkedHashMap<>();
        int totalRows = 0;
        for (String cat : List.of("contacts", "sms", "call_log")) {
            List<Map<String, Object>> rows = DATA.getOrDefault(cat, List.of());
            totalRows += rows.size();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("rowCount", rows.size());
            entry.put("sampleRows", rows.subList(0, Math.min(rows.size(), 3)));
            categories.put(cat, entry);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", totalRows > 0);
        out.put("totalRows", totalRows);
        out.put("categories", categories);
        return Collections.unmodifiableMap(out);
    }
}
