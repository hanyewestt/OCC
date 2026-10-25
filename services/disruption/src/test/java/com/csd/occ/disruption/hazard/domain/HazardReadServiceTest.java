package com.csd.occ.disruption.hazard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.csd.occ.disruption.bunching.domain.ConsumerSelectionService;
import com.csd.occ.disruption.bunching.domain.ConsumerState;
import com.csd.occ.disruption.bunching.domain.Disruption;
import com.csd.occ.disruption.bunching.domain.DisruptionRepository;
import com.csd.occ.disruption.bunching.domain.PlanningEventSubmissionPort;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentEvidencePort;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentEventRepository;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentSnapshot;
import com.csd.occ.disruption.traffic.domain.TrafficIncidentStateRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HazardReadServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-25T04:00:00Z");

    @Test
    void retainedTrafficEpisodeRemainsReadableAfterSourceStateReopens() {
        var hazards = mock(HazardStateRepository.class);
        var selection = mock(ConsumerSelectionService.class);
        var state = mock(ConsumerState.class);
        when(selection.current()).thenReturn(state);
        when(state.demoRunId()).thenReturn(null);
        var disruptions = mock(DisruptionRepository.class);
        var traffic = mock(TrafficIncidentStateRepository.class);
        var events = mock(TrafficIncidentEventRepository.class);
        var evidence = mock(TrafficIncidentEvidencePort.class);
        var checkpoints = mock(HazardCheckpointRepository.class);
        var disruptionId = UUID.randomUUID();
        var observationId = UUID.randomUUID();
        var sourceEvidence = "retained event snapshot";
        var disruption = mock(Disruption.class);
        when(disruption.kind()).thenReturn("TRAFFIC_INCIDENT");
        when(disruption.status()).thenReturn("CLEARED");
        when(disruption.classification()).thenReturn("OBSERVATION_ONLY");
        when(disruption.updatedAt()).thenReturn(NOW);
        when(disruptions.findById(disruptionId)).thenReturn(Optional.of(disruption));
        when(traffic.findByDisruptionId(disruptionId)).thenReturn(Optional.empty());
        when(events.findLatestEvidenceByDisruptionId(disruptionId)).thenReturn(Optional.of(sourceEvidence));
        when(evidence.decode(sourceEvidence)).thenReturn(new TrafficIncidentSnapshot(observationId, 2,
                UUID.randomUUID(), "a".repeat(64), "Lane obstruction", "Blocked lane", new BigDecimal("1.38"),
                new BigDecimal("103.75"), NOW.minusSeconds(60), NOW.minusSeconds(30)));
        var service = new HazardReadService(hazards, selection, mock(HazardTransitPort.class),
                Clock.fixed(NOW, ZoneOffset.UTC), disruptions, traffic, events, evidence,
                mock(PlanningEventSubmissionPort.class), checkpoints);

        var result = service.find(disruptionId);

        assertThat(result.disruptionId()).isEqualTo(disruptionId);
        assertThat(result.status()).isEqualTo("CLEARED");
        assertThat(result.inactiveReason()).isEqualTo("NO_LONGER_REPORTED");
        assertThat(result.sourceObservationId()).isEqualTo(observationId);
        assertThat(result.sourceRevision()).isEqualTo(2L);
        assertThat(result.facts().description()).isEqualTo("Blocked lane");
    }

    @Test
    void retainedHazardEventsKeepClearedSourceReadableAfterOperationalStatePurges() {
        var hazards = mock(HazardStateRepository.class);
        var selection = mock(ConsumerSelectionService.class);
        var consumerState = mock(ConsumerState.class);
        when(selection.current()).thenReturn(consumerState);
        when(consumerState.demoRunId()).thenReturn(null);
        var disruptions = mock(DisruptionRepository.class);
        var traffic = mock(TrafficIncidentStateRepository.class);
        var trafficEvents = mock(TrafficIncidentEventRepository.class);
        var trafficEvidence = mock(TrafficIncidentEvidencePort.class);
        var eventCodec = mock(PlanningEventSubmissionPort.class);
        var checkpoints = mock(HazardCheckpointRepository.class);
        var disruptionId = UUID.randomUUID();
        var sourceObservationId = UUID.randomUUID();
        var disruption = mock(Disruption.class);
        when(disruption.kind()).thenReturn("FLOOD");
        when(disruption.status()).thenReturn("CLEARED");
        when(disruption.classification()).thenReturn("OBSERVATION_ONLY");
        when(disruption.updatedAt()).thenReturn(NOW);
        when(disruptions.findById(disruptionId)).thenReturn(Optional.of(disruption));
        when(traffic.findByDisruptionId(disruptionId)).thenReturn(Optional.empty());
        when(hazards.findLatestHazardEventEvidence(disruptionId)).thenReturn(Optional.of("event snapshot"));
        var facts = new HazardPollPort.Fact("FLOOD", "flood-1", "OBSERVED", NOW, null, null, "Flood", "Road flooded",
                null, null, List.of(), null, null, null, null, List.of());
        when(eventCodec.decodeHazard("event snapshot")).thenReturn(new HazardEventSnapshot(UUID.randomUUID(), disruptionId,
                "CLEARED", "SOURCE_CANCELLED", "OBSERVATION_ONLY", null, sourceObservationId, null,
                UUID.randomUUID(), null, NOW, facts, null));
        var service = new HazardReadService(hazards, selection, mock(HazardTransitPort.class),
                Clock.fixed(NOW, ZoneOffset.UTC), disruptions, traffic, trafficEvents, trafficEvidence, eventCodec, checkpoints);

        var result = service.find(disruptionId);

        assertThat(result.kind()).isEqualTo("FLOOD");
        assertThat(result.status()).isEqualTo("CLEARED");
        assertThat(result.inactiveReason()).isEqualTo("SOURCE_CANCELLED");
        assertThat(result.sourceObservationId()).isEqualTo(sourceObservationId);
        assertThat(result.facts().description()).isEqualTo("Road flooded");
    }
}
