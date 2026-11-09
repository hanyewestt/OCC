package com.csd.occ.ingestion.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class DemoReplayServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-26T05:00:00Z");

    @Test
    void snapshotsOneAuthenticatedCaptainAndStableUniqueBusIdentitiesOnEveryRun() {
        var runs = mock(DemoRunRepository.class);
        var assignments = mock(DemoVehicleAssignmentRepository.class);
        var topology = mock(ReferenceTopologyPort.class);
        when(topology.activeVersion()).thenReturn(Optional.of(
                new ReferenceTopologyPort.ReferenceTopologyVersion(UUID.randomUUID(), "a".repeat(64))));
        var route = new ReferenceTopologyPort.BusRoute("190", List.of(
                new ReferenceTopologyPort.BusRouteOperator("SBST", "DD", List.of(
                        new ReferenceTopologyPort.BusRouteDirection(1, List.of(
                                new ReferenceTopologyPort.BusRouteStop("44251", "A", "Stop A", null, null, 10, null)))))));
        when(topology.busRoute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("190")))
                .thenReturn(Optional.of(route));
        when(runs.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        UUID captain = UUID.randomUUID();
        var service = new DemoReplayService(runs, assignments, mock(PollRunRepository.class),
                mock(RawObservationRepository.class), mock(BusArrivalObservationRepository.class),
                mock(TrafficIncidentObservationRepository.class), topology, Clock.fixed(NOW, ZoneOffset.UTC),
                mock(DemoModeService.class), new DemoReplayConfiguration("multi-route-bunching-v2", 190,
                Duration.ofSeconds(90), Optional.of(captain)), mock(DemoHazardReplayService.class));
        var first = service.start();
        var second = service.start();
        org.assertj.core.api.Assertions.assertThat(first.id()).isNotEqualTo(second.id());
        @SuppressWarnings("unchecked")
        var capture = org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(assignments, times(2)).saveAll(capture.capture());
        for (var batch : capture.getAllValues()) {
            @SuppressWarnings("unchecked")
            List<DemoVehicleAssignment> buses = StreamSupport.stream(batch.spliterator(), false).toList();
            org.assertj.core.api.Assertions.assertThat(buses).extracting(DemoVehicleAssignment::busId)
                    .containsExactly("demo-190-1", "demo-190-2", "demo-190-3");
            org.assertj.core.api.Assertions.assertThat(buses.stream().filter(bus -> bus.captainUserId().isPresent()).toList())
                    .hasSize(1).first().satisfies(bus -> {
                        org.assertj.core.api.Assertions.assertThat(bus.busId()).isEqualTo("demo-190-1");
                        org.assertj.core.api.Assertions.assertThat(bus.captainUserId()).contains(captain);
                    });
        }
    }

    @Test
    void createsExactlyTheRequestedSingleRouteFleet() {
        var runs = mock(DemoRunRepository.class);
        var assignments = mock(DemoVehicleAssignmentRepository.class);
        var topology = mock(ReferenceTopologyPort.class);
        UUID versionId = UUID.randomUUID();
        when(topology.activeVersion()).thenReturn(Optional.of(new ReferenceTopologyPort.ReferenceTopologyVersion(versionId, "a".repeat(64))));
        var route = new ReferenceTopologyPort.BusRoute("972", List.of(new ReferenceTopologyPort.BusRouteOperator("SMRT", "DD", List.of(
                new ReferenceTopologyPort.BusRouteDirection(2, List.of(
                        new ReferenceTopologyPort.BusRouteStop("44251", "A", "Stop A", null, null, 10, null)))))));
        when(topology.busRoute(versionId, "972")).thenReturn(Optional.of(route));
        when(runs.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new DemoReplayService(runs, assignments, mock(PollRunRepository.class), mock(RawObservationRepository.class),
                mock(BusArrivalObservationRepository.class), mock(TrafficIncidentObservationRepository.class), topology,
                Clock.fixed(NOW, ZoneOffset.UTC), mock(DemoModeService.class),
                new DemoReplayConfiguration("multi-route-bunching-v2", 190, Duration.ofSeconds(90)), mock(DemoHazardReplayService.class));

        var captains = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        service.start(new DemoRunConfiguration("972", 2, 3, Duration.ofSeconds(120),
                List.of(Duration.ofSeconds(120), Duration.ofSeconds(120)), "44251", DemoScenario.NORMAL, captains));

        @SuppressWarnings("unchecked") var capture = org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(assignments).saveAll(capture.capture());
        @SuppressWarnings("unchecked") List<DemoVehicleAssignment> buses = StreamSupport.stream(capture.getValue().spliterator(), false).toList();
        org.assertj.core.api.Assertions.assertThat(buses).extracting(DemoVehicleAssignment::busId)
                .containsExactly("demo-972-1", "demo-972-2", "demo-972-3");
        org.assertj.core.api.Assertions.assertThat(buses).allSatisfy(bus -> {
            org.assertj.core.api.Assertions.assertThat(bus.serviceId()).isEqualTo("972");
            org.assertj.core.api.Assertions.assertThat(bus.directionId()).isEqualTo(2);
        });
        org.assertj.core.api.Assertions.assertThat(buses).extracting(bus -> bus.captainUserId().orElseThrow())
                .containsExactlyElementsOf(captains);
    }

    @Test
    void rejectsNormalOperationThatWouldAlreadyBeClassifiedAsBunching() {
        var service = new DemoReplayService(mock(DemoRunRepository.class), mock(DemoVehicleAssignmentRepository.class),
                mock(PollRunRepository.class), mock(RawObservationRepository.class), mock(BusArrivalObservationRepository.class),
                mock(TrafficIncidentObservationRepository.class), mock(ReferenceTopologyPort.class), Clock.fixed(NOW, ZoneOffset.UTC),
                mock(DemoModeService.class), new DemoReplayConfiguration("multi-route-bunching-v2", 190, Duration.ofSeconds(90)),
                mock(DemoHazardReplayService.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.start(
                new DemoRunConfiguration("190", 1, 3, Duration.ofSeconds(90), DemoScenario.NORMAL)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("above the bunching threshold");
    }

    @Test
    void rejectsAConfiguredNormalFleetWhenAnyIndividualGapWouldBunch() {
        var service = new DemoReplayService(mock(DemoRunRepository.class), mock(DemoVehicleAssignmentRepository.class),
                mock(PollRunRepository.class), mock(RawObservationRepository.class), mock(BusArrivalObservationRepository.class),
                mock(TrafficIncidentObservationRepository.class), mock(ReferenceTopologyPort.class), Clock.fixed(NOW, ZoneOffset.UTC),
                mock(DemoModeService.class), new DemoReplayConfiguration("multi-route-bunching-v2", 190, Duration.ofSeconds(90)),
                mock(DemoHazardReplayService.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.start(new DemoRunConfiguration("190", 1, 3,
                Duration.ofSeconds(120), List.of(Duration.ofSeconds(120), Duration.ofSeconds(60)), DemoScenario.NORMAL)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("above the bunching threshold");
    }

    @Test
    void replayEmitsThreeScopedSnapshotsWithNormalThenBunchedHeadways() {
        DemoRunRepository runs = mock(DemoRunRepository.class);
        DemoVehicleAssignmentRepository assignments = mock(DemoVehicleAssignmentRepository.class);
        PollRunRepository pollRuns = mock(PollRunRepository.class);
        RawObservationRepository rawObservations = mock(RawObservationRepository.class);
        BusArrivalObservationRepository arrivals = mock(BusArrivalObservationRepository.class);
        TrafficIncidentObservationRepository incidents = mock(TrafficIncidentObservationRepository.class);
        ReferenceTopologyPort topology = mock(ReferenceTopologyPort.class);
        DemoModeService mode = mock(DemoModeService.class);
        UUID runId = UUID.randomUUID();
        DemoRun run = new DemoRun(runId, NOW, "route190-bunching-v1", 190, new DemoRunConfiguration("190", 1, 3,
                Duration.ofSeconds(90), List.of(Duration.ofSeconds(90), Duration.ofSeconds(90)), "12345", DemoScenario.BUNCHING));
        run.startReplay();
        when(runs.findActive()).thenReturn(Optional.of(run));
        when(mode.enabled()).thenReturn(true);
        var referenceVersion = new ReferenceTopologyPort.ReferenceTopologyVersion(UUID.randomUUID(), "a".repeat(64));
        when(topology.activeVersion()).thenReturn(Optional.of(referenceVersion));
        var repeatedStopRoute = new ReferenceTopologyPort.BusRoute("190", List.of(
                new ReferenceTopologyPort.BusRouteOperator("SBST", "DD", List.of(
                        new ReferenceTopologyPort.BusRouteDirection(1, List.of(
                                new ReferenceTopologyPort.BusRouteStop("44251", "A", "Stop A", null, null, 10, null),
                                new ReferenceTopologyPort.BusRouteStop("12345", "B", "Stop B", null, null, 20, null),
                                new ReferenceTopologyPort.BusRouteStop("44251", "A", "Stop A", null, null, 40, null)))))));
        when(topology.busRoute(referenceVersion.id(), "190")).thenReturn(Optional.of(repeatedStopRoute));
        List<List<BusArrivalObservation>> batches = new ArrayList<>();
        doAnswer(invocation -> {
            Iterable<BusArrivalObservation> batch = invocation.getArgument(0);
            List<BusArrivalObservation> copied = StreamSupport.stream(batch.spliterator(), false).toList();
            batches.add(copied);
            return copied;
        }).when(arrivals).saveAll(org.mockito.ArgumentMatchers.any());
        DemoReplayService service = new DemoReplayService(runs, assignments, pollRuns, rawObservations, arrivals,
                incidents, topology, Clock.fixed(NOW, ZoneOffset.UTC), mode,
                new DemoReplayConfiguration("route190-bunching-v1", 190, Duration.ofSeconds(90)), mock(DemoHazardReplayService.class));

        for (int step = 0; step < DemoReplayScenario.STEP_COUNT; step++) service.replayNext();

        assertEquals(3, batches.size());
        assertEquals(List.of(Duration.ofSeconds(90), Duration.ofSeconds(45), Duration.ofSeconds(45)),
                batches.stream().map(batch -> Duration.between(batch.get(0).getEstimatedArrivalAt(),
                        batch.get(1).getEstimatedArrivalAt())).toList());
        assertEquals(9, batches.stream().flatMap(List::stream)
                .filter(row -> "SIMULATION".equals(row.getClassification()) && runId.equals(row.getDemoRunId())).count());
        assertEquals(List.of("190", "190", "190"),
                batches.getFirst().stream().map(BusArrivalObservation::getServiceId).toList());
        assertEquals(List.of(20, 20), batches.getFirst().subList(0, 2).stream()
                .map(BusArrivalObservation::getRouteStopSequence).toList());
        org.assertj.core.api.Assertions.assertThat(batches.getFirst()).extracting(BusArrivalObservation::getStopCode)
                .containsOnly("12345");
        assertEquals(List.of(Duration.ofSeconds(45), Duration.ofSeconds(45)),
                List.of(Duration.between(batches.get(1).get(0).getEstimatedArrivalAt(),
                                batches.get(1).get(1).getEstimatedArrivalAt()),
                        Duration.between(batches.get(1).get(1).getEstimatedArrivalAt(),
                                batches.get(1).get(2).getEstimatedArrivalAt())));
        assertEquals("REPLAY_COMPLETE", run.status());
        assertEquals(DemoReplayScenario.STEP_COUNT, run.currentStep());
        verify(pollRuns, times(3)).save(org.mockito.ArgumentMatchers.any(PollRun.class));
        verify(incidents, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any(TrafficIncidentObservation.class));
    }
}
