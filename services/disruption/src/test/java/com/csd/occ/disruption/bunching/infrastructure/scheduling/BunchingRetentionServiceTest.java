package com.csd.occ.disruption.bunching.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.csd.occ.disruption.bunching.domain.AlertEventRepository;
import com.csd.occ.disruption.bunching.domain.AlertProcessingStateRepository;
import com.csd.occ.disruption.bunching.domain.ConsumerState;
import com.csd.occ.disruption.bunching.domain.ConsumerStateRepository;
import com.csd.occ.disruption.bunching.domain.DiagnosisOutboxRepository;
import com.csd.occ.disruption.bunching.domain.SourceProcessingStateRepository;
import com.csd.occ.disruption.bunching.domain.SourceRetentionPort;
import com.csd.occ.disruption.bunching.infrastructure.config.BunchingRetentionProperties;
import com.csd.occ.disruption.hazard.domain.HazardStateRepository;
import com.csd.occ.disruption.infrastructure.config.HazardProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BunchingRetentionServiceTest {
    @Test
    void returnsRowsDeletedByTheBulkRetentionQueries() {
        var sources = mock(SourceProcessingStateRepository.class);
        var events = mock(AlertEventRepository.class);
        var outbox = mock(DiagnosisOutboxRepository.class);
        var consumers = mock(ConsumerStateRepository.class);
        var alerts = mock(AlertProcessingStateRepository.class);
        var hazardStates = mock(HazardStateRepository.class);
        var hazardFeedHealth = mock(com.csd.occ.disruption.hazard.domain.HazardFeedHealthRepository.class);
        when(outbox.deleteDeliveredOrTerminalBefore(any())).thenReturn(3);
        when(events.deleteResolvedBefore(any())).thenReturn(2);
        when(consumers.findWithLockById(1)).thenReturn(Optional.of(mock(ConsumerState.class)));
        when(events.findProtectedDemoRunIds()).thenReturn(List.of());
        when(alerts.findExpiredDemoRunIds(any())).thenReturn(List.of());
        when(sources.findRetentionCandidates(any())).thenReturn(List.of());
        when(hazardStates.deleteClearedBefore(any())).thenReturn(4);
        when(hazardFeedHealth.deleteExpiredSimulationHealth(any())).thenReturn(1);
        var service = new BunchingRetentionService(sources, events, outbox,
                new BunchingRetentionProperties(Duration.ofDays(7), Duration.ofHours(1), Duration.ZERO),
                Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC),
                mock(SourceRetentionPort.class), consumers, alerts, hazardStates, hazardFeedHealth,
                new HazardProperties(true, Duration.ofSeconds(10), Duration.ofMinutes(2), 2,
                        Duration.ofMinutes(1), Duration.ofDays(30), 0.5));

        assertThat(service.purgeExpiredOperationalState()).isEqualTo(10L);
        verify(hazardFeedHealth).deleteExpiredSimulationHealth(Instant.parse("2026-09-15T00:00:00Z"));
    }
}
