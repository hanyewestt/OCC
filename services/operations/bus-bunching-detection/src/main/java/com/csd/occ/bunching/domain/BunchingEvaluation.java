package com.csd.occ.bunching.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Observation-only evidence, not a planning incident or operational directive. */
public record BunchingEvaluation(
        ArrivalSnapshot snapshot,
        Instant evaluatedAt,
        Duration threshold,
        Duration staleAfter,
        List<GroupEvaluation> groups) {

    public BunchingEvaluation {
        groups = List.copyOf(groups);
    }

    public enum State { BUNCHING, NORMAL, INSUFFICIENT_EVIDENCE }

    public enum Reason {
        NONE, STALE_SNAPSHOT, FUTURE_SNAPSHOT, UNKNOWN_DIRECTION,
        MISSING_ARRIVAL, PAST_ARRIVAL, TOO_FEW_ARRIVALS, DUPLICATE_ARRIVAL
    }

    public record Group(String serviceId, String stopCode, Optional<Integer> directionId) {}

    public record GroupEvaluation(Group group, State state, Reason reason, List<Headway> headways) {
        public GroupEvaluation {
            headways = List.copyOf(headways);
        }
    }

    public record Headway(UUID leadingObservationId, UUID followingObservationId,
                          Duration gap, boolean isBunched) {}
}
