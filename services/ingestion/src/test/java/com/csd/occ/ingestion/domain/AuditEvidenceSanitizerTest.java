package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditEvidenceSanitizerTest {
    @Test
    void redactsSecretsAndReturnsImmutableJsonSafeValuesIncludingNullMetrics() {
        var source = new java.util.LinkedHashMap<String, Object>();
        source.put("input", "Authorization: Bearer abc.def.ghi");
        source.put("rawText", "x-peer-token=peer-secret; peer-service-key: service-secret; x-service-role=role-secret; x-access-key=access-secret");
        source.put("tokenCount", null);
        source.put("secret", "must not remain");
        source.put("cookie", "session=secret");
        source.put("jwt", "eyJhbGciOiJub25l.eyJzdWIiOiJ1c2VyIn0.signature");
        source.put("peerCapability", "peer-secret");
        source.put("service_role", "service-secret");
        source.put("access_key", "access-secret");
        source.put("headers", Map.of("x-provider-key", "provider-secret"));
        source.put("nested", List.of("api_key=bad", 3));
        var result = AuditEvidenceSanitizer.sanitize(source);
        assertThat(result).containsKey("tokenCount").containsEntry("tokenCount", null)
                .containsEntry("input", "Authorization: [REDACTED]")
                .containsEntry("rawText", "x-peer-token=[REDACTED]; peer-service-key: [REDACTED]; x-service-role=[REDACTED]; x-access-key=[REDACTED]")
                .doesNotContainKey("secret")
                .doesNotContainKey("cookie")
                .doesNotContainKey("jwt")
                .doesNotContainKey("peerCapability")
                .doesNotContainKey("service_role")
                .doesNotContainKey("access_key")
                .doesNotContainKey("headers")
                .containsEntry("nested", List.of("api_key=[REDACTED]", 3));
        assertThatThrownBy(() -> result.put("changed", true)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void boundsNestedCollectionsAndMarksTruncation() {
        var deep = new java.util.LinkedHashMap<String, Object>();
        var cursor = deep;
        for (int i = 0; i < 12; i++) {
            var nested = new java.util.LinkedHashMap<String, Object>();
            cursor.put("level", nested);
            cursor = nested;
        }
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("deep", deep);
        details.put("items", java.util.stream.IntStream.range(0, 101).boxed().toList());
        var result = AuditEvidenceSanitizer.sanitize(details);
        assertThat(result).containsEntry("_truncated", true);
        assertThat((List<?>) result.get("items")).hasSize(100);
    }
}
