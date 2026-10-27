package dev.tripoder.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-scoped deterministic HTTP mock/fault rules and virtual network connectivity state
 * for {@code env_mock_control} and {@code env_mock_query}.
 *
 * <p>Default is passthrough ({@code rules=[]}, {@code networkState="passthrough"}).
 */
public final class RuntimeEnvMock {
    private static final Set<String> NETWORK_STATES = Set.of("passthrough", "offline", "wifi", "cellular");
    private static final Set<String> METHODS = Set.of("ANY", "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    public static final class HttpRule {
        public final String urlPrefix;
        public final String method;
        public final int statusCode;
        public final Map<String, String> headers;
        public final String body;
        public final int delayMs;
        public final AtomicInteger hits = new AtomicInteger(0);

        HttpRule(String urlPrefix, String method, int statusCode, Map<String, String> headers, String body, int delayMs) {
            this.urlPrefix = urlPrefix;
            this.method = method;
            this.statusCode = statusCode;
            this.headers = Collections.unmodifiableMap(headers);
            this.body = body;
            this.delayMs = delayMs;
        }
    }

    private static volatile List<HttpRule> httpRules = List.of();
    private static volatile String networkState = "passthrough";

    private RuntimeEnvMock() {}

    @SuppressWarnings("unchecked")
    public static synchronized Map<String, Object> configureHttp(List<Map<String, Object>> rawRules) {
        if (rawRules == null || rawRules.size() > 32) {
            throw new IllegalArgumentException("ENV_MOCK_HTTP_RULES_LIMIT");
        }
        List<HttpRule> next = new ArrayList<>(rawRules.size());
        for (Map<String, Object> item : rawRules) {
            if (item == null) throw new IllegalArgumentException("ENV_MOCK_RULE_NULL");
            Object rawPrefix = item.get("urlPrefix");
            if (!(rawPrefix instanceof String prefix) || prefix.length() < 8 || prefix.length() > 512) {
                throw new IllegalArgumentException("ENV_MOCK_URL_PREFIX");
            }
            Object rawMethod = item.getOrDefault("method", "ANY");
            String method = rawMethod instanceof String m && !m.isEmpty() ? m.toUpperCase(Locale.ROOT) : "ANY";
            if (!METHODS.contains(method)) {
                throw new IllegalArgumentException("ENV_MOCK_METHOD:" + method);
            }
            Object rawStatus = item.get("statusCode");
            if (!(rawStatus instanceof Number statusNum) || statusNum.intValue() < 100 || statusNum.intValue() > 599) {
                throw new IllegalArgumentException("ENV_MOCK_STATUS_CODE");
            }
            int statusCode = statusNum.intValue();
            Map<String, String> headers = new LinkedHashMap<>();
            Object rawHeaders = item.get("headers");
            if (rawHeaders instanceof Map<?, ?> hMap) {
                if (hMap.size() > 16) throw new IllegalArgumentException("ENV_MOCK_HEADERS_LIMIT");
                for (Map.Entry<?, ?> entry : hMap.entrySet()) {
                    if (!(entry.getKey() instanceof String k) || !(entry.getValue() instanceof String v)) {
                        throw new IllegalArgumentException("ENV_MOCK_HEADER_ENTRY");
                    }
                    headers.put(k, v);
                }
            }
            String body = item.get("body") instanceof String b ? b : "";
            int delayMs = 0;
            if (item.get("delayMs") instanceof Number delayNum) {
                delayMs = delayNum.intValue();
                if (delayMs < 0 || delayMs > 5000) throw new IllegalArgumentException("ENV_MOCK_DELAY_RANGE");
            }
            next.add(new HttpRule(prefix, method, statusCode, headers, body, delayMs));
        }
        httpRules = Collections.unmodifiableList(next);
        return status();
    }

    public static synchronized Map<String, Object> configureNetwork(String state) {
        if (state == null || !NETWORK_STATES.contains(state)) {
            throw new IllegalArgumentException("ENV_MOCK_NETWORK_STATE:" + state);
        }
        networkState = state;
        RuntimeEngine engine = RuntimeEngine.current;
        if (engine != null && engine.connectivity != null) {
            engine.connectivity.dispatchMockNetworkState(state);
        }
        return status();
    }

    public static synchronized Map<String, Object> clear() {
        httpRules = List.of();
        networkState = "passthrough";
        RuntimeEngine engine = RuntimeEngine.current;
        if (engine != null && engine.connectivity != null) {
            engine.connectivity.dispatchMockNetworkState("passthrough");
        }
        return status();
    }

    public static String networkState() {
        return networkState;
    }

    public static HttpRule matchHttp(String method, String url) {
        List<HttpRule> snapshot = httpRules;
        if (snapshot.isEmpty() || url == null) return null;
        for (HttpRule rule : snapshot) {
            if (!"ANY".equals(rule.method) && !rule.method.equalsIgnoreCase(method)) continue;
            if (url.startsWith(rule.urlPrefix)) {
                rule.hits.incrementAndGet();
                return rule;
            }
        }
        return null;
    }

    public static synchronized Map<String, Object> status() {
        List<Map<String, Object>> rulesOut = new ArrayList<>(httpRules.size());
        int totalHits = 0;
        for (HttpRule rule : httpRules) {
            int hits = rule.hits.get();
            totalHits += hits;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("urlPrefix", rule.urlPrefix);
            row.put("method", rule.method);
            row.put("statusCode", rule.statusCode);
            row.put("headers", rule.headers);
            row.put("bodyLength", rule.body.length());
            row.put("delayMs", rule.delayMs);
            row.put("hits", hits);
            rulesOut.add(Collections.unmodifiableMap(row));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", !httpRules.isEmpty() || !"passthrough".equals(networkState));
        out.put("networkState", networkState);
        out.put("httpRuleCount", rulesOut.size());
        out.put("totalHttpHits", totalHits);
        out.put("httpRules", rulesOut);
        return Collections.unmodifiableMap(out);
    }
}
