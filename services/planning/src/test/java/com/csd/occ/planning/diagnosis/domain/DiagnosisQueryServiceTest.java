package com.csd.occ.planning.diagnosis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DiagnosisQueryServiceTest {
    private static final Instant FIRST_CONFIRMED = Instant.parse("2026-10-25T07:05:31Z");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void findsTheCurrentProducerEventRequestEvenWhenDiagnosisFailed() {
        var requests = mock(DiagnosisRequestRepository.class);
        var diagnoses = mock(RootCauseDiagnosisRepository.class);
        var query = new DiagnosisQueryService(requests, diagnoses,
                mock(RecommendationDeliveryRepository.class), mock(SnapshotCodecPort.class));
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-21T08:09:26Z");
        var request = new DiagnosisRequest(requestId, EventType.BUS_BUNCHING, "disruption", eventId,
                DiagnosisRequestService.SCHEMA_VERSION, now);
        request.failTerminal("MODEL_PROVIDER_FAILURE", now);
        when(requests.findByEventTypeAndProducerAndEventIdAndSchemaVersion("BUS_BUNCHING", "disruption",
                eventId, DiagnosisRequestService.SCHEMA_VERSION)).thenReturn(Optional.of(request));
        when(diagnoses.findByRequestId(requestId)).thenReturn(Optional.empty());

        var result = query.findRequestByEvent(eventId, EventType.BUS_BUNCHING);
        assertThat(result.requestId()).isEqualTo(requestId);
        assertThat(result.status()).isEqualTo(DiagnosisRequestStatus.FAILED_TERMINAL);
        assertThat(result.failureCode()).isEqualTo("MODEL_PROVIDER_FAILURE");
        assertThat(result.diagnosisId()).isNull();
        assertThatThrownBy(() -> query.findRequestByEvent(UUID.randomUUID(), EventType.BUS_BUNCHING))
                .isInstanceOf(DiagnosisQueryService.DiagnosisNotFoundException.class);
    }

    @Test
    void presentsRetainedDiagnosesFromTheirStructuredSnapshot() {
        var requests = mock(DiagnosisRequestRepository.class);
        var diagnoses = mock(RootCauseDiagnosisRepository.class);
        var deliveries = mock(RecommendationDeliveryRepository.class);
        var codec = mock(SnapshotCodecPort.class);
        var query = new DiagnosisQueryService(requests, diagnoses, deliveries, codec);
        UUID diagnosisId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        var request = new DiagnosisRequest(requestId, EventType.BUS_BUNCHING, "disruption", UUID.randomUUID(),
                DiagnosisRequestService.SCHEMA_VERSION, FIRST_CONFIRMED);
        var entity = diagnosis(diagnosisId, requestId, "snapshot-json");
        when(diagnoses.findById(diagnosisId)).thenReturn(Optional.of(entity));
        when(requests.findById(requestId)).thenReturn(Optional.of(request));
        when(deliveries.findByDiagnosisId(diagnosisId)).thenReturn(Optional.empty());
        when(codec.decodeStringList("evidence-json")).thenReturn(List.of("bunching:event", "traffic-incident:incident-id"));
        when(codec.decodeStringList("limitations-json")).thenReturn(List.of("Road relevance has not been verified."));
        when(codec.decode("snapshot-json")).thenReturn(snapshot());

        var result = query.find(diagnosisId);

        assertThat(result.likelyCause()).isEqualTo(
                "Possible contributor: Vehicle Breakdown. The provider reported: Breakdown near Teck Whye Avenue.");
        assertThat(result.likelyCause()).doesNotContain("legacy raw evidence text", "traffic-incident:");
        assertThat(result.limitations()).containsExactly("Road relevance has not been verified.");
        assertThat(result.evidenceDetails()).singleElement().satisfies(detail -> {
            assertThat(detail.evidenceId()).isEqualTo("traffic-incident:incident-id");
            assertThat(detail.distanceMetersFromSuppliedBusPosition()).isZero();
        });
    }

    @Test
    void batchLoadsRecommendationSummariesAndFallsBackForCorruptOrMissingSnapshots() {
        var diagnoses = mock(RootCauseDiagnosisRepository.class);
        var query = new DiagnosisQueryService(mock(DiagnosisRequestRepository.class), diagnoses,
                mock(RecommendationDeliveryRepository.class), mock(SnapshotCodecPort.class));
        UUID firstId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        var entity = diagnosis(firstId, requestId, "corrupt-snapshot");
        when(diagnoses.findAllById(List.of(firstId, missingId))).thenReturn(List.of(entity));
        when(diagnoses.findAllById(List.of(firstId))).thenReturn(List.of(entity));

        Map<UUID, DiagnosisSummaryPolicy.Summary> results = query.summariesByDiagnosisIds(List.of(firstId, missingId));

        assertThat(results.get(firstId).likelyCause()).isEqualTo(
                "Possible contributor: Vehicle Breakdown. Details are unavailable for this retained diagnosis.");
        assertThat(results.get(missingId).likelyCause()).isEqualTo(
                "Diagnosis details are unavailable for this retained result.");
        assertThat(results.get(firstId).evidenceDetails()).isEmpty();
        assertThat(query.summariesByDiagnosisIds(List.of(firstId))).containsKey(firstId);
        org.mockito.Mockito.verify(diagnoses, org.mockito.Mockito.times(2)).findAllById(org.mockito.ArgumentMatchers.anyList());
    }

    private RootCauseDiagnosisEntity diagnosis(UUID diagnosisId, UUID requestId, String snapshot) {
        var entity = mock(RootCauseDiagnosisEntity.class);
        when(entity.id()).thenReturn(diagnosisId);
        when(entity.requestId()).thenReturn(requestId);
        when(entity.causeCode()).thenReturn("TRAFFIC_INCIDENT");
        when(entity.causeSubtype()).thenReturn("Vehicle Breakdown");
        when(entity.likelyCause()).thenReturn("legacy raw evidence text with UUIDs");
        when(entity.supportingEvidenceIdsJson()).thenReturn("evidence-json");
        when(entity.limitationsJson()).thenReturn("limitations-json");
        when(entity.evidenceSnapshotJson()).thenReturn(snapshot);
        when(entity.completedAt()).thenReturn(FIRST_CONFIRMED);
        return entity;
    }

    private EvidenceSnapshot snapshot() {
        UUID eventId = UUID.randomUUID();
        var event = new BunchingEventFacts(eventId, UUID.randomUUID(), "SIMULATION", RUN_ID,
                "190", 1, "44251", UUID.randomUUID(), UUID.randomUUID(), "CONFIRMED", 600,
                List.of(60, 60), FIRST_CONFIRMED, List.of(FIRST_CONFIRMED.minusSeconds(10), FIRST_CONFIRMED),
                List.of(UUID.randomUUID(), UUID.randomUUID()), List.of("bunching:event"), "test-rule");
        var arrival = new ArrivalEvidence("bus-arrival:position", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "190", "44251", 1, FIRST_CONFIRMED, FIRST_CONFIRMED, new BigDecimal("1.3"), new BigDecimal("103.8"),
                null, "DD", "SIMULATION", RUN_ID, null);
        var incident = new IncidentEvidence("traffic-incident:incident-id", UUID.randomUUID(), "Vehicle Breakdown",
                "Breakdown near Teck Whye Avenue", new BigDecimal("1.3"), new BigDecimal("103.8"),
                FIRST_CONFIRMED.minusSeconds(180), FIRST_CONFIRMED.minusSeconds(30), "SIMULATION", RUN_ID);
        var context = new RootCauseContext("SIMULATION", RUN_ID, null, List.of(arrival), List.of(incident), List.of());
        return new EvidenceSnapshot(event, context, List.of("bunching:event", arrival.evidenceId(), incident.evidenceId()),
                "SIMULATION");
    }
}
