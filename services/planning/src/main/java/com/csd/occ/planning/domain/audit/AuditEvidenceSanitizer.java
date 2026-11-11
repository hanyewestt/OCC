package com.csd.occ.planning.domain.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class AuditEvidenceSanitizer {
    private static final Pattern SECRET_KEY = Pattern.compile("(?i)(password|secret|token|authorization|api[_-]?key|access[_-]?key|credential|cookie|jwt|peer|service[_-]?(role|key|token)|headers?)");
    private static final Pattern SAFE_METRIC = Pattern.compile("(?i)(prompt|input|completion|output|total)?tokens?(count)?");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\b");
    private static final Pattern SECRET_VALUE = Pattern.compile("(?i)([\\\"']?[A-Za-z0-9._-]*(?:password|secret|token|authorization|api[_-]?key|access[_-]?key|credential|cookie|jwt|peer|service[_-]?(?:role|key|token))[A-Za-z0-9._-]*[\\\"']?\\s*[:=]\\s*)([\\\"']?)(?:Bearer\\s+)?([^\\s,;\\\"'{}]+)([\\\"']?)");
    private static final int MAX_DEPTH = 8, MAX_SIZE = 100, MAX_TEXT = 10_000;
    private AuditEvidenceSanitizer() { }
    public static Map<String, Object> sanitize(Map<String, ?> details) {
        if (details == null || details.isEmpty()) return Map.of();
        State state = new State(); Map<String, Object> safe = map(details, state, 0);
        if (state.truncated) safe.put("_truncated", true);
        return Collections.unmodifiableMap(safe);
    }
    public static String sanitizeText(String text) {
        if (text == null) return null;
        return (String) sanitize(Map.of("value", text)).get("value");
    }
    private static Map<String, Object> map(Map<?, ?> source, State state, int depth) {
        Map<String, Object> safe = new LinkedHashMap<>(); int count = 0;
        for (var entry : source.entrySet()) {
            if (count >= MAX_SIZE) { state.truncated = true; break; }
            if (!(entry.getKey() instanceof String key) || (SECRET_KEY.matcher(key).find() && !SAFE_METRIC.matcher(key).matches())) continue;
            Object value = entry.getValue();
            if (SAFE_METRIC.matcher(key).matches() && value != null && !(value instanceof Number)) continue;
            Object clean = clean(value, state, depth + 1);
            if (clean != OMIT) { safe.put(key, clean); count++; }
        }
        return safe;
    }
    private static final Object OMIT = new Object();
    private static Object clean(Object value, State state, int depth) {
        if (depth > MAX_DEPTH) { state.truncated = true; return "[TRUNCATED]"; }
        if (value == null || value instanceof Boolean || value instanceof Number) return value;
        if (value instanceof String text) {
            String safe = BEARER.matcher(text).replaceAll("Bearer [REDACTED]");
            safe = JWT.matcher(safe).replaceAll("[REDACTED_JWT]");
            safe = SECRET_VALUE.matcher(safe).replaceAll("$1$2[REDACTED]$4");
            safe = safe.replaceAll("[\\p{Cc}&&[^\\n\\r\\t]]", "");
            if (safe.length() > MAX_TEXT) { state.truncated = true; return safe.substring(0, MAX_TEXT) + "[TRUNCATED]"; }
            return safe;
        }
        if (value instanceof Map<?, ?> nested) return Collections.unmodifiableMap(map(nested, state, depth));
        if (value instanceof List<?> list) {
            List<Object> safe = new ArrayList<>();
            for (int i = 0; i < list.size() && i < MAX_SIZE; i++) { Object item = clean(list.get(i), state, depth + 1); if (item != OMIT) safe.add(item); }
            if (list.size() > MAX_SIZE) state.truncated = true;
            return Collections.unmodifiableList(safe);
        }
        return OMIT;
    }
    private static final class State { boolean truncated; }
}
