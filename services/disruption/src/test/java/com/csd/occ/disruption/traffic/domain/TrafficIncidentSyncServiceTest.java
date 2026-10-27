package com.csd.occ.disruption.traffic.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.csd.occ.disruption.bunching.domain.DisruptionRepository;
import com.csd.occ.disruption.bunching.domain.DiagnosisOutboxRepository;
import com.csd.occ.disruption.bunching.domain.EncodedPlanningEventSubmission;
import com.csd.occ.disruption.bunching.domain.PlanningEventSubmissionPort;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TrafficIncidentSyncServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-15T06:00:00Z");
    private static final Duration FRESHNESS = Duration.ofMinutes(10);
    private static final String SOURCE = "LTA";
    private static final String KEY = "a".repeat(64);

    @Mock private TrafficPollCheckpointRepository checkpoints;
    @Mock private TrafficIncidentStateRepository states;
    @Mock private TrafficIncidentEventRepository events;
    @Mock private EventEvidenceRepository evidence;
    @Mock private DisruptionRepository disruptions;
    @Mock private DiagnosisOutboxRepository planningOutbox;
    @Mock private PlanningEventSubmissionPort planningSubmissions;
    @Mock private TrafficIncidentEvidencePort evidenceCodec;

    private TrafficPollCheckpoint checkpoint;
    private TrafficIncidentSyncService service;

    @BeforeEach
    void setUp() {
        checkpoint = TrafficPollCheckpoint.initial();
        when(checkpoints.findForUpdate()).thenReturn(Optional.of(checkpoint));
        when(evidenceCodec.encode(any(), any(), any())).thenReturn("{}");
        when(planningSubmissions.encodeTraffic(any())).thenReturn(
                new EncodedPlanningEventSubmission("{}", "a".repeat(64), "planning-event-v1"));
        service = serviceAt(NOW);
    }

    @Test
    void pollReplayAndIdenticalIncidentObservationDoNotCreateDuplicateEvents() {
        UUID demoRun = UUID.randomUUID();
        Instant firstAt = NOW.minusSeconds(10);
        TrafficIncidentPollPort.TrafficIncidentPoll firstPoll = poll(UUID.randomUUID(), firstAt, "SIMULATION", demoRun,
                true, "SUCCESS", List.of(fact(UUID.randomUUID(), KEY, "Lane obstruction", "blocked lane", firstAt,
                        "SIMULATION", demoRun)));

        service.process(firstPoll, FRESHNESS, 2);
        service.process(firstPoll, FRESHNESS, 2);

        ArgumentCaptor<TrafficIncidentState> stateCaptor = ArgumentCaptor.forClass(TrafficIncidentState.class);
        verify(states).save(stateCaptor.capture());
        TrafficIncidentState saved = stateCaptor.getValue();
        when(states.findByClassificationAndDemoRunIdAndSourceAndSourceIncidentKey(
                "SIMULATION", demoRun, SOURCE, KEY)).thenReturn(Optional.of(saved));

        Instant secondAt = firstAt.plusSeconds(1);
        var unchangedReceipt = fact(UUID.randomUUID(), KEY, "Lane obstruction", "blocked lane", firstAt.minusSeconds(1),
                secondAt, "SIMULATION", demoRun);
        service.process(poll(UUID.randomUUID(), secondAt, "SIMULATION", demoRun, true, "SUCCESS",
                List.of(unchangedReceipt)), FRESHNESS, 2);

        verify(events, times(1)).save(any(TrafficIncidentEvent.class));
        verify(planningOutbox, times(1)).save(any());
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.absenceCount()).isZero();
        assertThat(saved.message()).isEqualTo("blocked lane");
        assertThat(saved.sourceRevision()).isEqualTo(1);
        assertThat(saved.sourceObservationId()).isEqualTo(unchangedReceipt.id());
    }

    @Test
    void changedTrafficFactsIncrementTheSemanticRevision() {
        UUID demoRun = UUID.randomUUID();
        Instant firstAt = NOW.minusSeconds(10);
        var original = fact(UUID.randomUUID(), KEY, "Lane obstruction", "blocked lane", firstAt,
                "SIMULATION", demoRun);
        service.process(poll(UUID.randomUUID(), firstAt, "SIMULATION", demoRun, true, "SUCCESS", List.of(original)),
                FRESHNESS, 2);
        var state = org.mockito.ArgumentCaptor.forClass(TrafficIncidentState.class);
        verify(states).save(state.capture());
        when(states.findByClassificationAndDemoRunIdAndSourceAndSourceIncidentKey(
                "SIMULATION", demoRun, SOURCE, KEY)).thenReturn(Optional.of(state.getValue()));

        service.process(poll(UUID.randomUUID(), firstAt.plusSeconds(1), "SIMULATION", demoRun, true, "SUCCESS",
                List.of(fact(UUID.randomUUID(), KEY, "Lane obstruction", "two lanes blocked", firstAt.plusSeconds(1),
                        "SIMULATION", demoRun))), FRESHNESS, 2);

        assertThat(state.getValue().sourceRevision()).isEqualTo(2);
        assertThat(state.getValue().sourceObservationId()).isNotEqualTo(original.id());
    }

    @Test
    void identicalProviderKeysRemainSeparateAcrossLiveAndDemoScopes() {
        Instant firstAt = NOW.minusSeconds(10);
        UUID demoRun = UUID.randomUUID();

        service.process(poll(UUID.randomUUID(), firstAt, "OBSERVATION_ONLY", null, true, "SUCCESS",
                List.of(fact(UUID.randomUUID(), KEY, "Lane obstruction", "live", firstAt,
                        "OBSERVATION_ONLY", null))), FRESHNESS, 2);
        service.process(poll(UUID.randomUUID(), firstAt.plusSeconds(1), "SIMULATION", demoRun, true, "SUCCESS",
                List.of(fact(UUID.randomUUID(), KEY, "Lane obstruction", "demo", firstAt.plusSeconds(1),
                        "SIMULATION", demoRun))), FRESHNESS, 2);

        verify(states).findByClassificationAndDemoRunIdIsNullAndSourceAndSourceIncidentKey(
                "OBSERVATION_ONLY", SOURCE, KEY);
        verify(states).findByClassificationAndDemoRunIdAndSourceAndSourceIncidentKey(
                "SIMULATION", demoRun, SOURCE, KEY);
        verify(states, times(2)).save(any(TrafficIncidentState.class));
        verify(events, times(2)).save(any(TrafficIncidentEvent.class));
        verify(planningOutbox, times(2)).save(any());
    }

    @Test
    void incompleteFailedAndMalformedPollsDoNotCountTowardClearingAnActiveIncident() {
        UUID demoRun = UUID.randomUUID();
        Instant firstAt = NOW.minusSeconds(10);
        service.process(poll(UUID.randomUUID(), firstAt, "SIMULATION", demoRun, true, "SUCCESS",
                List.of(fact(UUID.randomUUID(), KEY, "Lane obstruction", "blocked lane", firstAt,
                        "SIMULATION", demoRun))), FRESHNESS, 2);

        ArgumentCaptor<TrafficIncidentState> stateCaptor = ArgumentCaptor.forClass(TrafficIncidentState.class);
        verify(states).save(stateCaptor.capture());
        TrafficIncidentState active = stateCaptor.getValue();
        when(states.findByClassificationAndDemoRunIdAndSourceAndStatus("SIMULATION", demoRun, SOURCE, "ACTIVE"))
                .thenReturn(List.of(active));

        service.process(poll(UUID.randomUUID(), firstAt.plusSeconds(1), "SIMULATION", demoRun, false, "PARTIAL",
                List.of()), FRESHNESS, 2);
        service.process(poll(UUID.randomUUID(), firstAt.plusSeconds(2), "SIMULATION", demoRun, false, "FAILURE",
                List.of()), FRESHNESS, 2);
        service.process(poll(UUID.randomUUID(), firstAt.plusSeconds(3), "SIMULATION", demoRun, true, "SUCCESS",
                List.of(fact(UUID.randomUUID(), "invalid-key", "Lane obstruction", "partial data", firstAt,
                        "SIMULATION", demoRun))),
                FRESHNESS, 2);

        assertThat(active.absenceCount()).isZero();
        assertThat(active.isActive()).isTrue();
        verify(events, times(1)).save(any(TrafficIncidentEvent.class));
        verify(planningOutbox, times(1)).save(any());

        service.process(poll(UUID.randomUUID(), firstAt.plusSeconds(4), "SIMULATION", demoRun, true, "SUCCESS",
                List.of()), FRESHNESS, 2);

        assertThat(active.absenceCount()).isEqualTo(1);
        assertThat(active.isActive()).isTrue();
        verify(events, times(1)).save(any(TrafficIncidentEvent.class));
        verify(planningOutbox, times(1)).save(any());
    }

    private TrafficIncidentSyncService serviceAt(Instant instant) {
        return new TrafficIncidentSyncService(checkpoints, states, events, evidence, disruptions, planningOutbox,
                planningSubmissions, evidenceCodec, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private TrafficIncidentPollPort.TrafficIncidentPoll poll(UUID id, Instant completedAt, String classification,
            UUID demoRunId, boolean complete, String outcome,
            List<TrafficIncidentPollPort.TrafficIncidentFact> incidents) {
        return new TrafficIncidentPollPort.TrafficIncidentPoll(id, SOURCE, outcome, complete, classification,
                demoRunId, completedAt.minusSeconds(1), completedAt, incidents);
    }

    private TrafficIncidentPollPort.TrafficIncidentFact fact(UUID id, String key, String type, String message,
            Instant fetchedAt, String classification, UUID demoRunId) {
        return fact(id, key, type, message, fetchedAt.minusSeconds(1), fetchedAt, classification, demoRunId);
    }

    private TrafficIncidentPollPort.TrafficIncidentFact fact(UUID id, String key, String type, String message,
            Instant observedAt, Instant fetchedAt, String classification, UUID demoRunId) {
        return new TrafficIncidentPollPort.TrafficIncidentFact(id, UUID.randomUUID(), key, type, message,
                new BigDecimal("1.38"), new BigDecimal("103.75"), observedAt, fetchedAt,
                classification, demoRunId);
    }
}
