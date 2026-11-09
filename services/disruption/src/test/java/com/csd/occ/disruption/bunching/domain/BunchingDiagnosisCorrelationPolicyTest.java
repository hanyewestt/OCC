package com.csd.occ.disruption.bunching.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BunchingDiagnosisCorrelationPolicyTest {
    private static final UUID REFERENCE = id(1);
    private static final UUID CYCLE = id(2);
    private static final Instant FETCHED_AT = Instant.parse("2026-10-27T04:00:00Z");
    private final BunchingDiagnosisCorrelationPolicy policy = new BunchingDiagnosisCorrelationPolicy();

    @Test
    void exactPairInTheSameSweepCorrelatesAcrossUniqueStops() {
        ArrivalSnapshot first = snapshot("44009", 10, 1, buses("44009", 10));
        ArrivalSnapshot second = snapshot("44019", 11, 1, buses("44019", 11));

        assertThat(correlation(first)).isPresent();
        assertThat(correlation(second)).isEqualTo(correlation(first));
    }

    @Test
    void leadingAndFollowingOrderDoesNotChangeThePair() {
        List<ArrivalSnapshot.Arrival> arrivals = buses("44009", 10);
        ArrivalSnapshot snapshot = snapshot("44009", 10, 1, arrivals);
        BunchingEvaluation.Headway reversed = new BunchingEvaluation.Headway(
                arrivals.get(1).observationId(), arrivals.get(0).observationId(), Duration.ofSeconds(60), true);

        assertThat(policy.correlationFor(snapshot, group("44009", 1), reversed)).isEqualTo(correlation(snapshot));
    }

    @Test
    void anotherPhysicalPairGetsAnotherCorrelation() {
        ArrivalSnapshot first = snapshot("44009", 10, 1, buses("44009", 10));
        List<ArrivalSnapshot.Arrival> changed = List.of(
                arrival(id(30), "44019", 11, 1, "1.301", "103.801", "10009", "99999", 1),
                arrival(id(31), "44019", 11, 2, "1.309", "103.809", "10009", "99999", 1));

        assertThat(correlation(snapshot("44019", 11, 1, changed))).isNotEqualTo(correlation(first));
    }

    @Test
    void routeDirectionsRemainIndependent() {
        ArrivalSnapshot directionOne = snapshot("44009", 10, 1, buses("44009", 10));
        List<ArrivalSnapshot.Arrival> directionTwoBuses = buses("44009", 10).stream()
                .map(arrival -> new ArrivalSnapshot.Arrival(arrival.observationId(), arrival.serviceId(),
                        arrival.stopCode(), Optional.of(2), arrival.visitOrder(), arrival.estimatedArrivalAt(),
                        arrival.operator(), arrival.visitNumber(), arrival.originCode(), arrival.destinationCode(),
                        arrival.latitude(), arrival.longitude(), arrival.routeStopSequence()))
                .toList();
        ArrivalSnapshot directionTwo = snapshot("44009", 10, 2, directionTwoBuses);

        assertThat(correlation(directionTwo)).isNotEqualTo(correlation(directionOne));
    }

    @Test
    void providerPollSweepsRemainIndependent() {
        ArrivalSnapshot first = snapshot("44009", 10, 1, buses("44009", 10));
        ArrivalSnapshot laterSweep = snapshot("190", "44009", 1, buses("44009", 10),
                Optional.of(id(3)), Optional.of(REFERENCE));

        assertThat(correlation(laterSweep)).isNotEqualTo(correlation(first));
    }

    @Test
    void circularRouteVisitsRemainIndependent() {
        List<ArrivalSnapshot.Arrival> firstVisit = circularBuses("44009", 10, 1);
        List<ArrivalSnapshot.Arrival> secondVisit = circularBuses("44009", 10, 2);
        ArrivalSnapshot first = snapshot("972", "44009", 1, firstVisit,
                Optional.of(CYCLE), Optional.of(REFERENCE));
        ArrivalSnapshot second = snapshot("972", "44009", 1, secondVisit,
                Optional.of(CYCLE), Optional.of(REFERENCE));
        BunchingEvaluation.Group group = new BunchingEvaluation.Group("972", "44009", Optional.of(1));

        assertThat(policy.correlationFor(second, group, headway(secondVisit)))
                .isNotEqualTo(policy.correlationFor(first, group, headway(firstVisit)));
    }

    @Test
    void repeatedCircularStopOccurrenceFailsOpen() {
        List<ArrivalSnapshot.Arrival> ambiguous = buses("44009", 10).stream()
                .map(arrival -> new ArrivalSnapshot.Arrival(arrival.observationId(), "972", arrival.stopCode(),
                        arrival.directionId(), arrival.visitOrder(), arrival.estimatedArrivalAt(), arrival.operator(),
                        arrival.visitNumber(), arrival.originCode(), arrival.destinationCode(), arrival.latitude(),
                        arrival.longitude(), null))
                .toList();
        ArrivalSnapshot snapshot = snapshot("972", "44009", 1, ambiguous, Optional.of(CYCLE), Optional.of(REFERENCE));

        assertThat(policy.correlationFor(snapshot,
                new BunchingEvaluation.Group("972", "44009", Optional.of(1)), headway(ambiguous))).isEmpty();
    }

    @Test
    void duplicateVehicleIdentityWithinOneStopFailsOpen() {
        List<ArrivalSnapshot.Arrival> ambiguous = new ArrayList<>(buses("44009", 10));
        ArrivalSnapshot.Arrival first = ambiguous.getFirst();
        ambiguous.add(new ArrivalSnapshot.Arrival(id(40), first.serviceId(), first.stopCode(), first.directionId(), 3,
                Optional.of(FETCHED_AT.plusSeconds(420)), first.operator(), first.visitNumber(), first.originCode(),
                first.destinationCode(), first.latitude(), first.longitude(), first.routeStopSequence()));

        assertThat(correlation(snapshot("44009", 10, 1, ambiguous))).isEmpty();
    }

    @Test
    void incompleteIdentityAndSweepEvidenceFailOpen() {
        List<ArrivalSnapshot.Arrival> complete = buses("44009", 10);
        ArrivalSnapshot noCycle = snapshot("190", "44009", 1, complete, Optional.empty(), Optional.of(REFERENCE));
        ArrivalSnapshot noReference = snapshot("190", "44009", 1, complete, Optional.of(CYCLE), Optional.empty());
        ArrivalSnapshot.Arrival first = complete.getFirst();
        List<ArrivalSnapshot.Arrival> missingIdentity = List.of(
                new ArrivalSnapshot.Arrival(first.observationId(), first.serviceId(), first.stopCode(),
                        first.directionId(), first.visitOrder(), first.estimatedArrivalAt(), null, null, null, null,
                        null, null, first.routeStopSequence()),
                complete.get(1));

        assertThat(correlation(noCycle)).isEmpty();
        assertThat(correlation(noReference)).isEmpty();
        assertThat(correlation(snapshot("44009", 10, 1, missingIdentity))).isEmpty();
    }

    private Optional<BunchingDiagnosisCorrelationPolicy.Correlation> correlation(ArrivalSnapshot snapshot) {
        return policy.correlationFor(snapshot, group(snapshot.arrivals().getFirst().stopCode(),
                snapshot.arrivals().getFirst().directionId().orElseThrow()), headway(snapshot.arrivals()));
    }

    private static BunchingEvaluation.Group group(String stopCode, int direction) {
        return new BunchingEvaluation.Group("190", stopCode, Optional.of(direction));
    }

    private static BunchingEvaluation.Headway headway(List<ArrivalSnapshot.Arrival> arrivals) {
        return new BunchingEvaluation.Headway(arrivals.get(0).observationId(), arrivals.get(1).observationId(),
                Duration.ofSeconds(60), true);
    }

    private static ArrivalSnapshot snapshot(String stopCode, int sequence, int direction,
            List<ArrivalSnapshot.Arrival> arrivals) {
        return snapshot("190", stopCode, direction, arrivals, Optional.of(CYCLE), Optional.of(REFERENCE));
    }

    private static ArrivalSnapshot snapshot(String serviceId, String stopCode, int direction,
            List<ArrivalSnapshot.Arrival> arrivals, Optional<UUID> cycle, Optional<UUID> reference) {
        return new ArrivalSnapshot(id(50 + direction), id(60 + direction), cycle, reference, FETCHED_AT, arrivals,
                "OBSERVATION_ONLY", null);
    }

    private static List<ArrivalSnapshot.Arrival> buses(String stopCode, int sequence) {
        return List.of(
                arrival(id(20), stopCode, sequence, 1, "1.300", "103.800", "10009", "99999", 1),
                arrival(id(21), stopCode, sequence, 2, "1.305", "103.805", "10009", "99999", 1));
    }

    private static List<ArrivalSnapshot.Arrival> circularBuses(String stopCode, int sequence, int visitNumber) {
        return buses(stopCode, sequence).stream()
                .map(arrival -> new ArrivalSnapshot.Arrival(arrival.observationId(), "972", arrival.stopCode(),
                        arrival.directionId(), arrival.visitOrder(), arrival.estimatedArrivalAt(), arrival.operator(),
                        visitNumber, arrival.originCode(), arrival.destinationCode(), arrival.latitude(),
                        arrival.longitude(), arrival.routeStopSequence()))
                .toList();
    }

    private static ArrivalSnapshot.Arrival arrival(UUID id, String stopCode, int sequence, int visitOrder,
            String latitude, String longitude, String origin, String destination, int visitNumber) {
        return new ArrivalSnapshot.Arrival(id, "190", stopCode, Optional.of(1), visitOrder,
                Optional.of(FETCHED_AT.plusSeconds(300L + visitOrder * 60L)), "SMRT", visitNumber, origin,
                destination, new BigDecimal(latitude), new BigDecimal(longitude), sequence);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
