package com.csd.occ.ingestion.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Converts audit details to a bounded immutable JSON-safe tree and redacts credential-like text. */
public final class AuditEvidenceSanitizer {
    private static final Pattern SECRET_KEY = Pattern.compile("(?i)(password|secret|token|authorization|api[_-]?key|access[_-]?key|credential|cookie|jwt|peer|service[_-]?(role|key|token)|headers?)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern ASSIGNED_SECRET = Pattern.compile("(?i)([\\\"']?[A-Za-z0-9._-]*(?:password|secret|token|authorization|api[_-]?key|access[_-]?key|credential|cookie|jwt|peer|service[_-]?(?:role|key|token))[A-Za-z0-9._-]*[\\\"']?\\s*[:=]\\s*)([\\\"']?)(?:Bearer\\s+)?([^\\s,;\\\"'{}]+)([\\\"']?)" );
    private static final Pattern COOKIE_HEADER = Pattern.compile("(?i)\\b(set-cookie|cookie)\\s*:\\s*[^\\r\\n]+");
    private static final Pattern JWT_VALUE = Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\b");
    private static final int MAX_TEXT = 10_000;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_COLLECTION_SIZE = 100;
    private static final Pattern SAFE_TOKEN_METRIC = Pattern.compile("(?i)(prompt|input|completion|output|total)?tokens?(count)?");

    private AuditEvidenceSanitizer() { }

    public static Map<String, Object> sanitize(Map<String, ?> details) {
        if (details == null || details.isEmpty()) return Map.of();
        State state = new State();
        Map<String, Object> safe = map(details, state, 0);
        if (state.truncated) safe.put("_truncated", true);
        return Collections.unmodifiableMap(safe);
    }

    private static final Object OMIT = new Object();
    private static Map<String, Object> map(Map<?, ?> source, State state, int depth) {
        Map<String, Object> safe = new LinkedHashMap<>();
        int copied = 0;
        for (var entry : source.entrySet()) {
            if (copied >= MAX_COLLECTION_SIZE) { state.truncated = true; break; }
            if (entry.getKey() instanceof String key && !sensitiveKey(key)) {
                if (SAFE_TOKEN_METRIC.matcher(key).matches()
                        && entry.getValue() != null && !(entry.getValue() instanceof Number)) continue;
                Object sanitized = value(entry.getValue(), state, depth + 1);
                if (sanitized != OMIT) { safe.put(key, sanitized); copied++; }
            }
        }
        return safe;
    }

    private static boolean sensitiveKey(String key) {
        if (SAFE_TOKEN_METRIC.matcher(key).matches()) return false;
        return SECRET_KEY.matcher(key).find();
    }

    private static Object value(Object value, State state, int depth) {
        if (depth > MAX_DEPTH) { state.truncated = true; return "[TRUNCATED]"; }
        if (value == null || value instanceof Boolean || value instanceof Number) return value;
        if (value instanceof String text) {
            String safe = BEARER.matcher(text).replaceAll("Bearer [REDACTED]");
            safe = COOKIE_HEADER.matcher(safe).replaceAll("Cookie: [REDACTED]");
            safe = JWT_VALUE.matcher(safe).replaceAll("[REDACTED_JWT]");
            safe = ASSIGNED_SECRET.matcher(safe).replaceAll("$1$2[REDACTED]$4");
            safe = safe.replaceAll("[\\p{Cc}&&[^\\n\\r\\t]]", "");
            if (safe.length() > MAX_TEXT) { state.truncated = true; return safe.substring(0, MAX_TEXT) + "[TRUNCATED]"; }
            return safe;
        }
        if (value instanceof Map<?, ?> nested) {
            return Collections.unmodifiableMap(map(nested, state, depth));
        }
        if (value instanceof List<?> list) {
            List<Object> safe = new ArrayList<>();
            int count = 0;
            for (Object child : list) {
                if (count++ >= MAX_COLLECTION_SIZE) { state.truncated = true; break; }
                Object sanitized = value(child, state, depth + 1);
                if (sanitized != OMIT) safe.add(sanitized);
            }
            return Collections.unmodifiableList(safe);
        }
        return OMIT;
    }

    private static final class State { private boolean truncated; }
}
