package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaptainRouteServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-27T14:00:00Z");
    private static final UUID CAPTAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final DemoRunRepository runs = mock(DemoRunRepository.class);
    private final DemoVehicleAssignmentRepository assignments = mock(DemoVehicleAssignmentRepository.class);
    private final DemoFleetService fleet = mock(DemoFleetService.class);
    private final BusArrivalObservationRepository arrivals = mock(BusArrivalObservationRepository.class);
    private final ReferenceTopologyQueryService topology = mock(ReferenceTopologyQueryService.class);
    private final PollingConfigurationPort polling = mock(PollingConfigurationPort.class);
    private final CaptainRouteService service = new CaptainRouteService(runs, assignments, fleet, arrivals, topology,
            polling, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID runId = UUID.randomUUID();
    private DemoRun run;
    private DemoVehicleAssignment assignment;

    @BeforeEach
    void runningDemoWithAnAssignedBus() {
        run = new DemoRun(runId, NOW.minusSeconds(60), "multi-route-bunching-v2", 190);
        run.startReplay();
        assignment = new DemoVehicleAssignment(new DemoVehicleAssignmentId(runId, "demo-190-b"),
                "190", 1, "SBS5898U", "SBST", "DD", "DEMO-CAPTAIN", CAPTAIN);
        when(polling.staleAfterSeconds()).thenReturn(180);
        when(runs.findActive()).thenReturn(Optional.of(run));
        when(assignments.findByIdDemoRunIdAndCaptainUserId(runId, CAPTAIN)).thenReturn(Optional.of(assignment));
        when(topology.busRoute("190")).thenReturn(Optional.of(new ReferenceTopologyPort.BusRoute("190", List.of(
                new ReferenceTopologyPort.BusRouteOperator("SBST", "TRUNK", List.of(
                        new ReferenceTopologyPort.BusRouteDirection(2, List.of(stop(1, "99999", "0"))),
                        new ReferenceTopologyPort.BusRouteDirection(1, List.of(
                                stop(7, "44141", "4.0"), stop(8, "44251", "4.5"), stop(9, "44241", "5.5")))))))));
    }

    @Test
    void returnsTheAssignedBusRouteWithItsSimulationPosition() {
        var arrival = new BusArrivalObservation();
        arrival.setEstimatedArrivalAt(NOW.plusSeconds(60));
        when(fleet.progress(assignment)).thenReturn(Optional.of(new DemoFleetSnapshot.Progress("44251", 8,
                NOW.minusSeconds(5), 30, List.of(5), List.of(300))));
        when(arrivals.findFirstByDemoRunIdAndTripIdAndClassificationOrderByFetchedAtDescIdDesc(runId, "demo-190-b",
                "SIMULATION")).thenReturn(Optional.of(arrival));

        var route = service.current(CAPTAIN.toString()).orElseThrow();

        assertThat(route.registrationNumber()).isEqualTo("SBS5898U");
        assertThat(route.busId()).isEqualTo("demo-190-b");
        assertThat(route.stops()).extracting(stop -> stop.stop().stopCode()).containsExactly("44141", "44251", "44241");
        assertThat(route.position().lastStopSequence()).contains(7);
        assertThat(route.position().nextStopSequence()).contains(8);
        assertThat(route.stops().get(2).arrivalAt()).contains(NOW.plusSeconds(180));
    }

    @Test
    void anAssignedBusWithoutProgressStillShowsItsRoute() {
        when(fleet.progress(assignment)).thenReturn(Optional.empty());

        var route = service.current(CAPTAIN.toString()).orElseThrow();

        assertThat(route.position()).isEqualTo(CaptainRoute.Position.unavailable());
        assertThat(route.stops()).hasSize(3);
    }

    @Test
    void captainsWithoutACurrentAssignmentGetNothing() {
        assertThat(service.current(UUID.randomUUID().toString())).isEmpty();
        assertThat(service.current("not-a-uuid")).isEmpty();

        run.close(NOW);
        assertThat(service.current(CAPTAIN.toString())).isEmpty();

        when(runs.findActive()).thenReturn(Optional.empty());
        assertThat(service.current(CAPTAIN.toString())).isEmpty();
        verify(fleet, never()).progress(any());
    }

    @Test
    void aServiceDirectionMissingFromTheRouteDataGivesNothing() {
        when(topology.busRoute("190")).thenReturn(Optional.empty());

        assertThat(service.current(CAPTAIN.toString())).isEmpty();
    }

    private static ReferenceTopologyPort.BusRouteStop stop(int sequence, String code, String distanceKm) {
        return new ReferenceTopologyPort.BusRouteStop(code, "Road", "Stop " + code, BigDecimal.ONE, BigDecimal.ONE,
                sequence, new BigDecimal(distanceKm));
    }
}
