package com.csd.occ.ingestion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.csd.occ.ingestion.domain.RoutePositionBus;
import com.csd.occ.ingestion.domain.RoutePositionDirection;
import com.csd.occ.ingestion.domain.RoutePositionService;
import com.csd.occ.ingestion.domain.RoutePositionSettings;
import com.csd.occ.ingestion.domain.RoutePositionSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RoutePositionsControllerTest {
    @Test
    void returnsOrderBasedEstimatesGroupedByDirection() {
        UUID pollCycleId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID linkageEvidenceId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        UUID positionEvidenceId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        RoutePositionSnapshot snapshot = new RoutePositionSnapshot("190", Instant.parse("2026-10-23T04:00:00Z"),
                Optional.of(pollCycleId), Optional.of(Instant.parse("2026-10-23T03:59:58Z")), 60, 120,
                List.of(new RoutePositionDirection("SMRT", 1, 12_000,
                List.of(new RoutePositionBus(1, 4_300, 70, 6.2,
                        List.of(linkageEvidenceId, positionEvidenceId), List.of(positionEvidenceId), 2)))));
        RoutePositionsController controller = new RoutePositionsController(new StubRoutePositionService(snapshot));

        var response = controller.routePositions("190");

        assertThat(response.serviceId()).isEqualTo("190");
        assertThat(response.computedAt()).isEqualTo(Instant.parse("2026-10-23T04:00:00Z"));
        assertThat(response.sourcePollCycleId()).isEqualTo(pollCycleId);
        assertThat(response.sourceObservedAt()).isEqualTo(Instant.parse("2026-10-23T03:59:58Z"));
        assertThat(response.refreshIntervalSeconds()).isEqualTo(60);
        assertThat(response.maxSourceAgeSeconds()).isEqualTo(120);
        assertThat(response.directions()).hasSize(1);
        assertThat(response.directions().getFirst().directionId()).isEqualTo(1);
        assertThat(response.directions().getFirst().buses()).singleElement().satisfies(bus -> {
            assertThat(bus.evidenceObservationIds()).containsExactly(linkageEvidenceId, positionEvidenceId);
            assertThat(bus.positionEvidenceObservationIds()).containsExactly(positionEvidenceId);
            assertThat(bus.supportingStopCount()).isEqualTo(2);
        });
    }

    @Test
    void returnsNotFoundWhenServiceHasNoRoute() {
        RoutePositionsController controller = new RoutePositionsController(new StubRoutePositionService(null));
        assertThatThrownBy(() -> controller.routePositions("999")).isInstanceOf(ResponseStatusException.class);
    }

    private static final class StubRoutePositionService extends RoutePositionService {
        private final RoutePositionSnapshot snapshot;

        private StubRoutePositionService(RoutePositionSnapshot snapshot) {
            super(null, new TestSettings(), Clock.systemUTC());
            this.snapshot = snapshot;
        }

        @Override
        public Optional<RoutePositionSnapshot> positions(String serviceId) {
            return Optional.ofNullable(snapshot);
        }
    }

    private static final class TestSettings implements RoutePositionSettings {
        @Override public Duration pollInterval() { return Duration.ofSeconds(60); }
        @Override public int maxPollAgeCycles() { return 2; }
        @Override public double clusterToleranceMeters() { return 150; }
        @Override public double speedCapMetersPerSecond() { return 16.7; }
        @Override public double uncertaintyMeters() { return 50; }
        @Override public double circularProjectionSpeedMetersPerSecond() { return 7; }
        @Override public int circularProjectionMinimumStopCount() { return 3; }
        @Override public double identityLinkDistanceMeters() { return 25; }
        @Override public double identityLinkProjectionSpeedMetersPerSecond() { return 7; }
        @Override public Duration identityEtaTolerance() { return Duration.ofSeconds(20); }
        @Override public boolean isIdentityLinkEnabled(String serviceId, int directionId) {
            return serviceId.equals("190");
        }
        @Override public boolean isCoordinatedRefreshBoundaryEnabled(String serviceId, int directionId) {
            return serviceId.equals("190") && directionId == 1;
        }
        @Override public double refreshBoundaryDistanceMeters() { return 250; }
        @Override public Duration refreshBoundaryWindow() { return Duration.ofSeconds(5); }
        @Override public Duration refreshBoundaryAllowedOverlap() { return Duration.ofMillis(250); }
        @Override public Duration refreshBoundaryProfileResidual() { return Duration.ofSeconds(75); }
        @Override public double refreshBoundaryProfileMinimumSecondsPerMeter() { return 0.02; }
        @Override public double refreshBoundaryProfileMaximumSecondsPerMeter() { return 2; }
        @Override public double circularPhaseLinkDistanceMeters() { return 250; }
        @Override public double circularPhaseMaximumGapMeters() { return 2_000; }
        @Override public Duration circularPhaseMaximumEtaError() { return Duration.ofSeconds(75); }
        @Override public boolean isCircularPhaseLinkEnabled(String serviceId, int directionId) {
            return serviceId.equals("972") && directionId == 1;
        }
    }
}
