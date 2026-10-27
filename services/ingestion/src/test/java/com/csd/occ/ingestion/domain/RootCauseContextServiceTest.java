package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RootCauseContextServiceTest {
    private final Instant now = Instant.parse("2026-10-24T00:00:00Z");
    private final BusArrivalObservationRepository arrivals = mock(BusArrivalObservationRepository.class);
    private final TrafficIncidentObservationRepository incidents = mock(TrafficIncidentObservationRepository.class);
    private final PollingConfigurationPort settings = mock(PollingConfigurationPort.class);
    private final RootCauseContextService service = new RootCauseContextService(mock(DemoRunRepository.class),
            arrivals, incidents, settings, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void liveContextRetainsExactObservationIdsAndDoesNotReadDemoIncidents() {
        when(settings.retention()).thenReturn(Duration.ofHours(12));
        var first = arrival("OBSERVATION_ONLY", null);
        var second = arrival("OBSERVATION_ONLY", null);
        var ids = List.of(first.getId(), second.getId());
        when(arrivals.findAllById(ids)).thenReturn(List.of(first, second));
        when(incidents.findByClassificationAndDemoRunIdIsNullAndFetchedAtBetweenOrderByFetchedAtDesc(any(), any(), any(), any()))
                .thenReturn(List.of());
        var context = service.findLive(now, "190", "44009", 1, ids);
        assertThat(context.classification()).isEqualTo("OBSERVATION_ONLY");
        assertThat(context.demoRunId()).isNull();
        assertThat(context.arrivals()).extracting(RootCauseContextSnapshot.RootCauseArrival::observationId)
                .containsExactlyElementsOf(ids);
        verify(incidents, never()).findByDemoRunIdOrderByFetchedAtDesc(any());
    }

    @Test
    void liveContextRejectsAMixedSimulationObservation() {
        when(settings.retention()).thenReturn(Duration.ofHours(12));
        var first = arrival("OBSERVATION_ONLY", null);
        var second = arrival("SIMULATION", UUID.randomUUID());
        var ids = List.of(first.getId(), second.getId());
        when(arrivals.findAllById(ids)).thenReturn(List.of(first, second));
        assertThatThrownBy(() -> service.findLive(now, "190", "44009", 1, ids))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private BusArrivalObservation arrival(String classification, UUID runId) {
        var row = mock(BusArrivalObservation.class);
        when(row.getId()).thenReturn(UUID.randomUUID());
        when(row.getClassification()).thenReturn(classification);
        when(row.getDemoRunId()).thenReturn(runId);
        when(row.getServiceId()).thenReturn("190");
        when(row.getStopCode()).thenReturn("44009");
        when(row.getDirectionId()).thenReturn(1);
        when(row.getFetchedAt()).thenReturn(now.minusSeconds(5));
        return row;
    }
}
