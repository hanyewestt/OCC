package com.csd.occ.planning.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import com.csd.occ.planning.domain.audit.ApplicationAuditCapturePort;
import org.junit.jupiter.api.Test;

class DurableBunchingRecommendationServiceTest {

    private static final UUID RECOMMENDATION_ID =
            UUID.fromString("f07c921a-bd3b-4a97-8b17-a384ef1269a7");
    private static final UUID REQUEST_ID =
            UUID.fromString("6e2f6b21-9c43-4c17-93c5-10b2d820b9a7");
    private static final String REQUEST_HASH = "a".repeat(64);

    @Test
    void newRecommendationPersistsAssociatedModelInvocation() {
        BunchingRecommendationService recommendations = mock(BunchingRecommendationService.class);
        RecommendationRecordPort records = mock(RecommendationRecordPort.class);
        ModelInvocationRecordPort modelInvocations = mock(ModelInvocationRecordPort.class);
        ConfirmedBunchingEvent event = mock(ConfirmedBunchingEvent.class);
        BunchingRootCauseDiagnosis diagnosis = mock(BunchingRootCauseDiagnosis.class);
        ValidatedMitigationRecommendation recommendation = mock(ValidatedMitigationRecommendation.class);
        ValidatedMitigationContext context = mock(ValidatedMitigationContext.class);
        ModelInvocationRecord audit = mock(ModelInvocationRecord.class);
        ApplicationAuditCapturePort auditCapture = mock(ApplicationAuditCapturePort.class);
        ModelInvocationMetadata metadata = mock(ModelInvocationMetadata.class);
        when(recommendation.recommendationId()).thenReturn(RECOMMENDATION_ID);
        when(audit.recommendationId()).thenReturn(RECOMMENDATION_ID);
        when(audit.metadata()).thenReturn(metadata);
        when(audit.outcome()).thenReturn(ModelInvocationOutcome.ACCEPTED);
        when(audit.failureCategory()).thenReturn(Optional.empty());
        when(metadata.invocationId()).thenReturn(UUID.randomUUID());
        when(metadata.provider()).thenReturn(ModelInvocationProvider.GEMINI);
        when(metadata.model()).thenReturn("synthetic-model");
        when(metadata.promptVersion()).thenReturn("prompt-v1");
        when(metadata.outputSchemaVersion()).thenReturn("schema-v1");
        when(metadata.startedAt()).thenReturn(Instant.parse("2026-09-28T00:00:00Z"));
        when(metadata.completedAt()).thenReturn(Instant.parse("2026-09-28T00:00:01Z"));
        when(metadata.latencyMilliseconds()).thenReturn(100L);
        when(metadata.tokenUsage()).thenReturn(Optional.empty());
        when(metadata.inputSnapshot()).thenReturn("api_key=synthetic-secret");
        when(metadata.generatedResponse()).thenReturn("safe response");
        BunchingRecommendationDecision decision = new BunchingRecommendationDecision(
                recommendation,
                context,
                Optional.of(audit));
        when(recommendations.evaluate(
                RECOMMENDATION_ID,
                SourceClassification.SIMULATION,
                event,
                diagnosis)).thenReturn(Optional.of(decision));
        when(records.findByIdempotencyKey(RECOMMENDATION_ID)).thenReturn(Optional.empty());
        when(records.saveIfAbsent(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        DurableBunchingRecommendationService service = service(recommendations, records, modelInvocations, auditCapture);

        var result = service.recommend(
                RECOMMENDATION_ID,
                REQUEST_HASH,
                REQUEST_ID,
                SourceClassification.SIMULATION,
                event,
                diagnosis);

        assertThat(result).isPresent();
        verify(modelInvocations).save(audit);
        UUID invocationId = metadata.invocationId();
        Instant completedAt = metadata.completedAt();
        verify(auditCapture).recordRecommendationAttempt(org.mockito.ArgumentMatchers.eq(invocationId),
                org.mockito.ArgumentMatchers.eq(completedAt), org.mockito.ArgumentMatchers.eq(RECOMMENDATION_ID),
                org.mockito.ArgumentMatchers.eq(REQUEST_ID), org.mockito.ArgumentMatchers.eq("ACCEPTED"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void idempotentReplayDoesNotEvaluateOrCreateAnotherModelInvocation() {
        BunchingRecommendationService recommendations = mock(BunchingRecommendationService.class);
        RecommendationRecordPort records = mock(RecommendationRecordPort.class);
        ModelInvocationRecordPort modelInvocations = mock(ModelInvocationRecordPort.class);
        RecommendationRecord existing = mock(RecommendationRecord.class);
        ConfirmedBunchingEvent event = mock(ConfirmedBunchingEvent.class);
        BunchingRootCauseDiagnosis diagnosis = mock(BunchingRootCauseDiagnosis.class);
        when(existing.idempotencyKey()).thenReturn(RECOMMENDATION_ID);
        when(existing.requestHash()).thenReturn(REQUEST_HASH);
        when(records.findByIdempotencyKey(RECOMMENDATION_ID)).thenReturn(Optional.of(existing));
        ApplicationAuditCapturePort auditCapture = mock(ApplicationAuditCapturePort.class);
        DurableBunchingRecommendationService service = service(recommendations, records, modelInvocations, auditCapture);

        var result = service.recommend(
                RECOMMENDATION_ID,
                REQUEST_HASH,
                REQUEST_ID,
                SourceClassification.SIMULATION,
                event,
                diagnosis);

        assertThat(result).contains(existing);
        verify(recommendations, never()).evaluate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(modelInvocations, never()).save(org.mockito.ArgumentMatchers.any());
        verify(auditCapture, never()).recordRecommendationAttempt(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void independentlyCommittedInvocationIsAuditedWhenRecommendationLosesIdempotencyRace() {
        var recommendations = mock(BunchingRecommendationService.class);
        var records = mock(RecommendationRecordPort.class);
        var modelInvocations = mock(ModelInvocationRecordPort.class);
        var auditCapture = mock(ApplicationAuditCapturePort.class);
        var event = mock(ConfirmedBunchingEvent.class);
        var diagnosis = mock(BunchingRootCauseDiagnosis.class);
        var recommendation = mock(ValidatedMitigationRecommendation.class);
        var context = mock(ValidatedMitigationContext.class);
        var invocation = mock(ModelInvocationRecord.class);
        var metadata = mock(ModelInvocationMetadata.class);
        var existing = mock(RecommendationRecord.class);
        UUID invocationId = UUID.randomUUID();
        Instant completedAt = Instant.parse("2026-09-28T00:00:01Z");
        when(recommendation.recommendationId()).thenReturn(RECOMMENDATION_ID);
        when(invocation.recommendationId()).thenReturn(RECOMMENDATION_ID);
        when(invocation.metadata()).thenReturn(metadata);
        when(invocation.outcome()).thenReturn(ModelInvocationOutcome.ACCEPTED);
        when(invocation.failureCategory()).thenReturn(Optional.empty());
        when(metadata.invocationId()).thenReturn(invocationId);
        when(metadata.completedAt()).thenReturn(completedAt);
        when(metadata.provider()).thenReturn(ModelInvocationProvider.GEMINI);
        when(metadata.model()).thenReturn("synthetic-model");
        when(metadata.promptVersion()).thenReturn("prompt-v1");
        when(metadata.outputSchemaVersion()).thenReturn("schema-v1");
        when(metadata.startedAt()).thenReturn(completedAt.minusSeconds(1));
        when(metadata.latencyMilliseconds()).thenReturn(100L);
        when(metadata.tokenUsage()).thenReturn(Optional.empty());
        when(metadata.inputSnapshot()).thenReturn("safe input");
        when(metadata.generatedResponse()).thenReturn("safe output");
        var decision = new BunchingRecommendationDecision(recommendation, context, Optional.of(invocation));
        when(recommendations.evaluate(RECOMMENDATION_ID, SourceClassification.SIMULATION, event, diagnosis))
                .thenReturn(Optional.of(decision));
        when(records.findByIdempotencyKey(RECOMMENDATION_ID)).thenReturn(Optional.empty(), Optional.of(existing));
        when(records.saveIfAbsent(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        when(existing.idempotencyKey()).thenReturn(RECOMMENDATION_ID);
        when(existing.requestHash()).thenReturn(REQUEST_HASH);

        var result = service(recommendations, records, modelInvocations, auditCapture).recommend(RECOMMENDATION_ID,
                REQUEST_HASH, REQUEST_ID, SourceClassification.SIMULATION, event, diagnosis);

        assertThat(result).contains(existing);
        var order = org.mockito.Mockito.inOrder(modelInvocations, auditCapture, records);
        order.verify(modelInvocations).save(invocation);
        var details = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        order.verify(auditCapture).recordRecommendationAttempt(org.mockito.ArgumentMatchers.eq(invocationId),
                org.mockito.ArgumentMatchers.eq(completedAt), org.mockito.ArgumentMatchers.eq(RECOMMENDATION_ID),
                org.mockito.ArgumentMatchers.eq(REQUEST_ID), org.mockito.ArgumentMatchers.eq("ACCEPTED"), details.capture());
        assertThat(details.getValue()).containsEntry("outcome", "ACCEPTED");
        order.verify(records).saveIfAbsent(org.mockito.ArgumentMatchers.any());
    }

    private DurableBunchingRecommendationService service(
            BunchingRecommendationService recommendations,
            RecommendationRecordPort records,
            ModelInvocationRecordPort modelInvocations,
            ApplicationAuditCapturePort auditCapture) {
        Clock clock = Clock.fixed(Instant.parse("2026-09-28T00:00:00Z"), ZoneOffset.UTC);
        var reviews = org.mockito.Mockito.mock(BunchingRecommendationRefreshService.class);
        org.mockito.Mockito.when(reviews.initialReview(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> call.getArgument(0));
        return new DurableBunchingRecommendationService(recommendations, records, modelInvocations, clock, mock(com.csd.occ.planning.domain.audit.ApplicationAuditEventRepository.class), auditCapture, reviews);
    }
}
