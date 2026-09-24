package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BusArrivalServiceTest {
    private final PollTargetRepository targets = mock(PollTargetRepository.class);
    private final PollRunRepository runs = mock(PollRunRepository.class);
    private final RawObservationRepository observations = mock(RawObservationRepository.class);
    private final BusArrivalObservationRepository normalized = mock(BusArrivalObservationRepository.class);
    private final FeedStatusRepository feedStatuses = mock(FeedStatusRepository.class);
    private final BusArrivalPort arrivals = mock(BusArrivalPort.class);
    private final ReferenceTopologyPort topology = mock(ReferenceTopologyPort.class);
    private final PollingConfigurationPort configuration = mock(PollingConfigurationPort.class);
    private final Instant now = Instant.parse("2026-09-24T00:00:00Z");
    private final BusArrivalService service = new BusArrivalService(targets, runs, observations, normalized,
            feedStatuses, arrivals, topology, configuration, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void successfulPollPreservesObservationAndMinuteBucket() {
        when(targets.findAll()).thenReturn(List.of(target(true)));
        when(arrivals.arrivalsAt("44009")).thenReturn(snapshot());
        when(topology.activeVersion()).thenReturn(java.util.Optional.of(
                new ReferenceTopologyPort.ReferenceTopologyVersion(java.util.UUID.randomUUID(), "checksum")));
        when(configuration.staleAfterSeconds()).thenReturn(180);
        service.poll();
        ArgumentCaptor<RawObservation> captured = ArgumentCaptor.forClass(RawObservation.class);
        verify(observations).save(captured.capture());
        RawObservation observation = captured.getValue();
        assertThat(observation.getPayload()).isEqualTo("{}");
        assertThat(observation.getPayloadChecksum()).isEqualTo("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
        assertThat(observation.getFetchedAt()).isEqualTo(now);
        assertThat(observation.getClassification()).isEqualTo("OBSERVATION_ONLY");
        ArgumentCaptor<PollRun> capturedRun = ArgumentCaptor.forClass(PollRun.class);
        verify(runs, times(2)).save(capturedRun.capture());
        PollRun run = capturedRun.getValue();
        assertThat(run.getOutcome()).isEqualTo("SUCCESS");
        assertThat(run.getIdempotencyKey()).isEqualTo("DATAMALL-stop:44009-" + now.getEpochSecond() / 60);
        assertThat(run.getCompletedAt()).isEqualTo(now);
        assertThat(observation.getPollRunId()).isEqualTo(run.getId());
    }

    @Test
    void disabledTargetsDoNotCallProviderOrWriteRows() {
        when(targets.findAll()).thenReturn(List.of(target(false)));
        service.poll();
        verifyNoInteractions(arrivals, runs, observations, normalized, feedStatuses);
    }

    @Test
    void missingTopologySkipsAllProviderAndPersistenceWork() {
        when(targets.findAll()).thenReturn(List.of(target(true)));
        when(topology.activeVersion()).thenReturn(java.util.Optional.empty());
        service.poll();
        verifyNoInteractions(arrivals, runs, observations, normalized, feedStatuses);
    }

    @Test
    void providerFailureCompletesFailedRunWithoutObservation() {
        when(targets.findAll()).thenReturn(List.of(target(true)));
        when(topology.activeVersion()).thenReturn(java.util.Optional.of(
                new ReferenceTopologyPort.ReferenceTopologyVersion(java.util.UUID.randomUUID(), "checksum")));
        when(configuration.staleAfterSeconds()).thenReturn(180);
        when(arrivals.arrivalsAt("44009")).thenThrow(new IllegalStateException("synthetic failure"));
        service.poll();
        verifyNoInteractions(observations, normalized);
        ArgumentCaptor<PollRun> captured = ArgumentCaptor.forClass(PollRun.class);
        verify(runs, times(2)).save(captured.capture());
        assertThat(captured.getValue().getOutcome()).isEqualTo("FAILED");
        assertThat(captured.getValue().getCompletedAt()).isEqualTo(now);
    }

    private BusArrivalSnapshot snapshot() {
        return new BusArrivalSnapshot(200, "{}", List.of(), List.of());
    }

    private PollTarget target(boolean enabled) {
        PollTarget target = new PollTarget();
        target.setSource("DATAMALL");
        target.setScopeKey("stop:44009");
        target.setStopCode("44009");
        target.setEnabled(enabled);
        return target;
    }
}
