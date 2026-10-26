package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoutePositionServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-23T04:00:00Z");

    @Test
    void exposesNewestSourceCycleSeparatelyFromComputationTimeAndChecksEachDirectionScope() {
        UUID olderCycle = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID newestCycle = UUID.fromString("10000000-0000-0000-0000-000000000002");
        RoutePositionReading older = reading("older", olderCycle, NOW.minusSeconds(40));
        RoutePositionReading newest = reading("newest", newestCycle, NOW.minusSeconds(2));
        RoutePositionRepository repository = (serviceId, from, to) -> List.of(
                new RoutePositionRoute("SMRT", 1, 10_000, List.of(older)),
                new RoutePositionRoute("SMRT", 2, 10_000, List.of(newest)));
        RecordingSettings settings = new RecordingSettings();
        RoutePositionService service = new RoutePositionService(repository, settings,
                Clock.fixed(NOW, ZoneOffset.UTC));

        RoutePositionSnapshot snapshot = service.positions("190").orElseThrow();

        assertThat(snapshot.computedAt()).isEqualTo(NOW);
        assertThat(snapshot.sourcePollCycleId()).contains(newestCycle);
        assertThat(snapshot.sourceObservedAt()).contains(NOW.minusSeconds(2));
        assertThat(snapshot.refreshIntervalSeconds()).isEqualTo(60);
        assertThat(snapshot.maxSourceAgeSeconds()).isEqualTo(120);
        assertThat(settings.checkedIdentityLinkScopes).containsExactly("190:1", "190:2");
        assertThat(settings.checkedBoundaryScopes).containsExactly("190:1", "190:2");
        assertThat(settings.checkedCircularPhaseScopes).containsExactly("190:1", "190:2");
    }

    @Test
    void leavesSourceMetadataEmptyWhenTheCompletedCycleHasNoUsableReadings() {
        RoutePositionRepository repository = (serviceId, from, to) -> List.of(
                new RoutePositionRoute("SMRT", 1, 10_000, List.of()));
        RoutePositionService service = new RoutePositionService(repository, new RecordingSettings(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        RoutePositionSnapshot snapshot = service.positions("190").orElseThrow();

        assertThat(snapshot.sourcePollCycleId()).isEmpty();
        assertThat(snapshot.sourceObservedAt()).isEmpty();
        assertThat(snapshot.directions()).singleElement().satisfies(direction ->
                assertThat(direction.buses()).isEmpty());
    }

    @Test
    void emptyNewestCompletedCycleDoesNotFallBackToOlderReadings() {
        UUID olderCycle = UUID.fromString("10000000-0000-0000-0000-000000000011");
        UUID emptyNewestCycle = UUID.fromString("10000000-0000-0000-0000-000000000012");
        RoutePositionReading older = reading("older-visible", olderCycle, NOW.minusSeconds(50));
        RoutePositionRepository repository = (serviceId, from, to) -> List.of(
                new RoutePositionRoute("SMRT", 1, 10_000, false, 10_000,
                        emptyNewestCycle, List.of(older)));
        RoutePositionService service = new RoutePositionService(repository, new RecordingSettings(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        RoutePositionSnapshot snapshot = service.positions("190").orElseThrow();

        assertThat(snapshot.sourcePollCycleId()).isEmpty();
        assertThat(snapshot.sourceObservedAt()).isEmpty();
        assertThat(snapshot.directions()).singleElement().satisfies(direction ->
                assertThat(direction.buses()).isEmpty());
    }

    @Test
    void olderCoexistenceEvidencePreventsLatestCycleIdentityCollapse() {
        UUID olderCycle = UUID.fromString("10000000-0000-0000-0000-000000000021");
        UUID newestCycle = UUID.fromString("10000000-0000-0000-0000-000000000022");
        UUID sharedOlderSnapshot = UUID.fromString("30000000-0000-0000-0000-000000000001");
        List<RoutePositionReading> readings = List.of(
                identityReading("older-a", sharedOlderSnapshot, olderCycle, "A", 1,
                        1.3000, 103.8000, NOW.minusSeconds(50)),
                identityReading("older-b", sharedOlderSnapshot, olderCycle, "A", 2,
                        1.3000, 103.8001, NOW.minusSeconds(50)),
                identityReading("newest-a", UUID.fromString("30000000-0000-0000-0000-000000000002"),
                        newestCycle, "A", 1, 1.3000, 103.8000, NOW.minusSeconds(3)),
                identityReading("newest-b", UUID.fromString("30000000-0000-0000-0000-000000000003"),
                        newestCycle, "B", 1, 1.3000, 103.8001, NOW.minusSeconds(2)));
        RoutePositionRepository repository = (serviceId, from, to) -> List.of(
                new RoutePositionRoute("SMRT", 1, 10_000, false, 10_000, newestCycle, readings));
        RoutePositionService service = new RoutePositionService(repository, new RecordingSettings(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        RoutePositionSnapshot snapshot = service.positions("190").orElseThrow();

        assertThat(snapshot.sourcePollCycleId()).contains(newestCycle);
        assertThat(snapshot.directions()).singleElement().satisfies(direction ->
                assertThat(direction.buses()).hasSize(2));
    }

    private static RoutePositionReading reading(String key, UUID pollCycleId, Instant fetchedAt) {
        UUID observationId = UUID.nameUUIDFromBytes((key + "-observation").getBytes(StandardCharsets.UTF_8));
        UUID snapshotId = UUID.nameUUIDFromBytes((key + "-snapshot").getBytes(StandardCharsets.UTF_8));
        return new RoutePositionReading(observationId, snapshotId, pollCycleId, "01019", 1, 1,
                1_000, 1_500, fetchedAt.plusSeconds(60), fetchedAt);
    }

    private static RoutePositionReading identityReading(String key, UUID snapshotId, UUID pollCycleId,
            String stopCode, int visitOrder, double latitude, double longitude, Instant fetchedAt) {
        UUID observationId = UUID.nameUUIDFromBytes((key + "-observation").getBytes(StandardCharsets.UTF_8));
        return new RoutePositionReading(observationId, snapshotId, pollCycleId, stopCode, 1, visitOrder,
                1_000, 1_500, latitude, longitude, "ORIGIN", "DESTINATION", 1,
                List.of(1_500.0), List.of(new RoutePositionProjection(1, 1_000, 1_500, 5)),
                fetchedAt.plusSeconds(120), fetchedAt);
    }

    private static final class RecordingSettings implements RoutePositionSettings {
        private final List<String> checkedIdentityLinkScopes = new ArrayList<>();
        private final List<String> checkedBoundaryScopes = new ArrayList<>();
        private final List<String> checkedCircularPhaseScopes = new ArrayList<>();

        @Override public Duration pollInterval() { return Duration.ofSeconds(60); }
        @Override public int maxPollAgeCycles() { return 2; }
        @Override public double clusterToleranceMeters() { return 150; }
        @Override public double speedCapMetersPerSecond() { return 16.7; }
        @Override public double uncertaintyMeters() { return 50; }
        @Override public double circularProjectionSpeedMetersPerSecond() { return 7; }
        @Override public int circularProjectionMinimumStopCount() { return 3; }
        @Override public double identityLinkDistanceMeters() { return 25; }
        @Override public double identityLinkProjectionSpeedMetersPerSecond() { return 7; }
        @Override public Duration identityEtaTolerance() { return Duration.ofSeconds(20); }
        @Override public boolean isIdentityLinkEnabled(String serviceId, int directionId) {
            checkedIdentityLinkScopes.add(serviceId + ":" + directionId);
            return serviceId.equals("190");
        }
        @Override public boolean isCoordinatedRefreshBoundaryEnabled(String serviceId, int directionId) {
            checkedBoundaryScopes.add(serviceId + ":" + directionId);
            return serviceId.equals("190") && directionId == 1;
        }
        @Override public double refreshBoundaryDistanceMeters() { return 250; }
        @Override public Duration refreshBoundaryWindow() { return Duration.ofSeconds(5); }
        @Override public Duration refreshBoundaryAllowedOverlap() { return Duration.ofMillis(250); }
        @Override public Duration refreshBoundaryProfileResidual() { return Duration.ofSeconds(75); }
        @Override public double refreshBoundaryProfileMinimumSecondsPerMeter() { return 0.02; }
        @Override public double refreshBoundaryProfileMaximumSecondsPerMeter() { return 2; }
        @Override public double circularPhaseLinkDistanceMeters() { return 250; }
        @Override public double circularPhaseMaximumGapMeters() { return 2_000; }
        @Override public Duration circularPhaseMaximumEtaError() { return Duration.ofSeconds(75); }
        @Override public boolean isCircularPhaseLinkEnabled(String serviceId, int directionId) {
            checkedCircularPhaseScopes.add(serviceId + ":" + directionId);
            return serviceId.equals("972") && directionId == 1;
        }
    }
}
