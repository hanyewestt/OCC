package com.csd.occ.planning.diagnosis.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.csd.occ.planning.diagnosis.api.dto.DiagnosisRequestResponse;
import com.csd.occ.planning.diagnosis.api.dto.TriggerDiagnosisRequest;
import com.csd.occ.planning.diagnosis.infrastructure.config.DiagnosisProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class DiagnosisTriggerServiceTest {

    @Mock private DiagnosisRequestRepository requests;
    @Mock private RootCauseDiagnosisRepository diagnoses;
    @Mock private RootCauseDiagnosisProcessor processor;
    @Mock private RecommendationDeliveryService deliveries;
    @Mock private RecommendationSubmissionPort recommendation;
    @Mock private DiagnosisProperties properties;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-05T07:00:00Z"), ZoneOffset.UTC);
    private DiagnosisTriggerService service;

    @BeforeEach
    void setUp() {
        service = new DiagnosisTriggerService(
                requests, diagnoses, processor, deliveries, recommendation, properties, clock);
    }

    @Test
    void throwsBadRequestWhenRequestIsNull() {
        assertThrows(ResponseStatusException.class, () -> service.trigger(null));
    }

    @Test
    void throwsBadRequestWhenBothEventIdAndRequestIdAreNull() {
        assertThrows(ResponseStatusException.class, () -> service.trigger(new TriggerDiagnosisRequest(null, null)));
    }

    @Test
    void throwsNotFoundWhenRequestDoesNotExist() {
        UUID eventId = UUID.randomUUID();
        when(requests.findFirstByEventIdOrderByCreatedAtDesc(eventId)).thenReturn(Optional.empty());
        when(requests.findByEventTypeAndProducerAndEventIdAndSchemaVersion(
                EventType.BUS_BUNCHING.name(), "disruption", eventId, DiagnosisRequestService.SCHEMA_VERSION))
                .thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.trigger(new TriggerDiagnosisRequest(eventId, null)));
    }

    @Test
    void returnsExistingDiagnosisWhenAlreadyCompleted() {
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID diagnosisId = UUID.randomUUID();
        Instant now = clock.instant();

        DiagnosisRequest request = new DiagnosisRequest(
                requestId, EventType.BUS_BUNCHING, "disruption", eventId,
                DiagnosisRequestService.SCHEMA_VERSION, now);
        request.complete(now);

        RootCauseDiagnosisEntity entity = mock(RootCauseDiagnosisEntity.class);
        when(entity.id()).thenReturn(diagnosisId);

        when(requests.findFirstByEventIdOrderByCreatedAtDesc(eventId)).thenReturn(Optional.of(request));
        when(diagnoses.findByRequestId(requestId)).thenReturn(Optional.of(entity));

        DiagnosisRequestResponse response = service.trigger(new TriggerDiagnosisRequest(eventId, null));

        assertEquals(requestId, response.requestId());
        assertEquals(diagnosisId, response.diagnosisId());
        assertEquals(DiagnosisRequestStatus.COMPLETED, response.status());
        verify(processor, never()).process(any());
        verify(deliveries, never()).claimDue(anyInt(), any());
    }

    @Test
    void claimsAndProcessesPendingRequest() {
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID diagnosisId = UUID.randomUUID();
        Instant now = clock.instant();

        DiagnosisRequest request = new DiagnosisRequest(
                requestId, EventType.BUS_BUNCHING, "disruption", eventId,
                DiagnosisRequestService.SCHEMA_VERSION, now);

        DiagnosisRequest completed = new DiagnosisRequest(
                requestId, EventType.BUS_BUNCHING, "disruption", eventId,
                DiagnosisRequestService.SCHEMA_VERSION, now);
        completed.complete(now);

        RootCauseDiagnosisEntity entity = mock(RootCauseDiagnosisEntity.class);
        when(entity.id()).thenReturn(diagnosisId);

        when(properties.leaseDuration()).thenReturn(Duration.ofMinutes(5));
        when(properties.workerBatchSize()).thenReturn(10);
        when(requests.findFirstByEventIdOrderByCreatedAtDesc(eventId)).thenReturn(Optional.of(request));
        when(diagnoses.findByRequestId(requestId))
                .thenReturn(Optional.empty()) // Before process
                .thenReturn(Optional.of(entity)); // After process
        when(requests.findById(requestId)).thenReturn(Optional.of(completed));
        when(deliveries.claimDue(anyInt(), any())).thenReturn(List.of());

        DiagnosisRequestResponse response = service.trigger(new TriggerDiagnosisRequest(eventId, null));

        assertNotNull(response);
        assertEquals(requestId, response.requestId());
        assertEquals(diagnosisId, response.diagnosisId());
        assertEquals(DiagnosisRequestStatus.COMPLETED, response.status());
        verify(requests).save(request);
        verify(processor).process(requestId);
        verify(deliveries).claimDue(10, Duration.ofMinutes(5));
    }
}
