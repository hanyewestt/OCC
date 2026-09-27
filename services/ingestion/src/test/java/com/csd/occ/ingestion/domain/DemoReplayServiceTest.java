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
        DemoRun run = new DemoRun(runId, NOW, "route190-bunching-v1", 190);
        run.startReplay();
        when(runs.findActive()).thenReturn(Optional.of(run));
        when(mode.enabled()).thenReturn(true);
        when(topology.activeVersion()).thenReturn(Optional.of(
                new ReferenceTopologyPort.ReferenceTopologyVersion(UUID.randomUUID(), "a".repeat(64))));
        List<List<BusArrivalObservation>> batches = new ArrayList<>();
        doAnswer(invocation -> {
            Iterable<BusArrivalObservation> batch = invocation.getArgument(0);
            List<BusArrivalObservation> copied = StreamSupport.stream(batch.spliterator(), false).toList();
            batches.add(copied);
            return copied;
        }).when(arrivals).saveAll(org.mockito.ArgumentMatchers.any());
        DemoReplayService service = new DemoReplayService(runs, assignments, pollRuns, rawObservations, arrivals,
                incidents, topology, Clock.fixed(NOW, ZoneOffset.UTC), mode,
                new DemoReplayConfiguration("route190-bunching-v1", 190, Duration.ofSeconds(90)));

        for (int step = 0; step < DemoReplayScenario.STEP_COUNT; step++) service.replayNext();

        assertEquals(3, batches.size());
        assertEquals(List.of(Duration.ofSeconds(300), Duration.ofSeconds(45), Duration.ofSeconds(45)),
                batches.stream().map(batch -> Duration.between(batch.get(0).getEstimatedArrivalAt(),
                        batch.get(1).getEstimatedArrivalAt())).toList());
        assertEquals(6, batches.stream().flatMap(List::stream)
                .filter(row -> "SIMULATION".equals(row.getClassification()) && runId.equals(row.getDemoRunId())).count());
        assertEquals("REPLAY_COMPLETE", run.status());
        assertEquals(DemoReplayScenario.STEP_COUNT, run.currentStep());
        verify(pollRuns, times(3)).save(org.mockito.ArgumentMatchers.any(PollRun.class));
        verify(incidents, times(2)).save(org.mockito.ArgumentMatchers.any(TrafficIncidentObservation.class));
    }
}
