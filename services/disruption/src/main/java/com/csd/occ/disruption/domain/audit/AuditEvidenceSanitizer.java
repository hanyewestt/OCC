package com.csd.occ.disruption.domain.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class AuditEvidenceSanitizer {
    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i)(password|secret|token|authorization|api[_-]?key|access[_-]?(key|token)|credential|cookie|jwt|peer|service[_-]?(role|key|token)|headers?)");
    private static final Pattern SAFE_TOKEN_METRIC = Pattern.compile("(?i)(prompt|input|completion|output|total)?tokens?(count)?");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)((?:[A-Za-z0-9._-]*[-_])?(?:password|secret|authorization|api[_-]?key|access[-_]?(?:key|token)|credential|cookie|jwt|peer[-_]?(?:token|key)|service[-_]?(?:role|key|token)|token)(\\s*[:=]\\s*)(?:Bearer\\s+)?)[^\\s,;]+");
    private static final Pattern COOKIE_HEADER = Pattern.compile("(?i)\\b(set-cookie|cookie)\\s*:\\s*[^\\r\\n]+");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\b");
    private static final int MAX_TEXT = 10_000;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_COLLECTION_SIZE = 100;

    private AuditEvidenceSanitizer() { }

    public static Map<String, Object> sanitize(Map<String, ?> details) {
        if (details == null || details.isEmpty()) return Map.of();
        State state = new State();
        Map<String, Object> safe = copyMap(details, state, 0);
        if (state.truncated) safe.put("_truncated", true);
        return Collections.unmodifiableMap(safe);
    }

    private static final Object OMIT = new Object();

    private static Map<String, Object> copyMap(Map<?, ?> input, State state, int depth) {
        Map<String, Object> result = new LinkedHashMap<>();
        int visited = 0;
        for (var entry : input.entrySet()) {
            if (visited++ >= MAX_COLLECTION_SIZE) { state.truncated = true; break; }
            if (!(entry.getKey() instanceof String key) || isSensitiveKey(key)) continue;
            if (SAFE_TOKEN_METRIC.matcher(key).matches() && entry.getValue() != null && !(entry.getValue() instanceof Number)) continue;
            Object value = copyValue(entry.getValue(), state, depth + 1);
            if (value != OMIT) result.put(key, value);
        }
        return result;
    }

    private static boolean isSensitiveKey(String key) {
        return !SAFE_TOKEN_METRIC.matcher(key).matches() && SECRET_KEY.matcher(key).find();
    }

    private static Object copyValue(Object value, State state, int depth) {
        if (depth > MAX_DEPTH) { state.truncated = true; return "[TRUNCATED]"; }
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof String text) {
            String safe = text.replaceAll("[\\p{Cc}]", "");
            safe = BEARER.matcher(safe).replaceAll("Bearer [REDACTED]");
            safe = COOKIE_HEADER.matcher(safe).replaceAll("Cookie: [REDACTED]");
            safe = JWT.matcher(safe).replaceAll("[REDACTED_JWT]");
            safe = SECRET_ASSIGNMENT.matcher(safe).replaceAll("$1[REDACTED]");
            if (safe.length() > MAX_TEXT) { state.truncated = true; return safe.substring(0, MAX_TEXT) + "[TRUNCATED]"; }
            return safe;
        }
        if (value instanceof Map<?, ?> nested) return Collections.unmodifiableMap(copyMap(nested, state, depth));
        if (value instanceof List<?> list) {
            List<Object> safe = new ArrayList<>();
            for (int index = 0; index < list.size() && index < MAX_COLLECTION_SIZE; index++) {
                Object child = copyValue(list.get(index), state, depth + 1);
                if (child != OMIT) safe.add(child);
            }
            if (list.size() > MAX_COLLECTION_SIZE) state.truncated = true;
            return Collections.unmodifiableList(safe);
        }
        return OMIT;
    }

    private static final class State { private boolean truncated; }
}
