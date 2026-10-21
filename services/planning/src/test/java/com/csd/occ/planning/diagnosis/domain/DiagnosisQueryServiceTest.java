package com.csd.occ.planning.diagnosis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DiagnosisQueryServiceTest {
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
}
