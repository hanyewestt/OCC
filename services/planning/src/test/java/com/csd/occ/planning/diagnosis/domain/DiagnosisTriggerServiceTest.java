package com.csd.occ.planning.diagnosis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DiagnosisTriggerServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-05T07:00:00Z");
    private final DiagnosisRequestRepository requests = mock(DiagnosisRequestRepository.class);
    private final RootCauseDiagnosisRepository diagnoses = mock(RootCauseDiagnosisRepository.class);
    private final DiagnosisTriggerService service = new DiagnosisTriggerService(requests, diagnoses,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void nullRequestIdentifierIsRejected() {
        assertThatThrownBy(() -> service.trigger(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(requests, diagnoses);
    }

    @Test
    void unknownRequestIsNotFound() {
        assertThatThrownBy(() -> service.trigger(UUID.randomUUID()))
                .isInstanceOf(DiagnosisQueryService.DiagnosisNotFoundException.class);
    }

    @Test
    void queuesPendingWorkWithoutClaimingAnAttempt() {
        var request = request(EventType.BUS_BUNCHING);
        var result = service.trigger(request.id());
        assertThat(result.status()).isEqualTo(DiagnosisRequestStatus.PENDING);
        assertThat(request.isProcessingRequested()).isTrue();
        assertThat(request.attemptCount()).isZero();
        assertThat(request.leaseUntil()).isNull();
        verify(requests).findByIdForUpdate(request.id());
    }

    @Test
    void repeatedTriggersPreserveAnActiveLeaseAndAttemptCount() {
        var request = request(EventType.BUS_BUNCHING);
        request.claim(NOW.plusSeconds(300), NOW.minusSeconds(1));
        service.trigger(request.id());
        service.trigger(request.id());
        assertThat(request.status()).isEqualTo(DiagnosisRequestStatus.PROCESSING);
        assertThat(request.attemptCount()).isEqualTo(1);
        assertThat(request.leaseUntil()).isEqualTo(NOW.plusSeconds(300));
        assertThat(request.updatedAt()).isEqualTo(NOW.minusSeconds(1));
    }

    @Test
    void repeatedTriggersPreserveRetryBackoff() {
        var request = request(EventType.BUS_BUNCHING);
        request.claim(NOW.plusSeconds(300), NOW);
        request.failRetryable("MODEL_PROVIDER_FAILURE", NOW.plusSeconds(30), NOW);
        var result = service.trigger(request.id());
        assertThat(result.status()).isEqualTo(DiagnosisRequestStatus.FAILED_RETRYABLE);
        assertThat(request.nextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(request.attemptCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    void completedRequestsWithoutDiagnosisKeepTheirOutcome(EventType type) {
        var request = request(type);
        request.completeNoAction("TRAFFIC_INCIDENT_NO_OPERATIONAL_ACTION", NOW.minusSeconds(1));
        var result = service.trigger(request.id());
        assertThat(result.status()).isEqualTo(DiagnosisRequestStatus.COMPLETED);
        assertThat(result.policyOutcome()).isEqualTo("TRAFFIC_INCIDENT_NO_OPERATIONAL_ACTION");
        assertThat(result.diagnosisId()).isNull();
        assertThat(request.updatedAt()).isEqualTo(NOW.minusSeconds(1));
    }

    @Test
    void completedBunchingRequestReturnsItsDiagnosis() {
        var request = request(EventType.BUS_BUNCHING);
        request.complete(NOW);
        var diagnosis = mock(RootCauseDiagnosisEntity.class);
        UUID diagnosisId = UUID.randomUUID();
        when(diagnosis.id()).thenReturn(diagnosisId);
        when(diagnoses.findByRequestId(request.id())).thenReturn(Optional.of(diagnosis));
        assertThat(service.trigger(request.id()).diagnosisId()).isEqualTo(diagnosisId);
    }

    @Test
    void terminalFailureCanBeExplicitlyQueuedAgain() {
        var request = request(EventType.BUS_BUNCHING);
        request.claim(NOW.plusSeconds(300), NOW);
        request.failTerminal("MODEL_OUTPUT_INVALID", NOW.minusSeconds(1));
        assertThat(service.trigger(request.id()).status()).isEqualTo(DiagnosisRequestStatus.PENDING);
        assertThat(request.failureCode()).isNull();
        assertThat(request.nextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void eventLookupQueuesTheLatestRequest() {
        var request = new DiagnosisRequest(UUID.randomUUID(), EventType.TRAFFIC_INCIDENT, "disruption",
                UUID.randomUUID(), DiagnosisRequestService.SCHEMA_VERSION, NOW);
        when(requests.findFirstByEventIdOrderByCreatedAtDesc(request.eventId())).thenReturn(Optional.of(request));
        assertThat(service.triggerByEvent(request.eventId()).requestId()).isEqualTo(request.id());
        assertThat(request.isProcessingRequested()).isTrue();
    }

    private DiagnosisRequest request(EventType type) {
        var request = new DiagnosisRequest(UUID.randomUUID(), type, "disruption", UUID.randomUUID(),
                DiagnosisRequestService.SCHEMA_VERSION, NOW);
        when(requests.findByIdForUpdate(request.id())).thenReturn(Optional.of(request));
        return request;
    }
}
