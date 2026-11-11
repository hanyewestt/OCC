package com.csd.occ.disruption.bunching.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Latest detector result for an active simulation episode, not a bus identity list. */
@Service
public class CurrentBunchingEvidenceService {
    private final AlertProcessingStateRepository states;
    private final Clock clock;

    public CurrentBunchingEvidenceService(AlertProcessingStateRepository states, Clock clock) {
        this.states = states;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<Evidence> find(UUID runId, UUID episodeId) {
        return states.findByDemoRunIdAndActiveAlertId(runId, episodeId)
                .filter(state -> state.currentBunchingState() != null && state.currentRawObservationId() != null
                        && state.currentEvidenceValidUntil() != null && state.currentAffectedObservationIds() != null)
                .map(state -> {
                    var now = clock.instant();
                    boolean fresh = runId != null || (!state.lastFetchedAt().isAfter(now)
                            && now.isBefore(state.currentEvidenceValidUntil()));
                    return new Evidence(runId, episodeId, state.serviceId(), state.stopCode(), state.directionId(),
                            state.currentBunchingState(), state.currentRawObservationId(), state.lastFetchedAt(),
                            state.currentEvidenceValidUntil(), fresh,
                            state.currentAffectedObservationIds());
                });
    }

    public record Evidence(UUID demoRunId, UUID bunchingEventId, String serviceId, String stopCode, int directionId,
                           String state, UUID rawObservationId, Instant fetchedAt, Instant validUntil, boolean fresh,
                           List<UUID> affectedObservationIds) {
        public Evidence { affectedObservationIds = List.copyOf(affectedObservationIds); }
    }
}
