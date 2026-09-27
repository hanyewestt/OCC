package com.csd.occ.ingestion.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DemoRunServiceTest {
    @Mock private DemoReplayService replay;
    @Mock private DemoRunRepository runs;
    @Mock private DemoModeService mode;
    @Mock private BunchingControlPort bunching;
    @Mock private BusArrivalObservationRepository arrivals;
    @Mock private ReferenceTopologyPort topology;

    private DemoRunService service;

    @BeforeEach
    void setUp() {
        service = new DemoRunService(replay, runs, mode, bunching, arrivals, topology,
                Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void enablingRuntimeModeStartsReplayWhenNoRunIsActive() {
        UUID runId = UUID.randomUUID();
        DemoRun run = new DemoRun(runId, Instant.parse("2026-09-26T00:00:00Z"), "route190-bunching-v1", 190);
        when(runs.findActive()).thenReturn(Optional.empty());
        when(replay.start()).thenReturn(run);
        when(bunching.alerts(runId)).thenReturn(List.of());
        when(mode.enabled()).thenReturn(true);

        assertTrue(service.setMode(true));

        verify(bunching).selectDemo(runId);
        verify(mode).setEnabled(true);
        verify(replay).activate(runId);
    }

    @Test
    void disablingRuntimeModeSelectsLiveAndClosesActiveReplay() {
        UUID runId = UUID.randomUUID();
        DemoRun run = new DemoRun(runId, Instant.parse("2026-09-26T00:00:00Z"), "route190-bunching-v1", 190);
        when(runs.findActive()).thenReturn(Optional.of(run));
        when(mode.enabled()).thenReturn(false);

        assertFalse(service.setMode(false));

        verify(bunching).selectLive();
        verify(mode).setEnabled(false);
        verify(replay).close(runId, Instant.parse("2026-09-26T00:00:00Z"));
        verify(replay, never()).start();
    }

    @Test
    void duplicateStartDoesNotCreateOrSelectAnotherRun() {
        UUID runId = UUID.randomUUID();
        DemoRun active = new DemoRun(runId, Instant.parse("2026-09-26T00:00:00Z"), "route190-bunching-v1", 190);
        when(runs.findActive()).thenReturn(Optional.of(active));

        assertThrows(DemoRunAlreadyActiveException.class, service::start);

        verify(replay, never()).start();
        verify(bunching, never()).selectDemo(runId);
    }

    @Test
    void unavailableBunchingControlRollsBackStartToLiveAndFailsTheRun() {
        UUID runId = UUID.randomUUID();
        DemoRun run = new DemoRun(runId, Instant.parse("2026-09-26T00:00:00Z"), "route190-bunching-v1", 190);
        when(runs.findActive()).thenReturn(Optional.empty());
        when(replay.start()).thenReturn(run);
        org.mockito.Mockito.doThrow(new IllegalStateException("control unavailable"))
                .when(bunching).selectDemo(runId);

        assertThrows(DemoBunchingUnavailableException.class, service::start);

        verify(bunching).selectLive();
        verify(replay).fail(runId, "Bunching control unavailable");
        verify(mode).setEnabled(false);
        verify(replay, never()).activate(runId);
    }

    @Test
    void failedLiveSelectionLeavesTheActiveDemoModeUntouchedForRetry() {
        UUID runId = UUID.randomUUID();
        DemoRun run = new DemoRun(runId, Instant.parse("2026-09-26T00:00:00Z"), "route190-bunching-v1", 190);
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(runs.findActive()).thenReturn(Optional.of(run));
        org.mockito.Mockito.doThrow(new IllegalStateException("control unavailable"))
                .when(bunching).selectLive();

        assertThrows(DemoBunchingUnavailableException.class, () -> service.close(runId));

        verify(mode, never()).setEnabled(false);
        verify(replay, never()).close(runId, Instant.parse("2026-09-26T00:00:00Z"));
    }

    @Test
    void runStatusKeepsDetectedAlertAndEnrichesBothVehicles() {
        UUID runId = UUID.randomUUID();
        UUID leadingId = UUID.randomUUID();
        UUID followingId = UUID.randomUUID();
        Instant detectedAt = Instant.parse("2026-09-26T00:00:00Z");
        DemoRun run = new DemoRun(runId, detectedAt, "route190-bunching-v1", 190);
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(bunching.alerts(runId)).thenReturn(List.of(new DemoBunchingAlert("OPENED", "190", "44251", 1,
                detectedAt, "SIMULATION", leadingId, followingId)));
        when(arrivals.findSimulationArrivalDetails(runId, leadingId)).thenReturn(Optional.of(
                new DemoSimulationArrivalDetails("DEMO-BUS-190-01", "DEMO-OPERATOR", "DD", "DEMO-CAPTAIN-190-01",
                        detectedAt.plusSeconds(120))));
        when(arrivals.findSimulationArrivalDetails(runId, followingId)).thenReturn(Optional.of(
                new DemoSimulationArrivalDetails("DEMO-BUS-190-02", "DEMO-OPERATOR", "DD", "DEMO-CAPTAIN-190-02",
                        detectedAt.plusSeconds(165))));
        var version = new ReferenceTopologyPort.ReferenceTopologyVersion(UUID.randomUUID(), "checksum");
        when(topology.activeVersion()).thenReturn(Optional.of(version));
        when(topology.busStop(version.id(), "44251")).thenReturn(Optional.of(
                new ReferenceTopologyPort.BusStop("44251", "Teck Whye Ave", "Teck Whye Secondary School", null, null)));

        DemoAlertDetails alert = service.status(runId).alerts().getFirst();

        assertEquals("OPENED", alert.action());
        assertEquals("DEMO-BUS-190-01", alert.leadingVehicle().registrationNumber());
        assertEquals("DEMO-CAPTAIN-190-02", alert.followingVehicle().captainCode());
        assertEquals("Teck Whye Secondary School", alert.stopName());
        assertEquals(45L, alert.headwaySeconds());
    }
}
