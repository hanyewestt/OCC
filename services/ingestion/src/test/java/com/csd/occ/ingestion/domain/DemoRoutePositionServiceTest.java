package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class DemoRoutePositionServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");

    @Test
    void returnsTheConfiguredFleetAtItsExactGapsAndMovesEveryBusAtOneCruisingSpeed() {
        UUID runId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        var configuration = new DemoRunConfiguration("972", 2, 3, java.time.Duration.ofSeconds(120),
                List.of(java.time.Duration.ofSeconds(120), java.time.Duration.ofSeconds(60)), "10002", DemoScenario.NORMAL);
        DemoRun run = new DemoRun(runId, NOW, "multi-route-bunching-v2", 190, configuration);
        run.startReplay(); run.advance();
        var runs = mock(DemoRunRepository.class);
        var assignments = mock(DemoVehicleAssignmentRepository.class);
        var topology = mock(ReferenceTopologyPort.class);
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(topology.activeVersion()).thenReturn(Optional.of(new ReferenceTopologyPort.ReferenceTopologyVersion(versionId, "checksum")));
        var route = new ReferenceTopologyPort.BusRoute("972", List.of(new ReferenceTopologyPort.BusRouteOperator("SMRT", "DD", List.of(
                new ReferenceTopologyPort.BusRouteDirection(2, List.of(
                        new ReferenceTopologyPort.BusRouteStop("10001", "Road", "Start", new BigDecimal("1.3000000"), new BigDecimal("103.8000000"), 1, BigDecimal.ZERO),
                        new ReferenceTopologyPort.BusRouteStop("10002", "Road", "Middle", new BigDecimal("1.3100000"), new BigDecimal("103.8100000"), 2, BigDecimal.ONE),
                        new ReferenceTopologyPort.BusRouteStop("10003", "Road", "End", new BigDecimal("1.3200000"), new BigDecimal("103.8200000"), 3, new BigDecimal("2"))))))));
        when(topology.busRoute(versionId, "972")).thenReturn(Optional.of(route));
        var fleet = List.of(assignment(runId, "demo-972-1", 1), assignment(runId, "demo-972-2", 2), assignment(runId, "demo-972-3", 3));
        when(assignments.findByIdDemoRunIdOrderByBusId(runId, PageRequest.of(0, 3))).thenReturn(new PageImpl<>(fleet));
        var service = new DemoRoutePositionService(runs, assignments, topology, Clock.fixed(NOW, ZoneOffset.UTC));

        RoutePositionSnapshot snapshot = service.positions(runId);

        assertThat(snapshot.serviceId()).isEqualTo("972");
        assertThat(snapshot.sourceObservedAt()).contains(NOW);
        assertThat(snapshot.directions()).singleElement().satisfies(direction -> {
            assertThat(direction.directionId()).isEqualTo(2);
            assertThat(direction.buses()).hasSize(3);
            assertThat(direction.buses()).extracting(RoutePositionBus::speedMps).containsOnly(8.33);
            assertThat(direction.buses().getFirst().distanceAlongRouteM()).isBetween(1_500.0, 1_600.0);
            assertThat(direction.buses().get(0).distanceAlongRouteM() - direction.buses().get(1).distanceAlongRouteM())
                    .isCloseTo(8.33 * 120, org.assertj.core.data.Offset.offset(0.01));
            assertThat(direction.buses().get(1).distanceAlongRouteM() - direction.buses().get(2).distanceAlongRouteM())
                    .isCloseTo(8.33 * 60, org.assertj.core.data.Offset.offset(0.01));
        });

        var later = new DemoRoutePositionService(runs, assignments, topology,
                Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC)).positions(runId);
        for (int index = 0; index < 3; index++) {
            assertThat(later.directions().getFirst().buses().get(index).distanceAlongRouteM()
                    - snapshot.directions().getFirst().buses().get(index).distanceAlongRouteM())
                    .isCloseTo(8.33 * 30, org.assertj.core.data.Offset.offset(0.01));
        }
    }

    private DemoVehicleAssignment assignment(UUID runId, String tripId, int number) {
        return new DemoVehicleAssignment(new DemoVehicleAssignmentId(runId, tripId), "972", 2,
                "DEMO-972-" + String.format("%02d", number), "DEMO-OPERATOR", "DD", "CAPTAIN-" + number);
    }
}
