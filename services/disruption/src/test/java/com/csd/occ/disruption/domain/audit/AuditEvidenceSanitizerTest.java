package com.csd.occ.disruption.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditEvidenceSanitizerTest {
    @Test
    void removesSensitiveKeysAndRedactsCredentialTextWhilePreservingTokenMetrics() {
        var details = new LinkedHashMap<String, Object>();
        details.put("authorization", "Bearer secret");
        details.put("cookie", "session=secret");
        details.put("peer_token", "peer-value");
        details.put("access_key", "access-value");
        details.put("headers", Map.of("X-Service-Role", "service-value"));
        details.put("input", "Authorization: Bearer abc.def.ghi; x-peer-token=peer-secret; service_role=admin-secret; api_key=key-secret; access-token=access-secret; password=pass-secret; pass\nword=split-secret");
        details.put("jwtText", "eyJhbGciOiJub25l.eyJzdWIiOiJ1c2VyIn0.signature");
        details.put("promptTokenCount", 14);
        details.put("completionTokens", null);

        var result = AuditEvidenceSanitizer.sanitize(details);

        assertThat(result).doesNotContainKeys("authorization", "cookie", "peer_token", "access_key", "headers", "jwtText")
                .containsEntry("promptTokenCount", 14).containsKey("completionTokens");
        assertThat(result.get("input").toString()).doesNotContain("abc.def.ghi", "peer-secret", "admin-secret", "key-secret", "access-secret", "pass-secret", "split-secret")
                .contains("[REDACTED]");
        assertThatThrownBy(() -> result.put("other", true)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void boundsDepthCollectionsAndTextAndMarksTruncation() {
        var deep = new LinkedHashMap<String, Object>();
        var cursor = deep;
        for (int index = 0; index < 12; index++) {
            var next = new LinkedHashMap<String, Object>();
            cursor.put("nested", next);
            cursor = next;
        }
        var details = new LinkedHashMap<String, Object>();
        details.put("deep", deep);
        details.put("items", java.util.stream.IntStream.range(0, 101).boxed().toList());
        details.put("text", "x".repeat(10_001));

        var result = AuditEvidenceSanitizer.sanitize(details);

        assertThat(result).containsEntry("_truncated", true);
        assertThat((List<?>) result.get("items")).hasSize(100);
        assertThat(result.get("text").toString()).hasSize(10_011).endsWith("[TRUNCATED]");
    }
}
