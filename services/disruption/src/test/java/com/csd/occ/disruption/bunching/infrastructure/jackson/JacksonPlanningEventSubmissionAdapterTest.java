package com.csd.occ.disruption.bunching.infrastructure.jackson;

import static org.assertj.core.api.Assertions.assertThat;

import com.csd.occ.disruption.bunching.domain.AlertEvent;
import com.csd.occ.disruption.bunching.domain.PlanningEventSubmissionPort;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentPlanningSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JacksonPlanningEventSubmissionAdapterTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"SIMULATION", "OBSERVATION_ONLY"})
    void encodedBodyHasStableIdentityAndRecomputableSemanticFingerprint(String classification) {
        Instant first = Instant.parse("2026-09-27T23:55:00Z");
        Instant second = Instant.parse("2026-09-28T00:00:00Z");
        UUID run = "SIMULATION".equals(classification) ? UUID.randomUUID() : null;
        UUID[] observations = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        AlertEvent event = new AlertEvent(UUID.randomUUID(), "OPENED", "190", "44009", 1, UUID.randomUUID(),
                UUID.randomUUID(), second, second, "{}", classification, run, observations[2], observations[3],
                600, 360, 390, first, second, observations[0], observations[1], observations[2], observations[3],
                "bunching-gap-v1");
        PlanningEventSubmissionPort adapter = new JacksonPlanningEventSubmissionAdapter(
                JsonMapper.builder().findAndAddModules().build());

        var encoded = adapter.encode(event);

        assertThat(encoded.schemaVersion()).isEqualTo("planning-event-v1");
        assertThat(encoded.payloadJson()).contains("\"eventId\":\"" + event.id() + "\"")
                .contains("\"disruptionId\":\"" + event.alertId() + "\"")
                .contains("\"observedHeadwaySeconds\":[360,390]")
                .doesNotContain("trafficSnapshot");
        assertThat(adapter.fingerprint(encoded.payloadJson(), event.id())).isEqualTo(encoded.payloadFingerprint());
    }

    @Test
    void trafficLifecycleFactsUseTheirTypedSnapshotAndStableFingerprint() {
        UUID eventId = UUID.randomUUID();
        UUID disruptionId = UUID.randomUUID();
        UUID observationId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-15T06:00:00Z");
        TrafficIncidentPlanningSnapshot snapshot = new TrafficIncidentPlanningSnapshot(eventId, disruptionId,
                "UPDATED", "OBSERVATION_ONLY", null, observationId, 3, UUID.randomUUID(), "LTA", "a".repeat(64),
                "Road Obstruction", "Westbound lane blocked", new BigDecimal("1.38"), new BigDecimal("103.75"),
                now.minusSeconds(10), now.minusSeconds(5), now);
        PlanningEventSubmissionPort adapter = new JacksonPlanningEventSubmissionAdapter(
                JsonMapper.builder().findAndAddModules().build());

        var encoded = adapter.encodeTraffic(snapshot);

        assertThat(encoded.payloadJson()).contains("\"eventType\":\"TRAFFIC_INCIDENT\"")
                .contains("\"action\":\"UPDATED\"")
                .contains("\"sourceObservationId\":\"" + observationId + "\"")
                .contains("\"sourceRevision\":3")
                .doesNotContain("eventSnapshot");
        assertThat(adapter.fingerprint(encoded.payloadJson(), eventId)).isEqualTo(encoded.payloadFingerprint());
    }
}
