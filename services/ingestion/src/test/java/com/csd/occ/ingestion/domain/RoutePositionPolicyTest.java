package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoutePositionPolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-23T04:00:00Z");
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(60);
    private static final UUID CURRENT_CYCLE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final RoutePositionPolicy.Parameters PARAMETERS = new RoutePositionPolicy.Parameters(
            POLL_INTERVAL, 2, 150, 16.7, 50, 7, 3);

    @Test
    void exactCoordinateAcrossStopsRepresentsOneBusDespiteVisitOrderChanges() {
        RoutePositionReading first = reading(snapshot("first"), CURRENT_CYCLE, "A", 1, 3,
                1_200, 1.30001, 103.80001, "ORIGIN", "DESTINATION", 1, 300, 6);
        RoutePositionReading second = reading(snapshot("second"), CURRENT_CYCLE, "B", 2, 1,
                1_200, 1.30001, 103.80001, "ORIGIN", "DESTINATION", 1, 240, 5);

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(List.of(first, second));

        assertThat(estimate.buses()).singleElement().satisfies(bus -> {
            assertThat(bus.distanceAlongRouteM()).isEqualTo(1_200);
            assertThat(bus.uncertaintyM()).isEqualTo(50);
            assertThat(bus.speedMps()).isNull();
            assertThat(bus.supportingStopCount()).isEqualTo(2);
            assertThat(bus.evidenceObservationIds()).containsExactlyInAnyOrder(
                    first.observationId(), second.observationId());
        });
        assertThat(estimate.suppressedBusCount()).isZero();
    }

    @Test
    void coLocatedBusesInOneSnapshotRemainDistinctAcrossSupportingStops() {
        UUID firstSnapshot = snapshot("multiplicity-first");
        UUID secondSnapshot = snapshot("multiplicity-second");
        List<RoutePositionReading> readings = List.of(
                reading(firstSnapshot, CURRENT_CYCLE, "A", 1, 1,
                        2_000, 1.31, 103.81, "ORIGIN", "DESTINATION", 1, 90, 6),
                reading(firstSnapshot, CURRENT_CYCLE, "A", 1, 2,
                        2_000, 1.31, 103.81, "ORIGIN", "DESTINATION", 1, 300, 6),
                reading(secondSnapshot, CURRENT_CYCLE, "B", 2, 9,
                        2_000, 1.31, 103.81, "ORIGIN", "DESTINATION", 1, 60, 5),
                reading(secondSnapshot, CURRENT_CYCLE, "B", 2, 4,
                        2_000, 1.31, 103.81, "ORIGIN", "DESTINATION", 1, 270, 5));

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(readings);

        assertThat(estimate.buses()).hasSize(2);
        assertThat(estimate.buses()).allSatisfy(bus -> {
            assertThat(bus.distanceAlongRouteM()).isEqualTo(2_000);
            assertThat(bus.supportingStopCount()).isEqualTo(2);
            assertThat(bus.evidenceObservationIds()).hasSize(2);
        });
        assertThat(estimate.suppressedBusCount()).isZero();
    }

    @Test
    void coordinatesRemainDistinctEvenWhenTheirDisplayProjectionMatches() {
        List<RoutePositionReading> readings = List.of(
                reading(snapshot("coordinate-one"), CURRENT_CYCLE, "A", 1, 1,
                        3_000, 1.32, 103.82, "ORIGIN", "DESTINATION", 1, 120, 5),
                reading(snapshot("coordinate-two"), CURRENT_CYCLE, "B", 2, 1,
                        3_000, 1.33, 103.83, "ORIGIN", "DESTINATION", 1, 120, 5));

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(readings);

        assertThat(estimate.buses()).hasSize(2);
        assertThat(estimate.buses()).extracting(RoutePositionBus::distanceAlongRouteM).containsOnly(3_000.0);
        assertThat(estimate.buses()).allSatisfy(bus -> assertThat(bus.evidenceObservationIds()).hasSize(1));
    }

    @Test
    void originDestinationAndVisitNumberScopeCoordinateIdentity() {
        List<RoutePositionReading> readings = List.of(
                reading(snapshot("identity-base"), CURRENT_CYCLE, "A", 1, 1,
                        4_000, 1.34, 103.84, "O1", "D1", 1, 120, 5),
                reading(snapshot("identity-origin"), CURRENT_CYCLE, "B", 2, 1,
                        4_000, 1.34, 103.84, "O2", "D1", 1, 120, 5),
                reading(snapshot("identity-destination"), CURRENT_CYCLE, "C", 3, 1,
                        4_000, 1.34, 103.84, "O1", "D2", 1, 120, 5),
                reading(snapshot("identity-visit"), CURRENT_CYCLE, "D", 4, 1,
                        4_000, 1.34, 103.84, "O1", "D1", 2, 120, 5));

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(readings);

        assertThat(estimate.buses()).hasSize(4);
        assertThat(estimate.buses()).allSatisfy(bus -> {
            assertThat(bus.supportingStopCount()).isEqualTo(1);
            assertThat(bus.evidenceObservationIds()).hasSize(1);
        });
    }

    @Test
    void singletonCoordinateRemainsVisible() {
        RoutePositionReading singleton = reading(snapshot("singleton"), CURRENT_CYCLE, "A", 1, 1,
                5_000, 1.35, 103.85, "ORIGIN", "DESTINATION", 1, 120, 5);

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(List.of(singleton));

        assertThat(estimate.buses()).singleElement().satisfies(bus -> {
            assertThat(bus.evidenceObservationIds()).containsExactly(singleton.observationId());
            assertThat(bus.supportingStopCount()).isEqualTo(1);
        });
        assertThat(estimate.suppressedBusCount()).isZero();
    }

    @Test
    void onlyNewestPollCycleContributesBuses() {
        UUID olderCycle = UUID.fromString("00000000-0000-0000-0000-000000000002");
        RoutePositionReading older = reading(snapshot("older"), olderCycle, "A", 1, 1,
                1_000, 1.36, 103.86, "ORIGIN", "DESTINATION", 1, 300, 50);
        RoutePositionReading newest = reading(snapshot("newest"), CURRENT_CYCLE, "B", 2, 1,
                6_000, 1.37, 103.87, "ORIGIN", "DESTINATION", 1, 300, 5);

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(List.of(older, newest));

        assertThat(estimate.buses()).singleElement().satisfies(bus -> {
            assertThat(bus.distanceAlongRouteM()).isEqualTo(6_000);
            assertThat(bus.evidenceObservationIds()).containsExactly(newest.observationId());
        });
        assertThat(estimate.suppressedBusCount()).isZero();
    }

    @Test
    void explicitSelectedPollCycleIsDisplayedWhileNewerHistoryOnlySuppliesSafetyEvidence() {
        UUID selectedCycle = UUID.fromString("00000000-0000-0000-0000-000000000002");
        RoutePositionReading selected = reading(snapshot("explicit-selected"), selectedCycle, "A", 1, 1,
                1_000, 1.36, 103.86, "ORIGIN", "DESTINATION", 1, 300, 10);
        RoutePositionReading newerHistory = reading(snapshot("explicit-newer-history"), CURRENT_CYCLE,
                "B", 2, 1, 6_000, 1.37, 103.87, "ORIGIN", "DESTINATION", 1, 300, 5);

        RoutePositionPolicy.RoutePositionEstimate estimate = RoutePositionPolicy.estimateWithConfidence(
                List.of(selected, newerHistory), 10_000, false, 10_000, NOW, PARAMETERS,
                selectedCycle, true, false, false);

        assertThat(estimate.buses()).singleElement().satisfies(bus -> {
            assertThat(bus.distanceAlongRouteM()).isEqualTo(1_000);
            assertThat(bus.evidenceObservationIds()).containsExactly(selected.observationId());
            assertThat(bus.positionEvidenceObservationIds()).containsExactly(selected.observationId());
        });
    }

    @Test
    void explicitSelectedPollCycleWithoutReadingsReturnsNoBuses() {
        UUID emptySelectedCycle = UUID.fromString("00000000-0000-0000-0000-000000000003");
        RoutePositionReading history = reading(snapshot("explicit-empty-history"), CURRENT_CYCLE,
                "A", 1, 1, 1_000, 1.36, 103.86, "ORIGIN", "DESTINATION", 1, 300, 5);

        RoutePositionPolicy.RoutePositionEstimate estimate = RoutePositionPolicy.estimateWithConfidence(
                List.of(history), 10_000, false, 10_000, NOW, PARAMETERS,
                emptySelectedCycle, true, false, false);

        assertThat(estimate.buses()).isEmpty();
        assertThat(estimate.suppressedBusCount()).isZero();
    }

    @Test
    void circularEtaEvidenceSelectsLaterCoordinateOccurrence() {
        List<RoutePositionProjection> occurrences = List.of(
                new RoutePositionProjection(2, 900, 1_000, 5),
                new RoutePositionProjection(9, 8_000, 9_000, 15));
        List<RoutePositionReading> readings = List.of(
                circularReading("circular-a", "A", 1, 71, List.of(9_500.0), occurrences),
                circularReading("circular-b", "B", 2, 114, List.of(9_800.0), occurrences),
                circularReading("circular-terminal", "T", 10, 143, List.of(0.0, 10_000.0), occurrences));

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(readings, true);

        assertThat(estimate.buses()).singleElement().satisfies(bus -> {
            assertThat(bus.distanceAlongRouteM()).isEqualTo(8_000);
            assertThat(bus.supportingStopCount()).isEqualTo(3);
        });
    }

    @Test
    void circularSelectionFallsBackWithoutEnoughDistinctStops() {
        List<RoutePositionProjection> occurrences = List.of(
                new RoutePositionProjection(2, 900, 1_000, 5),
                new RoutePositionProjection(9, 8_000, 9_000, 15));
        List<RoutePositionReading> readings = List.of(
                circularReading("fallback-a", "A", 1, 71, List.of(9_500.0), occurrences),
                circularReading("fallback-b", "B", 2, 114, List.of(9_800.0), occurrences));

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(readings, true);

        assertThat(estimate.buses()).singleElement()
                .extracting(RoutePositionBus::distanceAlongRouteM).isEqualTo(1_111.0);
    }

    @Test
    void openRouteKeepsGlobalProjectionDespiteCircularCandidateEvidence() {
        List<RoutePositionProjection> occurrences = List.of(
                new RoutePositionProjection(2, 900, 1_000, 5),
                new RoutePositionProjection(9, 8_000, 9_000, 15));
        List<RoutePositionReading> readings = List.of(
                circularReading("open-a", "A", 1, 71, List.of(9_500.0), occurrences),
                circularReading("open-b", "B", 2, 114, List.of(9_800.0), occurrences),
                circularReading("open-terminal", "T", 10, 143, List.of(0.0, 10_000.0), occurrences));

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(readings, false);

        assertThat(estimate.buses()).singleElement()
                .extracting(RoutePositionBus::distanceAlongRouteM).isEqualTo(1_111.0);
    }

    @Test
    void allLatestCycleCoordinatesAreReturnedWithoutSuppression() {
        List<RoutePositionReading> readings = List.of(
                reading(snapshot("visible-one"), CURRENT_CYCLE, "A", 1, 1,
                        1_000, 1.21, 103.71, "ORIGIN", "DESTINATION", 1, 120, 5),
                reading(snapshot("visible-two"), CURRENT_CYCLE, "A", 1, 2,
                        2_000, 1.22, 103.72, "ORIGIN", "DESTINATION", 1, 240, 5),
                reading(snapshot("visible-three"), CURRENT_CYCLE, "A", 1, 3,
                        3_000, 1.23, 103.73, "ORIGIN", "DESTINATION", 1, 360, 5));

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(readings);

        assertThat(estimate.buses()).hasSize(3);
        assertThat(estimate.suppressedBusCount()).isZero();
    }

    @Test
    void nearbyEtaCompatibleCoordinatesAcrossSnapshotsRepresentOneOpenRouteBus() {
        RoutePositionReading olderCoordinate = reading(snapshot("short-link-older"), CURRENT_CYCLE,
                "A", 1, 1, 1_000, 1.30000, 103.80000,
                "ORIGIN", "DESTINATION", 1, 200, 6);
        RoutePositionReading fresherCoordinate = reading(snapshot("short-link-fresher"), CURRENT_CYCLE,
                "B", 9, 1, 1_200, 1.30000, 103.80010,
                " origin ", "destination", 1, 342, 5, List.of(2_000.0),
                List.of(new RoutePositionProjection(2, 1_200, 1_200, 0)));

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(List.of(olderCoordinate, fresherCoordinate));

        assertThat(estimate.buses()).singleElement().satisfies(bus -> {
            assertThat(bus.distanceAlongRouteM()).isEqualTo(1_200);
            assertThat(bus.evidenceObservationIds()).containsExactlyInAnyOrder(
                    olderCoordinate.observationId(), fresherCoordinate.observationId());
            assertThat(bus.supportingStopCount()).isEqualTo(2);
        });
    }

    @Test
    void openRouteIdentityLinkageUsesItsDedicatedProjectionSpeed() {
        RoutePositionReading olderCoordinate = reading(snapshot("dedicated-speed-older"), CURRENT_CYCLE,
                "A", 1, 1, 1_000, 1.30000, 103.80000,
                "ORIGIN", "DESTINATION", 1, 200, 6);
        RoutePositionReading fresherCoordinate = reading(snapshot("dedicated-speed-fresher"), CURRENT_CYCLE,
                "B", 9, 1, 1_200, 1.30000, 103.80010,
                "ORIGIN", "DESTINATION", 1, 342, 5, List.of(2_000.0),
                List.of(new RoutePositionProjection(2, 1_200, 1_200, 0)));
        RoutePositionPolicy.Parameters parameters = new RoutePositionPolicy.Parameters(
                POLL_INTERVAL, 2, 150, 16.7, 50, 1, 3,
                25, 7, Duration.ofSeconds(20), 250, Duration.ofSeconds(5),
                Duration.ofMillis(250), Duration.ofSeconds(75), 0.02, 2, 250, 2_000,
                Duration.ofSeconds(75));

        RoutePositionPolicy.RoutePositionEstimate estimate = RoutePositionPolicy.estimateWithConfidence(
                List.of(olderCoordinate, fresherCoordinate), 10_000, false, 10_000, NOW, parameters,
                true, false, false);

        assertThat(estimate.buses()).hasSize(1);
    }

    @Test
    void auditEvidenceIsNotSilentlyTruncated() {
        List<RoutePositionReading> readings = new ArrayList<>();
        for (int index = 0; index < 70; index++) {
            readings.add(reading(snapshot("complete-evidence-" + index), CURRENT_CYCLE,
                    "S" + index, index + 1, 1, 1_000, 1.30000, 103.80000,
                    "ORIGIN", "DESTINATION", 1, 300, 5));
        }

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(readings);

        assertThat(estimate.buses()).singleElement().satisfies(bus -> {
            assertThat(bus.evidenceObservationIds()).hasSize(70);
            assertThat(bus.supportingStopCount()).isEqualTo(70);
        });
    }

    @Test
    void routeScopeCanDisableShortRangeIdentityLinkage() {
        RoutePositionReading first = reading(snapshot("disabled-short-link-a"), CURRENT_CYCLE,
                "A", 1, 1, 1_000, 1.30000, 103.80000,
                "ORIGIN", "DESTINATION", 1, 200, 6);
        RoutePositionReading second = reading(snapshot("disabled-short-link-b"), CURRENT_CYCLE,
                "B", 9, 1, 1_200, 1.30000, 103.80010,
                "ORIGIN", "DESTINATION", 1, 342, 5, List.of(2_000.0),
                List.of(new RoutePositionProjection(2, 1_200, 1_200, 0)));

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(
                List.of(first, second), false, false, false, true);

        assertThat(estimate.buses()).hasSize(2);
    }

    @Test
    void coObservedCoordinatesOnlyTwoPointEightMetersApartRemainDistinct() {
        UUID sharedSnapshot = snapshot("coobserved-2.8m");
        RoutePositionReading first = reading(sharedSnapshot, CURRENT_CYCLE, "05069", 21, 2,
                17_983.290, 1.2867897, 103.8457157, "44009", "10499", 1, 300, 5);
        RoutePositionReading second = reading(sharedSnapshot, CURRENT_CYCLE, "05069", 21, 3,
                17_986.069, 1.2867720, 103.8456978, "44009", "10499", 1, 311, 5);

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(
                List.of(first, second), false, false, 22_900);

        assertThat(estimate.buses()).hasSize(2);
        assertThat(estimate.buses()).extracting(RoutePositionBus::distanceAlongRouteM)
                .containsExactlyInAnyOrder(17_983.290, 17_986.069);
        assertThat(estimate.buses()).allSatisfy(bus -> assertThat(bus.evidenceObservationIds()).hasSize(1));
    }

    @Test
    void olderCycleCoexistenceVetoesLatestCycleShortRangeLinkage() {
        UUID olderCycle = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID olderSharedSnapshot = snapshot("historical-coexistence");
        List<RoutePositionReading> readings = List.of(
                reading(olderSharedSnapshot, olderCycle, "OLD", 1, 1,
                        1_000, 1.3000, 103.8000, "ORIGIN", "DESTINATION", 1, 200, 30),
                reading(olderSharedSnapshot, olderCycle, "OLD", 1, 2,
                        1_020, 1.3000, 103.8001, "ORIGIN", "DESTINATION", 1, 200, 30),
                reading(snapshot("historical-latest-a"), CURRENT_CYCLE, "A", 1, 1,
                        1_000, 1.3000, 103.8000, "ORIGIN", "DESTINATION", 1, 200, 6),
                reading(snapshot("historical-latest-b"), CURRENT_CYCLE, "B", 9, 1,
                        1_020, 1.3000, 103.8001, "ORIGIN", "DESTINATION", 1, 342, 5,
                        List.of(2_000.0), List.of(new RoutePositionProjection(2, 1_020, 1_020, 0))));

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(readings);

        assertThat(estimate.buses()).hasSize(2);
        assertThat(estimate.buses()).extracting(RoutePositionBus::distanceAlongRouteM)
                .containsExactlyInAnyOrder(1_000.0, 1_020.0);
        assertThat(estimate.buses()).allSatisfy(bus -> {
            assertThat(bus.evidenceObservationIds()).hasSize(1);
            assertThat(bus.positionEvidenceObservationIds()).isEqualTo(bus.evidenceObservationIds());
        });
    }

    @Test
    void shortRangeLinkagePreservesSameSnapshotMultiplicity() {
        UUID olderSnapshot = snapshot("multiplicity-short-link-older");
        UUID fresherSnapshot = snapshot("multiplicity-short-link-fresher");
        List<RoutePositionReading> readings = List.of(
                reading(olderSnapshot, CURRENT_CYCLE, "A", 1, 1,
                        1_000, 1.30000, 103.80000, "ORIGIN", "DESTINATION", 1, 200, 6),
                reading(olderSnapshot, CURRENT_CYCLE, "A", 1, 2,
                        1_000, 1.30000, 103.80000, "ORIGIN", "DESTINATION", 1, 200, 6),
                reading(fresherSnapshot, CURRENT_CYCLE, "B", 2, 1,
                        1_200, 1.30000, 103.80010, "ORIGIN", "DESTINATION", 1, 342, 5),
                reading(fresherSnapshot, CURRENT_CYCLE, "B", 2, 2,
                        1_200, 1.30000, 103.80010, "ORIGIN", "DESTINATION", 1, 342, 5));

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(readings);

        assertThat(estimate.buses()).hasSize(2);
        assertThat(estimate.buses()).allSatisfy(bus -> {
            assertThat(bus.distanceAlongRouteM()).isEqualTo(1_200);
            assertThat(bus.evidenceObservationIds()).hasSize(2);
            assertThat(bus.supportingStopCount()).isEqualTo(2);
        });
    }

    @Test
    void shortRangeLinkageUsesCompleteLinkInsteadOfCollapsingAProximityChain() {
        List<RoutePositionReading> readings = List.of(
                reading(snapshot("complete-link-a"), CURRENT_CYCLE, "A", 1, 1,
                        1_000, 1.3000, 103.80000, "ORIGIN", "DESTINATION", 1, 200, 5),
                reading(snapshot("complete-link-b"), CURRENT_CYCLE, "B", 2, 1,
                        1_020, 1.3000, 103.80015, "ORIGIN", "DESTINATION", 1, 343, 5),
                reading(snapshot("complete-link-c"), CURRENT_CYCLE, "C", 3, 1,
                        1_040, 1.3000, 103.80030, "ORIGIN", "DESTINATION", 1, 486, 5));

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(readings);

        assertThat(estimate.buses()).hasSize(2);
        assertThat(estimate.buses()).extracting(bus -> bus.evidenceObservationIds().size())
                .containsExactlyInAnyOrder(2, 1);
    }

    @Test
    void screenshotRegressionRemovesOnlyTwoRefreshBoundaryDuplicates() {
        ScreenshotReadings screenshot = screenshotReadings();

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(
                screenshot.readings(), false, true, 22_900);

        assertThat(estimate.buses()).hasSize(11);
        assertThat(estimate.suppressedBusCount()).isZero();
        assertThat(estimate.buses().stream()
                .filter(bus -> bus.evidenceObservationIds().contains(screenshot.firstNewObservationId()))
                .toList()).singleElement().satisfies(bus -> {
                    assertThat(bus.distanceAlongRouteM()).isEqualTo(15_021.624);
                    assertThat(bus.evidenceObservationIds()).hasSize(9);
                    assertThat(bus.positionEvidenceObservationIds())
                            .containsExactly(screenshot.firstNewObservationId());
                });
        assertThat(estimate.buses().stream()
                .filter(bus -> bus.evidenceObservationIds().contains(screenshot.secondNewObservationId()))
                .toList()).singleElement().satisfies(bus -> {
                    assertThat(bus.distanceAlongRouteM()).isEqualTo(16_500.241);
                    assertThat(bus.evidenceObservationIds()).hasSize(6);
                    assertThat(bus.positionEvidenceObservationIds())
                            .containsExactly(screenshot.secondNewObservationId());
                });
        assertThat(estimate.buses()).filteredOn(bus -> bus.distanceAlongRouteM() == 17_983.290
                || bus.distanceAlongRouteM() == 17_986.069).hasSize(2);
    }

    @Test
    void directionWithoutBoundaryMatchingKeepsAllScreenshotCoordinates() {
        ScreenshotReadings screenshot = screenshotReadings();

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(
                screenshot.readings(), false, false, 22_900);

        assertThat(estimate.buses()).hasSize(13);
        assertThat(estimate.buses().stream()
                .mapToLong(bus -> Math.round(bus.distanceAlongRouteM() * 1_000))
                .toArray()).contains(14_823_963, 15_021_624, 16_460_561,
                        16_500_241, 17_983_290, 17_986_069);
    }

    @Test
    void olderCycleCoexistenceVetoesCoordinatedBoundaryMatches() {
        ScreenshotReadings screenshot = screenshotReadings();
        List<RoutePositionReading> readings = new ArrayList<>(screenshot.readings());
        UUID olderCycle = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID firstPairSnapshot = snapshot("historical-boundary-a");
        readings.add(reading(firstPairSnapshot, olderCycle, "OLD-A", 1, 1,
                14_823.963, 1.3031082, 103.8352592, "44009", "10499", 1, 200, 30));
        readings.add(reading(firstPairSnapshot, olderCycle, "OLD-A", 1, 2,
                15_021.624, 1.3023162, 103.8368518, "44009", "10499", 1, 200, 30));
        UUID secondPairSnapshot = snapshot("historical-boundary-b");
        readings.add(reading(secondPairSnapshot, olderCycle, "OLD-B", 1, 1,
                16_460.561, 1.2982135, 103.8491268, "44009", "10499", 1, 200, 30));
        readings.add(reading(secondPairSnapshot, olderCycle, "OLD-B", 1, 2,
                16_500.241, 1.2980455, 103.8493220, "44009", "10499", 1, 200, 30));

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(readings, false, true, 22_900);

        assertThat(estimate.buses()).hasSize(13);
        assertThat(estimate.buses()).filteredOn(bus -> bus.evidenceObservationIds()
                .contains(screenshot.firstNewObservationId())).singleElement().satisfies(bus -> {
                    assertThat(bus.evidenceObservationIds()).hasSize(1);
                    assertThat(bus.positionEvidenceObservationIds())
                            .containsExactly(screenshot.firstNewObservationId());
                });
        assertThat(estimate.buses()).filteredOn(bus -> bus.evidenceObservationIds()
                .contains(screenshot.secondNewObservationId())).singleElement().satisfies(bus -> {
                    assertThat(bus.evidenceObservationIds()).hasSize(1);
                    assertThat(bus.positionEvidenceObservationIds())
                            .containsExactly(screenshot.secondNewObservationId());
                });
    }

    @Test
    void ambiguousBoundaryCandidateDoesNotTriggerAnUncoordinatedMerge() {
        List<RoutePositionReading> readings = new ArrayList<>();
        readings.addAll(profileGroup("ambiguous-old-a", 1.3000, 103.8000, 1_000,
                List.of(1_000.0, 2_000.0, 3_000.0), 5_000));
        readings.addAll(profileGroup("ambiguous-old-b", 1.3000, 103.8020, 1_200,
                List.of(1_000.0, 2_000.0, 3_000.0), 5_000));
        UUID boundarySnapshot = snapshot("ambiguous-boundary");
        readings.add(profileReading(boundarySnapshot, "NEW-A", 1, 1_100,
                1.3000, 103.8010, 4_000, 2_000));
        readings.add(profileReading(boundarySnapshot, "NEW-B", 2, 900,
                1.3000, 103.7990, 4_000, 2_000));

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(readings, false, true);

        assertThat(estimate.buses()).hasSize(4);
        assertThat(estimate.buses()).allSatisfy(bus -> assertThat(bus.evidenceObservationIds()).hasSizeLessThanOrEqualTo(3));
    }

    @Test
    void circularPhaseCompatibleEvidenceCorrectsOccurrencesWithoutCollapsingMarkers() {
        List<RoutePositionProjection> occurrences = List.of(
                new RoutePositionProjection(2, 900, 1_000, 5),
                new RoutePositionProjection(9, 8_000, 9_000, 15));
        List<RoutePositionReading> readings = List.of(
                circularPhaseReading("phase-a-1", "A", 1, 1_111,
                        1.3000, 103.8000, 71, List.of(9_500.0), occurrences),
                circularPhaseReading("phase-a-2", "B", 2, 1_111,
                        1.3000, 103.8000, 114, List.of(9_800.0), occurrences),
                circularPhaseReading("phase-b", "T", 10, 2_222,
                        1.3005, 103.8005, 143, List.of(0.0, 10_000.0), occurrences));

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(readings, true);

        assertThat(estimate.buses()).hasSize(2);
        assertThat(estimate.buses()).extracting(RoutePositionBus::distanceAlongRouteM).containsOnly(8_000.0);
        assertThat(estimate.buses()).allSatisfy(bus -> assertThat(bus.evidenceObservationIds()).hasSize(3));
        assertThat(estimate.buses()).extracting(RoutePositionBus::positionEvidenceObservationIds)
                .anySatisfy(ids -> assertThat(ids).hasSize(2))
                .anySatisfy(ids -> assertThat(ids).hasSize(1));
        assertThat(estimate.buses()).extracting(RoutePositionBus::supportingStopCount)
                .containsExactlyInAnyOrder(2, 1);
    }

    @Test
    void routeScopeCanDisableCircularPhaseEvidenceSharing() {
        List<RoutePositionProjection> occurrences = List.of(
                new RoutePositionProjection(2, 900, 1_000, 5),
                new RoutePositionProjection(9, 8_000, 9_000, 15));
        List<RoutePositionReading> readings = List.of(
                circularPhaseReading("disabled-phase-a-1", "A", 1, 1_111,
                        1.3000, 103.8000, 71, List.of(9_500.0), occurrences),
                circularPhaseReading("disabled-phase-a-2", "B", 2, 1_111,
                        1.3000, 103.8000, 114, List.of(9_800.0), occurrences),
                circularPhaseReading("disabled-phase-b", "T", 10, 2_222,
                        1.3005, 103.8005, 143, List.of(0.0, 10_000.0), occurrences));

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(
                readings, true, true, false, false);

        assertThat(estimate.buses()).hasSize(2);
        assertThat(estimate.buses()).extracting(RoutePositionBus::distanceAlongRouteM)
                .containsExactlyInAnyOrder(1_111.0, 2_222.0);
    }

    @Test
    void olderCycleCoexistenceVetoesCircularEvidenceSharing() {
        UUID olderCycle = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID olderSharedSnapshot = snapshot("historical-circular-coexistence");
        List<RoutePositionProjection> occurrences = List.of(
                new RoutePositionProjection(2, 900, 1_000, 5),
                new RoutePositionProjection(9, 8_000, 9_000, 15));
        List<RoutePositionReading> readings = List.of(
                reading(olderSharedSnapshot, olderCycle, "OLD", 1, 1,
                        1_111, 1.3000, 103.8000, "ORIGIN", "DESTINATION", 1, 200, 30),
                reading(olderSharedSnapshot, olderCycle, "OLD", 1, 2,
                        2_222, 1.3005, 103.8005, "ORIGIN", "DESTINATION", 1, 200, 30),
                circularPhaseReading("historical-phase-a-1", "A", 1, 1_111,
                        1.3000, 103.8000, 71, List.of(9_500.0), occurrences),
                circularPhaseReading("historical-phase-a-2", "B", 2, 1_111,
                        1.3000, 103.8000, 114, List.of(9_800.0), occurrences),
                circularPhaseReading("historical-phase-b", "T", 10, 2_222,
                        1.3005, 103.8005, 143, List.of(0.0, 10_000.0), occurrences));

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(readings, true);

        assertThat(estimate.buses()).hasSize(2);
        assertThat(estimate.buses()).extracting(RoutePositionBus::distanceAlongRouteM)
                .containsExactlyInAnyOrder(1_111.0, 2_222.0);
        assertThat(estimate.buses()).extracting(RoutePositionBus::evidenceObservationIds)
                .anySatisfy(ids -> assertThat(ids).hasSize(2))
                .anySatisfy(ids -> assertThat(ids).hasSize(1));
    }

    @Test
    void nearbyCircularCoordinatesAtOppositePhasesDoNotShareEvidence() {
        List<RoutePositionProjection> firstOccurrence = List.of(
                new RoutePositionProjection(2, 1_000, 1_000, 5));
        List<RoutePositionProjection> secondOccurrence = List.of(
                new RoutePositionProjection(7, 4_000, 4_000, 5));
        List<RoutePositionReading> readings = List.of(
                circularPhaseReading("opposite-a-1", "A", 1, 1_111,
                        1.3000, 103.8000, 71, List.of(1_500.0, 4_500.0), firstOccurrence),
                circularPhaseReading("opposite-a-2", "B", 2, 1_111,
                        1.3000, 103.8000, 114, List.of(1_800.0, 4_800.0), firstOccurrence),
                circularPhaseReading("opposite-b", "T", 3, 2_222,
                        1.3005, 103.8005, 143, List.of(2_000.0, 5_000.0), secondOccurrence));

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(readings, true);

        assertThat(estimate.buses()).hasSize(2);
        assertThat(estimate.buses()).extracting(RoutePositionBus::distanceAlongRouteM)
                .containsExactlyInAnyOrder(1_111.0, 2_222.0);
        assertThat(estimate.buses()).extracting(RoutePositionBus::evidenceObservationIds)
                .anySatisfy(ids -> assertThat(ids).hasSize(2))
                .anySatisfy(ids -> assertThat(ids).hasSize(1));
    }

    @Test
    void ambiguousCircularPhaseLinksDoNotShareEvidence() {
        List<RoutePositionProjection> occurrences = List.of(
                new RoutePositionProjection(2, 900, 1_000, 5),
                new RoutePositionProjection(9, 8_000, 9_000, 15));
        List<RoutePositionReading> readings = List.of(
                circularPhaseReading("ambiguous-phase-a-1", "A1", 1, 1_111,
                        1.3000, 103.8000, 71, List.of(9_500.0), occurrences),
                circularPhaseReading("ambiguous-phase-a-2", "A2", 2, 1_111,
                        1.3000, 103.8000, 86, List.of(9_600.0), occurrences),
                circularPhaseReading("ambiguous-phase-b-1", "B1", 3, 2_222,
                        1.3005, 103.8005, 100, List.of(9_700.0), occurrences),
                circularPhaseReading("ambiguous-phase-b-2", "B2", 4, 2_222,
                        1.3005, 103.8005, 114, List.of(9_800.0), occurrences),
                circularPhaseReading("ambiguous-phase-c-1", "C1", 5, 3_333,
                        1.3010, 103.8010, 129, List.of(9_900.0), occurrences),
                circularPhaseReading("ambiguous-phase-c-2", "C2", 6, 3_333,
                        1.3010, 103.8010, 143, List.of(0.0, 10_000.0), occurrences));

        RoutePositionPolicy.RoutePositionEstimate estimate = estimate(readings, true);

        assertThat(estimate.buses()).hasSize(3);
        assertThat(estimate.buses()).extracting(RoutePositionBus::distanceAlongRouteM)
                .containsExactlyInAnyOrder(1_111.0, 2_222.0, 3_333.0);
        assertThat(estimate.buses()).allSatisfy(bus -> {
            assertThat(bus.evidenceObservationIds()).hasSize(2);
            assertThat(bus.supportingStopCount()).isEqualTo(2);
        });
    }

    private RoutePositionPolicy.RoutePositionEstimate estimate(List<RoutePositionReading> readings) {
        return estimate(readings, false);
    }

    private RoutePositionPolicy.RoutePositionEstimate estimate(List<RoutePositionReading> readings, boolean circular) {
        return estimate(readings, circular, false);
    }

    private RoutePositionPolicy.RoutePositionEstimate estimate(List<RoutePositionReading> readings, boolean circular,
            boolean coordinatedRefreshBoundaryEnabled) {
        return estimate(readings, circular, coordinatedRefreshBoundaryEnabled, 10_000);
    }

    private RoutePositionPolicy.RoutePositionEstimate estimate(List<RoutePositionReading> readings, boolean circular,
            boolean coordinatedRefreshBoundaryEnabled, double routeLengthMeters) {
        return RoutePositionPolicy.estimateWithConfidence(
                readings, routeLengthMeters, circular, routeLengthMeters, NOW, PARAMETERS,
                coordinatedRefreshBoundaryEnabled);
    }

    private RoutePositionPolicy.RoutePositionEstimate estimate(List<RoutePositionReading> readings, boolean circular,
            boolean identityLinkEnabled, boolean coordinatedRefreshBoundaryEnabled,
            boolean circularPhaseLinkEnabled) {
        return RoutePositionPolicy.estimateWithConfidence(
                readings, 10_000, circular, 10_000, NOW, PARAMETERS,
                identityLinkEnabled, coordinatedRefreshBoundaryEnabled, circularPhaseLinkEnabled);
    }

    private ScreenshotReadings screenshotReadings() {
        List<RoutePositionReading> readings = new ArrayList<>();
        readings.add(reading(snapshot("screenshot-01"), CURRENT_CYCLE, "S01", 1, 1,
                141.355, 1.3847727, 103.7451353, "44009", "10499", 1, 120, 5));
        readings.add(reading(snapshot("screenshot-02"), CURRENT_CYCLE, "S02", 2, 1,
                1_650.180, 1.3776447, 103.7561827, "44009", "10499", 1, 120, 5));
        readings.add(reading(snapshot("screenshot-03"), CURRENT_CYCLE, "S03", 3, 1,
                2_933.155, 1.3802107, 103.7674768, "44009", "10499", 1, 120, 5));
        readings.add(reading(snapshot("screenshot-04"), CURRENT_CYCLE, "S04", 4, 1,
                6_502.370, 1.3543047, 103.7866573, "44009", "10499", 1, 120, 5));
        readings.add(reading(snapshot("screenshot-05"), CURRENT_CYCLE, "S05", 5, 1,
                8_493.672, 1.3440122, 103.8017940, "44009", "10499", 1, 120, 5));
        readings.add(reading(snapshot("screenshot-06"), CURRENT_CYCLE, "S06", 6, 1,
                12_247.602, 1.3212017, 103.8256983, "44009", "10499", 1, 120, 5));
        readings.add(screenshotReading("old-a-09037", "09037", 1, 14_823.963,
                1.3031082, 103.8352592, 17_400, 1_792_990_418, 1_792_990_421.885408));
        readings.add(screenshotReading("old-a-04159", "04159", 2, 14_823.963,
                1.3031082, 103.8352592, 19_500, 1_792_990_946, 1_792_990_421.614658));
        readings.add(screenshotReading("old-a-08057", "08057", 1, 14_823.963,
                1.3031082, 103.8352592, 18_500, 1_792_990_678, 1_792_990_422.207838));
        readings.add(screenshotReading("old-a-05059", "05059", 3, 14_823.963,
                1.3031082, 103.8352592, 20_600, 1_792_991_287, 1_792_990_419.534305));
        readings.add(screenshotReading("old-a-04239", "04239", 2, 14_823.963,
                1.3031082, 103.8352592, 20_300, 1_792_991_171, 1_792_990_422.387769));
        readings.add(screenshotReading("old-a-04229", "04229", 2, 14_823.963,
                1.3031082, 103.8352592, 20_100, 1_792_991_106, 1_792_990_420.734271));
        readings.add(screenshotReading("old-a-08138", "08138", 1, 14_823.963,
                1.3031082, 103.8352592, 18_100, 1_792_990_569, 1_792_990_423.161594));
        readings.add(screenshotReading("old-a-08069", "08069", 1, 14_823.963,
                1.3031082, 103.8352592, 19_000, 1_792_990_810, 1_792_990_423.816906));
        readings.add(screenshotReading("old-b-04159", "04159", 1, 16_460.561,
                1.2982135, 103.8491268, 19_500, 1_792_990_487, 1_792_990_421.614658));
        readings.add(screenshotReading("old-b-05059", "05059", 2, 16_460.561,
                1.2982135, 103.8491268, 20_600, 1_792_990_832, 1_792_990_419.534305));
        readings.add(screenshotReading("old-b-04239", "04239", 1, 16_460.561,
                1.2982135, 103.8491268, 20_300, 1_792_990_720, 1_792_990_422.387769));
        readings.add(screenshotReading("old-b-04229", "04229", 1, 16_460.561,
                1.2982135, 103.8491268, 20_100, 1_792_990_649, 1_792_990_420.734271));
        readings.add(screenshotReading("old-b-05049", "05049", 3, 16_460.561,
                1.2982135, 103.8491268, 20_800, 1_792_990_951, 1_792_990_424.647060));
        UUID boundarySnapshot = snapshot("screenshot-boundary");
        RoutePositionReading firstNew = screenshotReading(boundarySnapshot, "04149", 2, 15_021.624,
                1.3023162, 103.8368518, 19_800, 1_792_990_977, 1_792_990_425.506889);
        RoutePositionReading secondNew = screenshotReading(boundarySnapshot, "04149", 1, 16_500.241,
                1.2980455, 103.8493220, 19_800, 1_792_990_553, 1_792_990_425.506889);
        readings.add(firstNew);
        readings.add(secondNew);
        UUID coObservedSnapshot = snapshot("screenshot-coobserved");
        readings.add(reading(coObservedSnapshot, CURRENT_CYCLE, "05069", 21, 2,
                17_983.290, 1.2867897, 103.8457157, "44009", "10499", 1, 300, 5));
        readings.add(reading(coObservedSnapshot, CURRENT_CYCLE, "05069", 21, 3,
                17_986.069, 1.2867720, 103.8456978, "44009", "10499", 1, 311, 5));
        readings.add(reading(snapshot("screenshot-13"), CURRENT_CYCLE, "S13", 22, 1,
                18_192.049, 1.2853138, 103.8445455, "44009", "10499", 1, 120, 5));
        return new ScreenshotReadings(List.copyOf(readings), firstNew.observationId(), secondNew.observationId());
    }

    private RoutePositionReading screenshotReading(String key, String stopCode, int visitOrder,
            double distanceAlongRouteM, double latitude, double longitude, double stopReferenceDistanceMeters,
            double etaEpochSeconds, double fetchedEpochSeconds) {
        return screenshotReading(snapshot("screenshot-" + key), stopCode, visitOrder, distanceAlongRouteM,
                latitude, longitude, stopReferenceDistanceMeters, etaEpochSeconds, fetchedEpochSeconds);
    }

    private RoutePositionReading screenshotReading(UUID snapshotId, String stopCode, int visitOrder,
            double distanceAlongRouteM, double latitude, double longitude, double stopReferenceDistanceMeters,
            double etaEpochSeconds, double fetchedEpochSeconds) {
        Instant estimatedArrivalAt = screenshotInstant(etaEpochSeconds);
        Instant fetchedAt = screenshotInstant(fetchedEpochSeconds);
        int stopSequence = Math.max(1, (int) Math.round(stopReferenceDistanceMeters / 100));
        return new RoutePositionReading(observation(snapshotId, stopCode, visitOrder), snapshotId, CURRENT_CYCLE,
                stopCode, stopSequence, visitOrder, distanceAlongRouteM, distanceAlongRouteM,
                latitude, longitude, "44009", "10499", 1,
                List.of(stopReferenceDistanceMeters),
                List.of(new RoutePositionProjection(stopSequence, distanceAlongRouteM,
                        distanceAlongRouteM, 0)), estimatedArrivalAt, fetchedAt);
    }

    private Instant screenshotInstant(double sourceEpochSeconds) {
        double secondsFromFixtureNow = sourceEpochSeconds - 1_792_990_430;
        return NOW.plusMillis(Math.round(secondsFromFixtureNow * 1_000));
    }

    private List<RoutePositionReading> profileGroup(String key, double latitude, double longitude,
            double distanceAlongRouteM, List<Double> stopDistances, long oldestFetchedMillisAgo) {
        List<RoutePositionReading> readings = new ArrayList<>();
        for (int index = 0; index < stopDistances.size(); index++) {
            readings.add(profileReading(snapshot(key + "-" + index), key + "-" + index, index + 1,
                    distanceAlongRouteM, latitude, longitude, stopDistances.get(index),
                    oldestFetchedMillisAgo - index * 1_000));
        }
        return List.copyOf(readings);
    }

    private RoutePositionReading profileReading(UUID snapshotId, String stopCode, int visitOrder,
            double distanceAlongRouteM, double latitude, double longitude, double stopDistanceAlongRouteM,
            long fetchedMillisAgo) {
        Instant fetchedAt = NOW.minusMillis(fetchedMillisAgo);
        Instant estimatedArrivalAt = NOW.plusSeconds(600)
                .plusMillis(Math.round(stopDistanceAlongRouteM * 100));
        int stopSequence = Math.max(1, (int) Math.round(stopDistanceAlongRouteM / 1_000));
        return new RoutePositionReading(observation(snapshotId, stopCode, visitOrder), snapshotId, CURRENT_CYCLE,
                stopCode, stopSequence, visitOrder, distanceAlongRouteM, stopDistanceAlongRouteM,
                latitude, longitude, "44009", "10499", 1,
                List.of(stopDistanceAlongRouteM),
                List.of(new RoutePositionProjection(stopSequence, distanceAlongRouteM,
                        distanceAlongRouteM, 0)), estimatedArrivalAt, fetchedAt);
    }

    private RoutePositionReading circularPhaseReading(String key, String stopCode, int stopSequence,
            double fallbackDistance, double latitude, double longitude, long etaSeconds,
            List<Double> stopReferenceDistances, List<RoutePositionProjection> projections) {
        UUID snapshotId = snapshot(key);
        Instant fetchedAt = NOW.minusSeconds(5);
        return new RoutePositionReading(observation(snapshotId, stopCode, 1), snapshotId, CURRENT_CYCLE,
                stopCode, stopSequence, 1, fallbackDistance, stopSequence * 1_000.0,
                latitude, longitude, "ORIGIN", "DESTINATION", 1,
                stopReferenceDistances, projections, fetchedAt.plusSeconds(etaSeconds), fetchedAt);
    }

    private RoutePositionReading circularReading(String key, String stopCode, int sequence, long etaSeconds,
            List<Double> stopReferenceDistances, List<RoutePositionProjection> projections) {
        UUID snapshotId = snapshot(key);
        return reading(snapshotId, CURRENT_CYCLE, stopCode, sequence, 1, 1_111,
                1.30, 103.80, "ORIGIN", "DESTINATION", 1, etaSeconds, 5,
                stopReferenceDistances, projections);
    }

    private RoutePositionReading reading(UUID snapshotId, UUID pollCycleId, String stopCode, int stopSequence,
            int visitOrder, double distanceAlongRouteM, double latitude, double longitude,
            String originCode, String destinationCode, int visitNumber, long etaSeconds, long fetchedSecondsAgo) {
        return reading(snapshotId, pollCycleId, stopCode, stopSequence, visitOrder, distanceAlongRouteM,
                latitude, longitude, originCode, destinationCode, visitNumber, etaSeconds, fetchedSecondsAgo,
                List.of((double) stopSequence * 1_000),
                List.of(new RoutePositionProjection(stopSequence, distanceAlongRouteM,
                        distanceAlongRouteM, 0)));
    }

    private RoutePositionReading reading(UUID snapshotId, UUID pollCycleId, String stopCode, int stopSequence,
            int visitOrder, double distanceAlongRouteM, double latitude, double longitude,
            String originCode, String destinationCode, int visitNumber, long etaSeconds, long fetchedSecondsAgo,
            List<Double> stopReferenceDistances, List<RoutePositionProjection> projections) {
        Instant fetchedAt = NOW.minusSeconds(fetchedSecondsAgo);
        return new RoutePositionReading(observation(snapshotId, stopCode, visitOrder), snapshotId, pollCycleId,
                stopCode, stopSequence, visitOrder, distanceAlongRouteM, stopSequence * 1_000.0,
                latitude, longitude, originCode, destinationCode, visitNumber,
                stopReferenceDistances, projections, fetchedAt.plusSeconds(etaSeconds), fetchedAt);
    }

    private UUID snapshot(String key) {
        return UUID.nameUUIDFromBytes(("snapshot:" + key).getBytes(StandardCharsets.UTF_8));
    }

    private UUID observation(UUID snapshotId, String stopCode, int visitOrder) {
        return UUID.nameUUIDFromBytes(
                (snapshotId + ":" + stopCode + ":" + visitOrder).getBytes(StandardCharsets.UTF_8));
    }

    private record ScreenshotReadings(List<RoutePositionReading> readings, UUID firstNewObservationId,
                                      UUID secondNewObservationId) { }
}
