package com.csd.occ.ingestion.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Pure rule: turns one prediction set per stop, service and direction into a gap ahead and behind
 * for every predicted bus. The caller supplies server time and every threshold.
 */
public final class PacingPolicy {
    private static final Comparator<Group> GROUP_ORDER = Comparator.comparing(Group::classification)
            .thenComparing(Group::serviceId)
            .thenComparingInt(Group::directionId)
            .thenComparing(Group::stopCode);

    public List<PacingMetric> evaluate(List<PacingArrival> arrivals, Instant now, Duration targetHeadway,
                                       double tooCloseRatio, double tooFarRatio, Duration staleAfter) {
        Objects.requireNonNull(arrivals);
        Objects.requireNonNull(now);
        requirePositive(targetHeadway, "Target headway");
        requirePositive(staleAfter, "Freshness limit");
        if (!(tooCloseRatio > 0 && tooCloseRatio < 1 && tooFarRatio > 1))
            throw new IllegalArgumentException("Pacing ratios must satisfy 0 < too-close < 1 < too-far");
        var grouped = arrivals.stream().collect(Collectors.groupingBy(arrival -> new Group(
                arrival.classification(), arrival.stopCode(), arrival.serviceId(), arrival.directionId())));
        var metrics = new ArrayList<PacingMetric>();
        grouped.keySet().stream().sorted(GROUP_ORDER).forEach(group -> {
            var members = grouped.get(group);
            // A future or expired prediction set is not evidence of current spacing.
            if (members.stream().anyMatch(arrival -> !isFresh(arrival.fetchedAt(), now, staleAfter))) return;
            var ordered = members.stream().sorted(Comparator.comparing(PacingArrival::estimatedArrivalAt)).toList();
            for (int index = 0; index < ordered.size(); index++) {
                var bus = ordered.get(index);
                Optional<PacingGap> ahead = index == 0 ? Optional.empty()
                        : Optional.of(gap(ordered.get(index - 1), bus, targetHeadway, tooCloseRatio, tooFarRatio));
                Optional<PacingGap> behind = index == ordered.size() - 1 ? Optional.empty()
                        : Optional.of(gap(bus, ordered.get(index + 1), targetHeadway, tooCloseRatio, tooFarRatio));
                metrics.add(new PacingMetric(bus.vehicleReference().orElse(liveReference(group, index + 1)),
                        group.classification(), group.stopCode(), group.serviceId(), group.directionId(),
                        targetHeadway, ahead, behind, bus.fetchedAt()));
            }
        });
        return List.copyOf(metrics);
    }

    public PacingState classify(Duration headway, Duration targetHeadway, double tooCloseRatio, double tooFarRatio) {
        double headwayMillis = headway.toMillis();
        double targetMillis = targetHeadway.toMillis();
        if (headwayMillis <= targetMillis * tooCloseRatio) return PacingState.TOO_CLOSE;
        if (headwayMillis >= targetMillis * tooFarRatio) return PacingState.TOO_FAR;
        return PacingState.ON_TARGET;
    }

    private PacingGap gap(PacingArrival leading, PacingArrival following, Duration targetHeadway,
                          double tooCloseRatio, double tooFarRatio) {
        Duration headway = Duration.between(leading.estimatedArrivalAt(), following.estimatedArrivalAt());
        return new PacingGap(headway, classify(headway, targetHeadway, tooCloseRatio, tooFarRatio));
    }

    private boolean isFresh(Instant fetchedAt, Instant now, Duration staleAfter) {
        // At the expiry boundary the evidence is already stale, matching the bunching detector.
        return !fetchedAt.isAfter(now) && Duration.between(fetchedAt, now).compareTo(staleAfter) < 0;
    }

    /**
     * Bus Arrival has no vehicle identity, so a live bus is named by its queue position at the stop.
     * The reference is only stable within one calculation cycle.
     */
    private String liveReference(Group group, int position) {
        return "LIVE-" + group.serviceId() + "-D" + group.directionId() + "-" + group.stopCode() + "-" + position;
    }

    private void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value);
        if (value.isNegative() || value.isZero()) throw new IllegalArgumentException(name + " must be positive");
    }

    private record Group(String classification, String stopCode, String serviceId, int directionId) {}
}
