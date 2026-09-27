package com.csd.occ.planning.recommendationengine.domain;

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
        when(recommendation.recommendationId()).thenReturn(RECOMMENDATION_ID);
        when(audit.recommendationId()).thenReturn(RECOMMENDATION_ID);
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
        DurableBunchingRecommendationService service = service(recommendations, records, modelInvocations);

        var result = service.recommend(
                RECOMMENDATION_ID,
                REQUEST_HASH,
                REQUEST_ID,
                SourceClassification.SIMULATION,
                event,
                diagnosis);

        assertThat(result).isPresent();
        verify(modelInvocations).save(audit);
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
        DurableBunchingRecommendationService service = service(recommendations, records, modelInvocations);

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
    }

    private DurableBunchingRecommendationService service(
            BunchingRecommendationService recommendations,
            RecommendationRecordPort records,
            ModelInvocationRecordPort modelInvocations) {
        Clock clock = Clock.fixed(Instant.parse("2026-09-28T00:00:00Z"), ZoneOffset.UTC);
        return new DurableBunchingRecommendationService(recommendations, records, modelInvocations, clock);
    }
}
