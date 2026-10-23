package com.csd.occ.ingestion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.csd.occ.ingestion.domain.RoutePositionBus;
import com.csd.occ.ingestion.domain.RoutePositionDirection;
import com.csd.occ.ingestion.domain.RoutePositionService;
import com.csd.occ.ingestion.domain.RoutePositionSettings;
import com.csd.occ.ingestion.domain.RoutePositionSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.time.Duration;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RoutePositionsControllerTest {
    @Test
    void returnsOrderBasedEstimatesGroupedByDirection() {
        RoutePositionSnapshot snapshot = new RoutePositionSnapshot("190", Instant.parse("2026-10-23T04:00:00Z"),
                60, List.of(new RoutePositionDirection("SMRT", 1, 12_000,
                List.of(new RoutePositionBus(1, 4_300, 70, 6.2)))));
        RoutePositionsController controller = new RoutePositionsController(new StubRoutePositionService(snapshot));

        var response = controller.routePositions("190");

        assertThat(response.serviceId()).isEqualTo("190");
        assertThat(response.refreshIntervalSeconds()).isEqualTo(60);
        assertThat(response.directions()).hasSize(1);
        assertThat(response.directions().getFirst().directionId()).isEqualTo(1);
        assertThat(response.directions().getFirst().buses()).containsExactly(
                new com.csd.occ.ingestion.api.dto.RoutePositionBusResponse(1, 4_300, 70, 6.2));
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
    }
}
