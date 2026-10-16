package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TrafficIncidentPollingServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-16T08:00:00Z");

    private final TrafficIncidentFeedPort provider = mock(TrafficIncidentFeedPort.class);
    private final PollRunRepository pollRuns = mock(PollRunRepository.class);
    private final RawObservationRepository rawObservations = mock(RawObservationRepository.class);
    private final TrafficIncidentObservationRepository incidents = mock(TrafficIncidentObservationRepository.class);
    private final TrafficIncidentPollingService polling = new TrafficIncidentPollingService(provider, pollRuns,
            rawObservations, incidents, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void successfulPollStoresObservationOnlyRawAndNormalizedRows() {
        when(provider.trafficIncidents()).thenReturn(new TrafficIncidentFeedPort.TrafficIncidentBatch(200,
                "[{\"Type\":\"Road Works\"}]", List.of(new TrafficIncidentFeedPort.TrafficIncident(
                        "a".repeat(64), "Road Works", new BigDecimal("1.3000000"),
                        new BigDecimal("103.8000000"), "Road works near test junction", null)), List.of()));

        polling.pollOnce();

        ArgumentCaptor<PollRun> savedRuns = ArgumentCaptor.forClass(PollRun.class);
        verify(pollRuns, org.mockito.Mockito.times(2)).save(savedRuns.capture());
        PollRun savedRun = savedRuns.getValue();
        assertThat(savedRun.getSource()).isEqualTo("TRAFFIC_DATAMALL");
        assertThat(savedRun.getScopeKey()).isEqualTo("traffic-incidents");
        assertThat(savedRun.getClassification()).isEqualTo("OBSERVATION_ONLY");
        assertThat(savedRun.getOutcome()).isEqualTo("SUCCESS");
        assertThat(savedRun.getRecordsReceived()).isEqualTo(1);
        assertThat(savedRun.getCompletedAt()).isEqualTo(NOW);

        ArgumentCaptor<RawObservation> raw = ArgumentCaptor.forClass(RawObservation.class);
        verify(rawObservations).save(raw.capture());
        assertThat(raw.getValue().getSource()).isEqualTo("TRAFFIC_DATAMALL");
        assertThat(raw.getValue().getPayload()).isEqualTo("[{\"Type\":\"Road Works\"}]");
        assertThat(raw.getValue().getClassification()).isEqualTo("OBSERVATION_ONLY");
        assertThat(raw.getValue().getPayloadChecksum()).matches("[0-9a-f]{64}");

        ArgumentCaptor<List<TrafficIncidentObservation>> normalized = ArgumentCaptor.forClass(List.class);
        verify(incidents).saveAll(normalized.capture());
        assertThat(normalized.getValue()).singleElement().satisfies(incident -> {
            assertThat(incident.classification()).isEqualTo("OBSERVATION_ONLY");
            assertThat(incident.sourceIncidentKey()).isEqualTo("a".repeat(64));
            assertThat(incident.message()).isEqualTo("Road works near test junction");
            assertThat(incident.observedAt()).isNull();
            assertThat(incident.fetchedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void incompletePollDoesNotCreateNormalizedRowsOrBecomeSuccessful() {
        when(provider.trafficIncidents()).thenReturn(new TrafficIncidentFeedPort.TrafficIncidentBatch(200,
                "[]", List.of(), List.of("invalid record")));

        polling.pollOnce();

        ArgumentCaptor<PollRun> savedRuns = ArgumentCaptor.forClass(PollRun.class);
        verify(pollRuns, org.mockito.Mockito.times(2)).save(savedRuns.capture());
        assertThat(savedRuns.getValue().getOutcome()).isEqualTo("PARTIAL");
        assertThat(savedRuns.getValue().getErrorCode()).isEqualTo("incomplete_feed");
        verify(rawObservations).save(any(RawObservation.class));
        verify(incidents).saveAll(List.of());
    }

    @Test
    void providerFailureIsRecordedAsFailedAndHasNoObservation() {
        when(provider.trafficIncidents()).thenThrow(new IllegalStateException("synthetic provider failure"));

        polling.pollOnce();

        ArgumentCaptor<PollRun> savedRuns = ArgumentCaptor.forClass(PollRun.class);
        verify(pollRuns, org.mockito.Mockito.times(2)).save(savedRuns.capture());
        assertThat(savedRuns.getValue().getOutcome()).isEqualTo("FAILED");
        assertThat(savedRuns.getValue().getErrorCode()).isEqualTo("poll_failed");
        verify(rawObservations, never()).save(any());
        verify(incidents, never()).saveAll(any());
    }
}
