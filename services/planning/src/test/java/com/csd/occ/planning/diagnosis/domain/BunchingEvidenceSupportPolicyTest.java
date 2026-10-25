package com.csd.occ.planning.diagnosis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BunchingEvidenceSupportPolicyTest {
    private static final Instant CONFIRMED = Instant.parse("2026-10-25T04:00:00Z");
    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final BunchingEvidenceSupportPolicy policy = new BunchingEvidenceSupportPolicy();

    @Test
    void describesSupportedTimeAndSuppliedPositionWithoutClaimingRoadVerification() {
        var incident = incident("incident:one", "Accident", CONFIRMED.minusSeconds(180), "1.3", "103.8");
        var result = associate(List.of(incident), output("Accident", "incident:one"));

        assertThat(result.causeCode()).isEqualTo("TRAFFIC_INCIDENT");
        assertThat(result.causeSubtype()).isEqualTo("Accident");
        assertThat(result.evidenceIds()).containsExactly("event:one", "incident:one");
        assertThat(result.facts()).anySatisfy(fact -> assertThat(fact).contains("reported 180 seconds before"));
        assertThat(result.facts()).anySatisfy(fact -> assertThat(fact).contains("approximately 0 m", "supplied bus position arrival:one"));
        assertThat(result.limitations()).anySatisfy(value -> assertThat(value).contains("Road and direction relevance have not been verified", "does not establish impact or causation"));
    }

    @Test
    void unknownReportTimeDoesNotUseFetchTimeForChronology() {
        var incident = incident("incident:one", "Accident", null, "1.3", "103.8");
        var result = associate(List.of(incident), output("Accident", "incident:one"));

        assertThat(result.causeCode()).isEqualTo("TRAFFIC_INCIDENT");
        assertThat(result.facts()).noneSatisfy(fact -> assertThat(fact).contains("seconds before"));
        assertThat(result.limitations()).anySatisfy(value -> assertThat(value).contains("report time is unknown", "fetch time does not establish"));
    }

    @Test
    void missingCoordinatesAllowOnlySupportedChronologyFacts() {
        var result = associate(List.of(incident("incident:one", "Accident", CONFIRMED.minusSeconds(180), null, null)),
                output("Accident", "incident:one"));

        assertThat(result.causeCode()).isEqualTo("TRAFFIC_INCIDENT");
        assertThat(result.facts()).noneSatisfy(fact -> assertThat(fact).contains("approximately"));
        assertThat(result.limitations()).anySatisfy(value -> assertThat(value).contains("distance cannot be computed"));
    }

    @Test
    void noSupportedTimeOrLocationKeepsCauseUnknown() {
        var result = associate(List.of(incident("incident:one", "Accident", null, null, null)),
                output("Accident", "incident:one"));

        assertThat(result.causeCode()).isEqualTo("UNKNOWN");
        assertThat(result.causeSubtype()).isNull();
        assertThat(result.evidenceIds()).containsExactly("event:one");
    }

    @Test
    void aKnownReportAfterConfirmationOrBeforeLookbackCannotEstablishAContributingIncident() {
        for (Instant report : List.of(CONFIRMED.plusSeconds(1), CONFIRMED.minusSeconds(901))) {
            var result = associate(List.of(incident("incident:one", "Accident", report, "1.3", "103.8")),
                    output("Accident", "incident:one"));
            assertThat(result.causeCode()).isEqualTo("UNKNOWN");
            assertThat(result.limitations()).anySatisfy(value -> assertThat(value).contains("outside the preceding evidence window"));
        }
        assertThat(associate(List.of(incident("incident:one", "Accident", CONFIRMED.minusSeconds(900), null, null)),
                output("Accident", "incident:one")).causeCode()).isEqualTo("TRAFFIC_INCIDENT");
    }

    @Test
    void retainsAllSupportedCitedIncidentsWithoutChoosingAWeightedWinner() {
        var first = incident("incident:a", "Accident", CONFIRMED.minusSeconds(800), null, null);
        var second = incident("incident:b", "Road Works", CONFIRMED.minusSeconds(10), "1.3", "103.8");
        var output = output(null, "incident:a", "incident:b");
        var result = associate(List.of(second, first), output);

        assertThat(result.evidenceIds()).containsExactly("event:one", "incident:a", "incident:b");
        assertThat(result.causeSubtype()).isNull();
        assertThat(result).isEqualTo(associate(List.of(first, second), output));
    }

    @Test
    void rejectsInventedEvidenceAndIncidentSubtype() {
        var incident = incident("incident:one", "Accident", CONFIRMED.minusSeconds(180), "1.3", "103.8");
        assertThatThrownBy(() -> associate(List.of(incident), output("Accident", "invented")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside the supplied snapshot");
        assertThatThrownBy(() -> associate(List.of(incident), output("Flood", "incident:one")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("subtype must match");
        assertThatThrownBy(() -> associate(List.of(incident), output(null, "arrival:one")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must cite an incident");
    }

    @Test
    void rejectsMismatchedScopeAndUncitedTrafficDiagnosis() {
        var wrongScope = new RootCauseContext("OBSERVATION_ONLY", null, null, List.of(), List.of(), List.of());
        assertThatThrownBy(() -> policy.associate(event(), wrongScope, output(null), Duration.ofMinutes(15), 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("provenance");
        assertThatThrownBy(() -> associate(List.of(), output(null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requires supporting evidence");
    }

    @Test
    void unknownModelConclusionStaysUnknownAndStillValidatesCitations() {
        var output = new ModelOutput("UNKNOWN", null, "Unknown", List.of("event:one"), List.of());
        assertThat(associate(List.of(), output).causeCode()).isEqualTo("UNKNOWN");
        var invalid = new ModelOutput("UNKNOWN", null, "Unknown", List.of("invented"), List.of());
        assertThatThrownBy(() -> associate(List.of(), invalid)).isInstanceOf(IllegalArgumentException.class);
    }

    private BunchingEvidenceSupportPolicy.AssociatedEvidence associate(List<IncidentEvidence> incidents, ModelOutput output) {
        var position = new ArrivalEvidence("arrival:one", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "190", "44251", 1, CONFIRMED.plusSeconds(120), CONFIRMED, new BigDecimal("1.3"),
                new BigDecimal("103.8"), null, "DD", "SIMULATION", RUN, null);
        return policy.associate(event(), new RootCauseContext("SIMULATION", RUN, null, List.of(position), incidents, List.of()),
                output, Duration.ofMinutes(15), 1);
    }

    private ModelOutput output(String subtype, String... ids) {
        return new ModelOutput("TRAFFIC_INCIDENT", subtype, "Possible reported incident", List.of(ids), List.of());
    }

    private IncidentEvidence incident(String id, String type, Instant reportedAt, String lat, String lon) {
        return new IncidentEvidence(id, UUID.randomUUID(), type, "Provider message", lat == null ? null : new BigDecimal(lat),
                lon == null ? null : new BigDecimal(lon), reportedAt, CONFIRMED.minusSeconds(30), "SIMULATION", RUN);
    }

    private BunchingEventFacts event() {
        return new BunchingEventFacts(UUID.randomUUID(), UUID.randomUUID(), "SIMULATION", RUN, "190", 1, "44251",
                UUID.randomUUID(), UUID.randomUUID(), "CONFIRMED", 600, List.of(45, 45), CONFIRMED,
                List.of(CONFIRMED, CONFIRMED.plusSeconds(5)), List.of(UUID.randomUUID(), UUID.randomUUID()),
                List.of("event:one"), "test-rule");
    }
}
