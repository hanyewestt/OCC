package com.csd.occ.planning.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ApplicationAuditCaptureServiceTest {
    @Test
    void unavailableProviderMetricsAreStoredAsNull() {
        var events = mock(ApplicationAuditEventRepository.class);
        var service = new ApplicationAuditCaptureService(events,
                Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC));

        service.recordAi("AI_NOTICE_SUGGESTION_FAILURE", "FAILURE", null, null, null,
                "NOTICE_SUGGESTION", null, "PROVIDER_UNAVAILABLE", "safe input", null,
                Map.of("promptVersion", "notice-prompt-v1", "schemaVersion", "notice-schema-v1"));

        var captured = ArgumentCaptor.forClass(ApplicationAuditEvent.class);
        verify(events).save(captured.capture());
        assertThat(captured.getValue().details())
                .containsEntry("promptTokens", null)
                .containsEntry("completionTokens", null)
                .containsEntry("totalTokens", null)
                .containsEntry("latencyMillis", null)
                .containsEntry("promptVersion", "notice-prompt-v1")
                .containsEntry("schemaVersion", "notice-schema-v1");
    }
}
