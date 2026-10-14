package com.csd.occ.disruption.bunching.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.csd.occ.disruption.bunching.domain.BunchingEvaluation.*;

/** Pure rule: the caller supplies server time, freshness limit and threshold. */
public final class BunchingPolicy {
    public static final Duration DEFAULT_THRESHOLD = Duration.ofSeconds(90);

    public BunchingEvaluation evaluate(ArrivalSnapshot snapshot, Instant now,
                                       Duration threshold, Duration staleAfter) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(now);
        requirePositive(threshold, "Threshold");
        requirePositive(staleAfter, "Freshness limit");

        var grouped = snapshot.arrivals().stream().collect(Collectors.groupingBy(
                arrival -> new Group(arrival.serviceId(), arrival.stopCode(), arrival.directionId())));
        var order = Comparator.comparing(Group::serviceId)
                .thenComparing(Group::stopCode)
                .thenComparing(group -> group.directionId().orElse(0));
        var results = grouped.keySet().stream().sorted(order)
                .map(group -> evaluateGroup(group, grouped.get(group), snapshot.fetchedAt(),
                        now, threshold, staleAfter))
                .toList();
        return new BunchingEvaluation(snapshot, now, threshold, staleAfter, results);
    }

    private GroupEvaluation evaluateGroup(Group group, List<ArrivalSnapshot.Arrival> arrivals,
                                           Instant fetchedAt, Instant now,
                                           Duration threshold, Duration staleAfter) {
        if (fetchedAt.isAfter(now)) {
            return insufficient(group, Reason.FUTURE_SNAPSHOT);
        }
        // At the expiry boundary the evidence is already stale.
        if (Duration.between(fetchedAt, now).compareTo(staleAfter) >= 0) {
            return insufficient(group, Reason.STALE_SNAPSHOT);
        }
        // Bus Arrival is the authoritative source for the stop's service direction.
        // Do not reject an otherwise usable arrival group solely because a legacy row
        // lacks the derived direction value.
        if (arrivals.stream().anyMatch(arrival -> arrival.estimatedArrivalAt().isEmpty())) {
            return insufficient(group, Reason.MISSING_ARRIVAL);
        }
        if (arrivals.stream().map(ArrivalSnapshot.Arrival::observationId).distinct().count() != arrivals.size()
                || arrivals.stream().map(ArrivalSnapshot.Arrival::visitOrder).distinct().count() != arrivals.size()) {
            return insufficient(group, Reason.DUPLICATE_ARRIVAL);
        }
        if (arrivals.size() < 2) {
            if (arrivals.stream().anyMatch(arrival -> arrival.estimatedArrivalAt().orElseThrow().isBefore(now))) {
                return insufficient(group, Reason.PAST_ARRIVAL);
            }
            return insufficient(group, Reason.TOO_FEW_ARRIVALS);
        }

        var sorted = arrivals.stream().sorted(Comparator
                .comparing((ArrivalSnapshot.Arrival arrival) -> arrival.estimatedArrivalAt().orElseThrow())
                .thenComparingInt(ArrivalSnapshot.Arrival::visitOrder)).toList();
        var headways = new ArrayList<Headway>();
        for (int index = 1; index < sorted.size(); index++) {
            var leading = sorted.get(index - 1);
            var following = sorted.get(index);
            // Ignore expired pairs, not the whole fresh snapshot. Remaining adjacent estimates are usable.
            if (leading.estimatedArrivalAt().orElseThrow().isBefore(now)
                    || following.estimatedArrivalAt().orElseThrow().isBefore(now)) continue;
            var gap = Duration.between(leading.estimatedArrivalAt().orElseThrow(),
                    following.estimatedArrivalAt().orElseThrow());
            headways.add(new Headway(leading.observationId(), following.observationId(),
                    gap, gap.compareTo(threshold) < 0));
        }
        if (headways.isEmpty()) return insufficient(group, Reason.PAST_ARRIVAL);
        var state = headways.stream().anyMatch(Headway::isBunched) ? State.BUNCHING : State.NORMAL;
        return new GroupEvaluation(group, state, Reason.NONE, headways);
    }

    private GroupEvaluation insufficient(Group group, Reason reason) {
        return new GroupEvaluation(group, State.INSUFFICIENT_EVIDENCE, reason, List.of());
    }

    private void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
