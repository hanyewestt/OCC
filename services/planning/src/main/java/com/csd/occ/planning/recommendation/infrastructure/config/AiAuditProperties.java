package com.csd.occ.planning.recommendation.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.recommendation.audit")
public record AiAuditProperties(int maxResponseCharacters) {
    public AiAuditProperties {
        if (maxResponseCharacters < 1) throw new IllegalArgumentException("Audit response limit must be positive");
    }
    public String capture(String text) {
        if (text == null) return null;
        String sanitized = text.replaceAll("[\\p{Cc}&&[^\\n\\r\\t]]", "");
        return sanitized.length() <= maxResponseCharacters ? sanitized
                : sanitized.substring(0, maxResponseCharacters) + "\n[TRUNCATED]";
    }
}
