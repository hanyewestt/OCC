package com.csd.occ.planning.recommendation.infrastructure.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.csd.occ.planning.recommendation.domain.BunchingEventStatus;
import com.csd.occ.planning.recommendation.domain.BunchingMitigationCandidateValidator;
import com.csd.occ.planning.recommendation.domain.BunchingRecommendationOrchestrator;
import com.csd.occ.planning.recommendation.domain.BunchingRecommendationPolicy;
import com.csd.occ.planning.recommendation.domain.BunchingRecommendationService;
import com.csd.occ.planning.recommendation.domain.BunchingRootCauseDiagnosis;
import com.csd.occ.planning.recommendation.domain.ConfirmedBunchingEvent;
import com.csd.occ.planning.recommendation.domain.MitigationType;
import com.csd.occ.planning.recommendation.domain.SourceClassification;
import com.csd.occ.planning.recommendation.infrastructure.config.RecommendationFixtureProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
                new BunchingRecommendationService(
                        adapter,
                        new BunchingRecommendationOrchestrator(
                                false,
                                Optional.empty(),
                                new BunchingMitigationCandidateValidator(),
                                new BunchingRecommendationPolicy()));

        var recommendation = service.recommend(
                UUID.fromString("f07c921a-bd3b-4a97-8b17-a384ef1269a7"),
                SourceClassification.SIMULATION,
                confirmedEvent(),
                diagnosis());

        assertThat(recommendation).hasValueSatisfying(value -> {
            assertThat(value.mitigationType()).isEqualTo(MitigationType.HOLD);
            assertThat(value.adjustmentSeconds()).isEqualTo(120);
            assertThat(value.targetBusReference()).isEqualTo("SBS5898U");
            assertThat(value.controlStopCode()).isEqualTo("44251");
            assertThat(value.expectedImpact().headwayAfterSeconds()).isEqualTo(341);
            assertThat(value.expectedImpact().queueAfterPassengers()).isEqualTo(24);
            assertThat(value.expectedImpact().passengerMinutesAfter())
                    .isEqualByComparingTo("128");
        });
        assertThat(adapter.findFor(confirmedEvent()).orElseThrow().context().affectedBusReferences())
                .containsExactly("SBS8027Y", "SBS5898U");
    }

    @Test
    void threeBusFixtureCarriesAllAffectedBusReferences() {
        ClasspathBunchingMitigationContextAdapter adapter = new ClasspathBunchingMitigationContextAdapter(
                new ObjectMapper(), new RecommendationFixtureProperties(
                        new ClassPathResource("fixtures/bunching-mitigation-context.json")));
        ConfirmedBunchingEvent event = new ConfirmedBunchingEvent(UUID.randomUUID(), "972", 1, "44251",
                BunchingEventStatus.CONFIRMED, 600, List.of(45, 45), Instant.parse("2026-09-27T23:45:02Z"),
                List.of("route972-arrival-a", "route972-arrival-b", "route972-arrival-c"));

        assertThat(adapter.findFor(event).orElseThrow().context().affectedBusReferences())
                .containsExactly("SMB3123A", "SMB4567B", "SMB5890C");
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
                List.of(276, 221),
                Instant.parse("2026-09-22T10:02:00Z"),
                List.of("arrival-observation-201", "arrival-observation-202"));

        assertThat(adapter.findFor(event)).isEmpty();
    }

    private ConfirmedBunchingEvent confirmedEvent() {
        return new ConfirmedBunchingEvent(
                UUID.fromString("c5934f15-4a65-4e94-87ea-a5a64a7dd4c1"),
                "190",
                1,
                "44251",
                BunchingEventStatus.CONFIRMED,
                600,
                List.of(276, 221),
                Instant.parse("2026-09-27T23:45:02Z"),
                List.of("route190-arrival-20260928T074402+0800",
                        "route190-arrival-20260928T074502+0800"));
    }

    private BunchingRootCauseDiagnosis diagnosis() {
        return new BunchingRootCauseDiagnosis(
                UUID.fromString("f2b8d1b5-c24c-48bb-8a66-bff7be1e9e73"),
                "A vehicle breakdown delayed leader SBS8027Y, allowing follower SBS5898U to close the gap.",
                new BigDecimal("0.82"),
                List.of("route190-breakdown-delay-sbs8027y"),
                List.of("The diagnosis is supplied by the shared deterministic simulation fixture."));
    }
}
