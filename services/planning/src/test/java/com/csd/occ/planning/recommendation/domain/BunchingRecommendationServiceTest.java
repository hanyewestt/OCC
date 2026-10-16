package com.csd.occ.planning.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BunchingRecommendationServiceTest {

    private static final UUID RECOMMENDATION_ID = UUID.fromString("f07c921a-bd3b-4a97-8b17-a384ef1269a7");
    private static final UUID EVENT_ID = UUID.fromString("c5934f15-4a65-4e94-87ea-a5a64a7dd4c1");
    private static final UUID DIAGNOSIS_ID = UUID.fromString("f2b8d1b5-c24c-48bb-8a66-bff7be1e9e73");

    private final BunchingMitigationContextPort mitigationContext = mock(BunchingMitigationContextPort.class);
    private final BunchingRecommendationService service = new BunchingRecommendationService(
            mitigationContext,
            new BunchingRecommendationOrchestrator(
                    false,
                    Optional.empty(),
                    new BunchingMitigationCandidateValidator(),
                    new BunchingRecommendationPolicy()));

    @Test
    void observationOnlyRequestDoesNotReadSimulationMitigationFixture() {
        ConfirmedBunchingEvent event = new ConfirmedBunchingEvent(
                EVENT_ID,
                "190",
                1,
                "44251",
                BunchingEventStatus.CONFIRMED,
                600,
                List.of(276, 221),
                Instant.parse("2026-09-27T23:45:02Z"),
                List.of("arrival-observation-201", "arrival-observation-202"));
        BunchingRootCauseDiagnosis diagnosis = new BunchingRootCauseDiagnosis(
                DIAGNOSIS_ID,
                "Synthetic diagnosis for policy-boundary verification.",
                new BigDecimal("0.82"),
                List.of("incident-evidence-201"),
                List.of("Synthetic test input."));

        assertThat(service.evaluate(
                RECOMMENDATION_ID,
                SourceClassification.OBSERVATION_ONLY,
                event,
                diagnosis)).isEmpty();
        verify(mitigationContext, never()).findFor(event);
    }
}
