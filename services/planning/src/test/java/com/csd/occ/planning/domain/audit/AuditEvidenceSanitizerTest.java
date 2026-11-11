package com.csd.occ.planning.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditEvidenceSanitizerTest {
    @Test
    void sanitizesCredentialTextButKeepsNullAndNumericMetrics() {
        var input = new LinkedHashMap<String, Object>();
        input.put("input", "{\"api_key\":\"provider-secret\",\"access_key\":\"access-secret\"} Cookie: session=private");
        input.put("jwt", "eyJhbGciOiJub25l.eyJzdWIiOiJ1c2VyIn0.signature");
        input.put("peer-service-key", "private");
        input.put("promptTokens", 12);
        input.put("completionTokens", null);
        input.put("headers", Map.of("x-peer-token", "private"));
        var result = AuditEvidenceSanitizer.sanitize(input);
        assertThat(result).containsEntry("input", "{\"api_key\":\"[REDACTED]\",\"access_key\":\"[REDACTED]\"} Cookie: [REDACTED]")
                .containsEntry("promptTokens", 12).containsKey("completionTokens").containsEntry("completionTokens", null)
                .doesNotContainKey("jwt").doesNotContainKey("peer-service-key").doesNotContainKey("headers");
        assertThatThrownBy(() -> result.put("changed", true)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void boundsNestedEvidenceAndAddsTruncationMarker() {
        var details = new LinkedHashMap<String, Object>();
        details.put("items", java.util.stream.IntStream.range(0, 101).boxed().toList());
        var deep = new LinkedHashMap<String, Object>(); var cursor = deep;
        for (int i = 0; i < 12; i++) { var child = new LinkedHashMap<String, Object>(); cursor.put("level", child); cursor = child; }
        details.put("deep", deep);
        var safe = AuditEvidenceSanitizer.sanitize(details);
        assertThat(safe).containsEntry("_truncated", true);
        assertThat((List<?>) safe.get("items")).hasSize(100);
    }
}
