package com.csd.occ.planning.recommendationengine.infrastructure.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.csd.occ.planning.recommendationengine.domain.BunchingEventStatus;
import com.csd.occ.planning.recommendationengine.domain.BunchingRecommendationPolicy;
import com.csd.occ.planning.recommendationengine.domain.BunchingRecommendationService;
import com.csd.occ.planning.recommendationengine.domain.BunchingRootCauseDiagnosis;
import com.csd.occ.planning.recommendationengine.domain.ConfirmedBunchingEvent;
import com.csd.occ.planning.recommendationengine.domain.MitigationType;
import com.csd.occ.planning.recommendationengine.domain.SourceClassification;
import com.csd.occ.planning.recommendationengine.infrastructure.config.RecommendationFixtureProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

class ClasspathBunchingMitigationContextAdapterTest {

    @Test
    void knownFixtureProducesDeterministicDemoRecommendation() {
        ClasspathBunchingMitigationContextAdapter adapter =
                new ClasspathBunchingMitigationContextAdapter(
                        new ObjectMapper(),
                        new RecommendationFixtureProperties(
                                new ClassPathResource("fixtures/bunching-mitigation-context.json")));
        BunchingRecommendationService service =
                new BunchingRecommendationService(adapter, new BunchingRecommendationPolicy());

        var recommendation = service.recommend(
                UUID.fromString("f07c921a-bd3b-4a97-8b17-a384ef1269a7"),
                SourceClassification.SIMULATION,
                confirmedEvent(),
                diagnosis());

        assertThat(recommendation).hasValueSatisfying(value -> {
            assertThat(value.mitigationType()).isEqualTo(MitigationType.HOLD);
            assertThat(value.adjustmentSeconds()).isEqualTo(90);
            assertThat(value.expectedImpact().headwayAfterSeconds()).isEqualTo(420);
            assertThat(value.expectedImpact().queueAfterPassengers()).isEqualTo(24);
            assertThat(value.expectedImpact().passengerMinutesAfter())
                    .isEqualByComparingTo("128");
        });
    }

    @Test
    void unknownRouteHasNoFixtureContext() {
        ClasspathBunchingMitigationContextAdapter adapter =
                new ClasspathBunchingMitigationContextAdapter(
                        new ObjectMapper(),
                        new RecommendationFixtureProperties(
                                new ClassPathResource("fixtures/bunching-mitigation-context.json")));
        ConfirmedBunchingEvent event = new ConfirmedBunchingEvent(
                UUID.fromString("62e7784e-7d37-4d79-b460-4bcb8058cde1"),
                "999",
                1,
                "00000",
                BunchingEventStatus.CONFIRMED,
                600,
                List.of(180, 210),
                Instant.parse("2026-09-22T10:02:00Z"),
                List.of("arrival-observation-201", "arrival-observation-202"));

        assertThat(adapter.findFor(event)).isEmpty();
    }

    private ConfirmedBunchingEvent confirmedEvent() {
        return new ConfirmedBunchingEvent(
                UUID.fromString("c5934f15-4a65-4e94-87ea-a5a64a7dd4c1"),
                "190",
                1,
                "44531",
                BunchingEventStatus.CONFIRMED,
                600,
                List.of(180, 210),
                Instant.parse("2026-09-22T10:02:00Z"),
                List.of("arrival-observation-101", "arrival-observation-102"));
    }

    private BunchingRootCauseDiagnosis diagnosis() {
        return new BunchingRootCauseDiagnosis(
                UUID.fromString("f2b8d1b5-c24c-48bb-8a66-bff7be1e9e73"),
                "Downstream boarding delay caused the following bus to close the gap.",
                new BigDecimal("0.82"),
                List.of("diagnosis-evidence-001"),
                List.of("Vehicle location is unavailable."));
    }
}
