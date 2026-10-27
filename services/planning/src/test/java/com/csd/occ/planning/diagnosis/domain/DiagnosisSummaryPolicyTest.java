package com.csd.occ.planning.diagnosis.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DiagnosisSummaryPolicyTest {
    private static final Instant CONFIRMED = Instant.parse("2026-10-25T07:05:31.590871Z");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID INCIDENT_ID = UUID.fromString("4b61cb14-7a2a-4ebd-8245-7e65eec5d23c");
    private static final String INCIDENT_EVIDENCE_ID = "traffic-incident:" + INCIDENT_ID;

    private final DiagnosisSummaryPolicy policy = new DiagnosisSummaryPolicy();

    @Test
    void summarizesOnlyValidatedIncidentEvidenceAndKeepsTechnicalFactsStructured() {
        var incident = incident(INCIDENT_EVIDENCE_ID, INCIDENT_ID, "Vehicle Breakdown",
                "Breakdown near Teck Whye Avenue", CONFIRMED.minusSeconds(180), new BigDecimal("1.3"), new BigDecimal("103.8"));

        var result = policy.summarize("TRAFFIC_INCIDENT", "Vehicle Breakdown",
                List.of("bunching:event", INCIDENT_EVIDENCE_ID), snapshot(List.of(incident)));

        assertThat(result.likelyCause()).isEqualTo(
                "Possible contributor: Vehicle Breakdown. The provider reported: Breakdown near Teck Whye Avenue.");
        assertThat(result.likelyCause()).doesNotContain(INCIDENT_ID.toString(), "traffic-incident:", "bus-arrival:",
                "2026-10-25T07:05:31.590871Z");
        assertThat(result.evidenceDetails()).singleElement().satisfies(detail -> {
            assertThat(detail.evidenceId()).isEqualTo(INCIDENT_EVIDENCE_ID);
            assertThat(detail.reportTime()).isEqualTo(CONFIRMED.minusSeconds(180));
            assertThat(detail.firstConfirmedAt()).isEqualTo(CONFIRMED);
            assertThat(detail.secondsBeforeFirstConfirmed()).isEqualTo(180);
            assertThat(detail.distanceMetersFromSuppliedBusPosition()).isZero();
            assertThat(detail.monitoredStopCode()).isEqualTo("44251");
        });
    }

    @Test
    void usesAnHonestFallbackWhenTheProviderHasNoDescription() {
        var incident = incident(INCIDENT_EVIDENCE_ID, INCIDENT_ID, "Vehicle Breakdown", null,
                CONFIRMED, null, null);

        var result = policy.summarize("TRAFFIC_INCIDENT", "Vehicle Breakdown",
                List.of(INCIDENT_EVIDENCE_ID), snapshot(List.of(incident)));

        assertThat(result.likelyCause()).isEqualTo(
                "Possible contributor: Vehicle Breakdown. The provider supplied no location description.");
        assertThat(result.evidenceDetails()).singleElement().satisfies(detail -> {
            assertThat(detail.secondsBeforeFirstConfirmed()).isZero();
            assertThat(detail.distanceMetersFromSuppliedBusPosition()).isNull();
            assertThat(detail.busPositionEvidenceId()).isEqualTo("bus-arrival:position");
        });
    }

    @Test
    void retainsTimestampDifferenceWhenWholeSecondOffsetIsZero() {
        var incident = incident(INCIDENT_EVIDENCE_ID, INCIDENT_ID, "Vehicle Breakdown", "Breakdown reported",
                CONFIRMED.minusMillis(500), null, null);

        var result = policy.summarize("TRAFFIC_INCIDENT", "Vehicle Breakdown",
                List.of(INCIDENT_EVIDENCE_ID), snapshot(List.of(incident)));

        assertThat(result.evidenceDetails()).singleElement().satisfies(detail -> {
            assertThat(detail.secondsBeforeFirstConfirmed()).isZero();
            assertThat(detail.reportTime()).isNotEqualTo(detail.firstConfirmedAt());
        });
    }

    @Test
    void fallsBackForRetainedSnapshotsWithMissingScopeProvenance() {
        var incident = incident(INCIDENT_EVIDENCE_ID, INCIDENT_ID, "Vehicle Breakdown", "Breakdown reported",
                CONFIRMED, null, null);
        var validSnapshot = snapshot(List.of(incident));
        var event = validSnapshot.event();
        var unscopedEvent = new BunchingEventFacts(event.eventId(), event.bunchingEventId(), null, null,
                event.serviceId(), event.directionId(), event.monitoredStopCode(), event.leadingObservationId(),
                event.followingObservationId(), event.status(), event.expectedHeadwaySeconds(),
                event.observedHeadwaySeconds(), event.firstConfirmedAt(), event.confirmingObservationTimes(),
                event.confirmingObservationIds(), event.evidenceIds(), event.detectionRuleVersion());
        var unscopedSnapshot = new EvidenceSnapshot(unscopedEvent, validSnapshot.context(),
                validSnapshot.allowedEvidenceIds(), validSnapshot.classification());

        var result = policy.summarize("TRAFFIC_INCIDENT", "Vehicle Breakdown",
                List.of(INCIDENT_EVIDENCE_ID), unscopedSnapshot);

        assertThat(result.likelyCause()).contains("Details are unavailable");
        assertThat(result.evidenceDetails()).isEmpty();
    }

    @Test
    void listsDistinctSupportedTypesDeterministicallyAndOmitsUnassociatedIncidents() {
        var roadWorks = incident("incident:b", UUID.randomUUID(), "Road Works", "Lane closure on the road",
                CONFIRMED.minusSeconds(60), null, null);
        var breakdown = incident("incident:a", UUID.randomUUID(), "Vehicle Breakdown", "Disabled vehicle reported",
                CONFIRMED.minusSeconds(90), null, null);
        var unrelated = incident("incident:z", UUID.randomUUID(), "Flood", "Flooding reported",
                CONFIRMED.minusSeconds(30), null, null);

        var result = policy.summarize("TRAFFIC_INCIDENT", null,
                List.of("incident:b", "incident:a"), snapshot(List.of(unrelated, roadWorks, breakdown)));

        assertThat(result.likelyCause()).startsWith("Possible contributors: Road Works, Vehicle Breakdown.");
        assertThat(result.likelyCause()).contains("Lane closure on the road", "Disabled vehicle reported")
                .doesNotContain("Flood", "Flooding reported", "incident:a", "incident:b");
        assertThat(result.evidenceDetails()).extracting(DiagnosisEvidenceDetail::incidentType)
                .containsExactly("Vehicle Breakdown", "Road Works");
    }

    @Test
    void unknownAndMissingLegacyEvidenceRemainExplicitAndNeverExposeUnsupportedIds() {
        var unknown = policy.summarize("UNKNOWN", null, List.of("bunching:event", INCIDENT_EVIDENCE_ID), snapshot(
                List.of(incident(INCIDENT_EVIDENCE_ID, INCIDENT_ID, "Vehicle Breakdown", "Not supported",
                        CONFIRMED, null, null))));
        var legacy = policy.summarize("TRAFFIC_INCIDENT", "Vehicle Breakdown", List.of("unsupported:incident"), null);

        assertThat(unknown.likelyCause()).contains("No supported contributing incident");
        assertThat(unknown.evidenceDetails()).isEmpty();
        assertThat(legacy.likelyCause()).isEqualTo(
                "Possible contributor: Vehicle Breakdown. Details are unavailable for this retained diagnosis.");
        assertThat(legacy.likelyCause()).doesNotContain("unsupported:incident");
    }

    @Test
    void sanitizesHtmlLikeTextAndTruncatesWithoutSplittingSurrogatePairs() {
        String source = "<script>alert('x')</script><b>Near the bridge</b> " + "🚧".repeat(220);
        var incidents = new java.util.ArrayList<IncidentEvidence>();
        var evidenceIds = new java.util.ArrayList<String>();
        for (int index = 0; index < 8; index++) {
            String evidenceId = "incident:%02d".formatted(index);
            String type = index == 0 ? "Vehicle Breakdown" : "Hazard %d %s".formatted(index, "T".repeat(50));
            String message = index == 0 ? source : index == 1 ? "R".repeat(480) + "🚧" : "F".repeat(490);
            incidents.add(incident(evidenceId, index == 0 ? INCIDENT_ID : UUID.randomUUID(), type, message,
                    CONFIRMED, null, null));
            evidenceIds.add(evidenceId);
        }

        var result = policy.summarize("TRAFFIC_INCIDENT", "Vehicle Breakdown",
                evidenceIds, snapshot(incidents));

        assertThat(result.likelyCause()).contains("Near the bridge").doesNotContain("<script>", "</script>", "<b>", "</b>");
        assertThat(result.likelyCause().length()).isLessThanOrEqualTo(1_000);
        assertThat(result.likelyCause().length()).isGreaterThan(900);
        assertThat(Character.isHighSurrogate(result.likelyCause().charAt(result.likelyCause().length() - 1))).isFalse();
        assertThat(result.evidenceDetails().getFirst().message()).doesNotContain("<script>", "<b>");
    }

    private EvidenceSnapshot snapshot(List<IncidentEvidence> incidents) {
        var event = new BunchingEventFacts(UUID.randomUUID(), UUID.randomUUID(), "SIMULATION", RUN_ID,
                "190", 1, "44251", UUID.randomUUID(), UUID.randomUUID(), "CONFIRMED", 600,
                List.of(60, 60), CONFIRMED, List.of(CONFIRMED.minusSeconds(10), CONFIRMED),
                List.of(UUID.randomUUID(), UUID.randomUUID()), List.of("bunching:event"), "test-rule");
        var arrival = new ArrivalEvidence("bus-arrival:position", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "190", "44251", 1, CONFIRMED, CONFIRMED, new BigDecimal("1.3"), new BigDecimal("103.8"),
                null, "DD", "SIMULATION", RUN_ID, null);
        var context = new RootCauseContext("SIMULATION", RUN_ID, null, List.of(arrival), incidents, List.of());
        var allowedIds = java.util.stream.Stream.concat(
                java.util.stream.Stream.of("bunching:event", arrival.evidenceId()),
                incidents.stream().map(IncidentEvidence::evidenceId)).toList();
        return new EvidenceSnapshot(event, context, allowedIds, "SIMULATION");
    }

    private IncidentEvidence incident(String evidenceId, UUID id, String type, String message, Instant observedAt,
                                      BigDecimal latitude, BigDecimal longitude) {
        return new IncidentEvidence(evidenceId, id, type, message, latitude, longitude, observedAt,
                CONFIRMED.minusSeconds(30), "SIMULATION", RUN_ID);
    }
}
