package com.csd.occ.disruption.bunching.infrastructure.jackson;

import static org.assertj.core.api.Assertions.assertThat;

import com.csd.occ.disruption.bunching.domain.AlertEvent;
import com.csd.occ.disruption.bunching.domain.PlanningEventSubmissionPort;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JacksonPlanningEventSubmissionAdapterTest {
    @Test
    void encodedBodyHasStableIdentityAndRecomputableSemanticFingerprint() {
        Instant first = Instant.parse("2026-09-27T23:55:00Z");
        Instant second = Instant.parse("2026-09-28T00:00:00Z");
        UUID run = UUID.randomUUID();
        UUID[] observations = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        AlertEvent event = new AlertEvent(UUID.randomUUID(), "OPENED", "190", "44009", 1, UUID.randomUUID(),
                UUID.randomUUID(), second, second, "{}", "SIMULATION", run, observations[2], observations[3],
                600, 360, 390, first, second, observations[0], observations[1], observations[2], observations[3],
                "bunching-gap-v1");
        PlanningEventSubmissionPort adapter = new JacksonPlanningEventSubmissionAdapter(
                JsonMapper.builder().findAndAddModules().build());

        var encoded = adapter.encode(event);

        assertThat(encoded.schemaVersion()).isEqualTo("planning-event-v1");
        assertThat(encoded.payloadJson()).contains("\"eventId\":\"" + event.id() + "\"")
                .contains("\"disruptionId\":\"" + event.alertId() + "\"")
                .contains("\"observedHeadwaySeconds\":[360,390]");
        assertThat(adapter.fingerprint(encoded.payloadJson(), event.id())).isEqualTo(encoded.payloadFingerprint());
    }
}
