package com.csd.occ.disruption.bunching.domain;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Correlates only exact, provider-reported two-bus evidence within one coordinated poll sweep.
 * Missing or ambiguous identity evidence deliberately fails open so a diagnosis is not suppressed.
 */
public final class BunchingDiagnosisCorrelationPolicy {
    public static final String RULE_VERSION = "same-sweep-pair-v1";

    public Optional<Correlation> correlationFor(ArrivalSnapshot snapshot, BunchingEvaluation.Group group,
            BunchingEvaluation.Headway headway) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(group);
        Objects.requireNonNull(headway);
        if (!headway.isBunched() || snapshot.referenceVersionId().isEmpty() || snapshot.sourcePollCycleId().isEmpty()
                || group.directionId().isEmpty() || group.directionId().orElseThrow() <= 0) return Optional.empty();

        Optional<ArrivalSnapshot.Arrival> leading = uniqueArrival(snapshot, headway.leadingObservationId());
        Optional<ArrivalSnapshot.Arrival> following = uniqueArrival(snapshot, headway.followingObservationId());
        if (leading.isEmpty() || following.isEmpty()) return Optional.empty();
        ArrivalSnapshot.Arrival first = leading.orElseThrow();
        ArrivalSnapshot.Arrival second = following.orElseThrow();
        int direction = group.directionId().orElseThrow();
        if (!belongsTo(first, group, direction) || !belongsTo(second, group, direction)
                || !Objects.equals(first.routeStopSequence(), second.routeStopSequence())) return Optional.empty();

        String operator = normalize(first.operator());
        if (operator.isEmpty() || !operator.equals(normalize(second.operator()))) return Optional.empty();
        Optional<String> firstIdentity = identity(first);
        Optional<String> secondIdentity = identity(second);
        if (firstIdentity.isEmpty() || secondIdentity.isEmpty()
                || firstIdentity.orElseThrow().equals(secondIdentity.orElseThrow())) return Optional.empty();
        if (!isUniqueWithinSnapshot(snapshot, group.serviceId(), direction, operator, firstIdentity.orElseThrow())
                || !isUniqueWithinSnapshot(snapshot, group.serviceId(), direction, operator,
                        secondIdentity.orElseThrow())) return Optional.empty();

        List<String> pair = new ArrayList<>(List.of(firstIdentity.orElseThrow(), secondIdentity.orElseThrow()));
        pair.sort(Comparator.naturalOrder());
        return Optional.of(new Correlation(hash(List.of(RULE_VERSION, snapshot.classification(),
                snapshot.demoRunId() == null ? "" : snapshot.demoRunId().toString(),
                snapshot.referenceVersionId().orElseThrow().toString(),
                snapshot.sourcePollCycleId().orElseThrow().toString(), normalize(group.serviceId()), operator,
                Integer.toString(direction), pair.get(0), pair.get(1))), RULE_VERSION));
    }

    private static Optional<ArrivalSnapshot.Arrival> uniqueArrival(ArrivalSnapshot snapshot,
            java.util.UUID observationId) {
        List<ArrivalSnapshot.Arrival> matches = snapshot.arrivals().stream()
                .filter(arrival -> arrival.observationId().equals(observationId)).toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private static boolean belongsTo(ArrivalSnapshot.Arrival arrival, BunchingEvaluation.Group group, int direction) {
        return normalize(arrival.serviceId()).equals(normalize(group.serviceId()))
                && arrival.stopCode().equals(group.stopCode())
                && arrival.directionId().filter(value -> value == direction).isPresent()
                && arrival.routeStopSequence() != null && arrival.routeStopSequence() > 0;
    }

    private static Optional<String> identity(ArrivalSnapshot.Arrival arrival) {
        if (arrival.visitNumber() == null || arrival.visitNumber() <= 0
                || !usableCoordinate(arrival.latitude(), arrival.longitude())) return Optional.empty();
        String origin = normalize(arrival.originCode());
        String destination = normalize(arrival.destinationCode());
        if (origin.isEmpty() || destination.isEmpty()) return Optional.empty();
        return Optional.of(hash(List.of(canonical(arrival.latitude()), canonical(arrival.longitude()), origin,
                destination, Integer.toString(arrival.visitNumber()))));
    }

    private static boolean isUniqueWithinSnapshot(ArrivalSnapshot snapshot, String serviceId, int direction,
            String operator, String identity) {
        long occurrences = snapshot.arrivals().stream()
                .filter(arrival -> normalize(arrival.serviceId()).equals(normalize(serviceId)))
                .filter(arrival -> arrival.directionId().filter(value -> value == direction).isPresent())
                .filter(arrival -> normalize(arrival.operator()).equals(operator))
                .map(BunchingDiagnosisCorrelationPolicy::identity)
                .flatMap(Optional::stream)
                .filter(identity::equals)
                .count();
        return occurrences == 1;
    }

    private static boolean usableCoordinate(BigDecimal latitude, BigDecimal longitude) {
        if (latitude == null || longitude == null) return false;
        return latitude.compareTo(BigDecimal.valueOf(-90)) >= 0
                && latitude.compareTo(BigDecimal.valueOf(90)) <= 0
                && longitude.compareTo(BigDecimal.valueOf(-180)) >= 0
                && longitude.compareTo(BigDecimal.valueOf(180)) <= 0
                && (latitude.signum() != 0 || longitude.signum() != 0);
    }

    private static String canonical(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    private static String hash(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", unavailable);
        }
    }

    public record Correlation(String key, String ruleVersion) {
        public Correlation {
            Objects.requireNonNull(key);
            Objects.requireNonNull(ruleVersion);
            if (key.length() != 64) throw new IllegalArgumentException("Correlation key must be a SHA-256 hex value");
        }
    }
}
