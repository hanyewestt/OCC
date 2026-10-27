package com.csd.occ.ingestion.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;

class RoutePositionPropertiesTest {
    @Test
    void applicationDefaultsBindToTypedSettings() {
        StandardEnvironment environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment);

        RoutePositionProperties properties = Binder.get(environment)
                .bind("occ.ingestion.route-positions", RoutePositionProperties.class)
                .get();

        assertThat(properties.identityLinkDistanceMeters()).isEqualTo(25);
        assertThat(properties.identityLinkProjectionSpeedMetersPerSecond()).isEqualTo(7);
        assertThat(properties.identityEtaTolerance()).isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.identityLinkRoutes()).containsExactly("190:1", "190:2");
        assertThat(properties.coordinatedRefreshBoundaryRoutes()).containsExactly("190:1");
        assertThat(properties.refreshBoundaryAllowedOverlap()).isEqualTo(Duration.ofMillis(250));
        assertThat(properties.refreshBoundaryProfileMinimumSecondsPerMeter()).isEqualTo(0.02);
        assertThat(properties.refreshBoundaryProfileMaximumSecondsPerMeter()).isEqualTo(2);
        assertThat(properties.circularPhaseMaximumGapMeters()).isEqualTo(2_000);
        assertThat(properties.circularPhaseLinkRoutes()).containsExactly("972:1");
    }

    @Test
    void rejectsInvalidExistingRoutePositionSettings() {
        assertThatThrownBy(() -> legacyProperties(0, 2, 16.7, 50, 7, 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cluster-tolerance-meters");
        assertThatThrownBy(() -> legacyProperties(150, 0, 16.7, 50, 7, 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-poll-age-cycles");
        assertThatThrownBy(() -> legacyProperties(150, 2, 40.1, 50, 7, 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("speed-cap-meters-per-second");
        assertThatThrownBy(() -> legacyProperties(150, 2, 16.7, 0, 7, 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("uncertainty-meters");
        assertThatThrownBy(() -> legacyProperties(150, 2, 16.7, 50, 40.1, 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("circular-projection-speed");
        assertThatThrownBy(() -> legacyProperties(150, 2, 16.7, 50, 7, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minimum-stop-count");
    }

    @Test
    void rejectsInvalidIdentityBoundaryAndCircularPhaseSettings() {
        assertThatThrownBy(() -> policyProperties(0, 7, Duration.ofSeconds(20), List.of("190:1"),
                250, Duration.ofSeconds(5), Duration.ofMillis(250), Duration.ofSeconds(75),
                250, 2_000, Duration.ofSeconds(75)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity-link-distance");
        assertThatThrownBy(() -> policyProperties(25, 0, Duration.ofSeconds(20), List.of("190:1"),
                250, Duration.ofSeconds(5), Duration.ofMillis(250), Duration.ofSeconds(75),
                250, 2_000, Duration.ofSeconds(75)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity-link-projection-speed");
        assertThatThrownBy(() -> policyProperties(25, 7, Duration.ZERO, List.of("190:1"),
                250, Duration.ofSeconds(5), Duration.ofMillis(250), Duration.ofSeconds(75),
                250, 2_000, Duration.ofSeconds(75)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity-eta-tolerance");
        assertThatThrownBy(() -> policyProperties(25, 7, Duration.ofSeconds(20), List.of("190"),
                250, Duration.ofSeconds(5), Duration.ofMillis(250), Duration.ofSeconds(75),
                250, 2_000, Duration.ofSeconds(75)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("service:positive-direction");
        assertThatThrownBy(() -> policyProperties(25, 7, Duration.ofSeconds(20), List.of("190:1"),
                250, Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofSeconds(75),
                250, 2_000, Duration.ofSeconds(75)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allowed-overlap");
        assertThatThrownBy(() -> policyProperties(25, 7, Duration.ofSeconds(20), List.of("190:1"),
                250, Duration.ofSeconds(5), Duration.ofMillis(250), Duration.ofSeconds(75),
                250, 0, Duration.ofSeconds(75)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maximum-gap-meters");
        assertThatThrownBy(() -> slopeProperties(0, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile-minimum-seconds-per-meter");
        assertThatThrownBy(() -> slopeProperties(2, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile-maximum-seconds-per-meter");
    }

    @Test
    void normalizesAndDeduplicatesCoordinatedBoundaryRoutes() {
        RoutePositionProperties properties = policyProperties(25, 7, Duration.ofSeconds(20),
                List.of(" 190:01 ", "190:1", "nr6:2"), 250, Duration.ofSeconds(5),
                Duration.ofMillis(250), Duration.ofSeconds(75), 250, 2_000, Duration.ofSeconds(75));

        assertThat(properties.coordinatedRefreshBoundaryRoutes()).containsExactly("190:1", "NR6:2");
    }

    @Test
    void normalizesAndDeduplicatesEachValidatedRouteScope() {
        RoutePositionProperties properties = scopedProperties(
                List.of(" 190:01 ", "190:1", "190:2"),
                List.of(" 190:01 ", "190:1"),
                List.of(" 972:01 ", "972:1"));

        assertThat(properties.identityLinkRoutes()).containsExactly("190:1", "190:2");
        assertThat(properties.coordinatedRefreshBoundaryRoutes()).containsExactly("190:1");
        assertThat(properties.circularPhaseLinkRoutes()).containsExactly("972:1");
    }

    @Test
    void configuredSettingsEnablePoliciesOnlyForAllowListedDirections() {
        RoutePositionProperties properties = scopedProperties(
                List.of("190:1", "190:2"), List.of("190:1"), List.of("972:1"));
        ConfiguredRoutePositionSettings settings = new ConfiguredRoutePositionSettings(properties,
                new IngestionScheduleProperties(true, Duration.ofSeconds(30), Duration.ZERO,
                        true, Duration.ofMinutes(2), Duration.ZERO, Duration.ofHours(1), Duration.ZERO));

        assertThat(settings.isIdentityLinkEnabled("190", 1)).isTrue();
        assertThat(settings.isIdentityLinkEnabled("190", 2)).isTrue();
        assertThat(settings.isIdentityLinkEnabled("972", 1)).isFalse();
        assertThat(settings.isCoordinatedRefreshBoundaryEnabled("190", 1)).isTrue();
        assertThat(settings.isCoordinatedRefreshBoundaryEnabled("190", 2)).isFalse();
        assertThat(settings.isCoordinatedRefreshBoundaryEnabled("972", 1)).isFalse();
        assertThat(settings.isCircularPhaseLinkEnabled("972", 1)).isTrue();
        assertThat(settings.isCircularPhaseLinkEnabled("190", 1)).isFalse();
    }

    @Test
    void emptyRouteScopesDisableEveryOptionalPolicy() {
        ConfiguredRoutePositionSettings settings = new ConfiguredRoutePositionSettings(
                scopedProperties(List.of(), List.of(), List.of()),
                new IngestionScheduleProperties(true, Duration.ofSeconds(30), Duration.ZERO,
                        true, Duration.ofMinutes(2), Duration.ZERO, Duration.ofHours(1), Duration.ZERO));

        assertThat(settings.isIdentityLinkEnabled("190", 1)).isFalse();
        assertThat(settings.isCoordinatedRefreshBoundaryEnabled("190", 1)).isFalse();
        assertThat(settings.isCircularPhaseLinkEnabled("972", 1)).isFalse();
    }

    private static RoutePositionProperties legacyProperties(double clusterToleranceMeters, int maxPollAgeCycles,
            double speedCapMetersPerSecond, double uncertaintyMeters,
            double circularProjectionSpeedMetersPerSecond, int circularProjectionMinimumStopCount) {
        return new RoutePositionProperties(clusterToleranceMeters, maxPollAgeCycles, speedCapMetersPerSecond,
                uncertaintyMeters, circularProjectionSpeedMetersPerSecond, circularProjectionMinimumStopCount,
                25, 7, Duration.ofSeconds(20), List.of("190:1", "190:2"), List.of("190:1"),
                250, Duration.ofSeconds(5), Duration.ofMillis(250), Duration.ofSeconds(75),
                0.02, 2,
                250, 2_000, Duration.ofSeconds(75), List.of("972:1"));
    }

    private static RoutePositionProperties policyProperties(double identityLinkDistanceMeters,
            double identityLinkProjectionSpeedMetersPerSecond,
            Duration identityEtaTolerance, List<String> coordinatedRefreshBoundaryRoutes,
            double refreshBoundaryDistanceMeters, Duration refreshBoundaryWindow,
            Duration refreshBoundaryAllowedOverlap, Duration refreshBoundaryProfileResidual,
            double circularPhaseLinkDistanceMeters, double circularPhaseMaximumGapMeters,
            Duration circularPhaseMaximumEtaError) {
        return new RoutePositionProperties(150, 2, 16.7, 50, 7, 3,
                identityLinkDistanceMeters, identityLinkProjectionSpeedMetersPerSecond,
                identityEtaTolerance, List.of("190:1", "190:2"),
                coordinatedRefreshBoundaryRoutes,
                refreshBoundaryDistanceMeters, refreshBoundaryWindow, refreshBoundaryAllowedOverlap,
                refreshBoundaryProfileResidual, 0.02, 2, circularPhaseLinkDistanceMeters,
                circularPhaseMaximumGapMeters, circularPhaseMaximumEtaError, List.of("972:1"));
    }

    private static RoutePositionProperties scopedProperties(List<String> identityLinkRoutes,
            List<String> coordinatedRefreshBoundaryRoutes, List<String> circularPhaseLinkRoutes) {
        return new RoutePositionProperties(150, 2, 16.7, 50, 7, 3,
                25, 7, Duration.ofSeconds(20), identityLinkRoutes, coordinatedRefreshBoundaryRoutes,
                250, Duration.ofSeconds(5), Duration.ofMillis(250), Duration.ofSeconds(75),
                0.02, 2,
                250, 2_000, Duration.ofSeconds(75), circularPhaseLinkRoutes);
    }

    private static RoutePositionProperties slopeProperties(double minimumSecondsPerMeter,
            double maximumSecondsPerMeter) {
        return new RoutePositionProperties(150, 2, 16.7, 50, 7, 3,
                25, 7, Duration.ofSeconds(20), List.of("190:1", "190:2"), List.of("190:1"),
                250, Duration.ofSeconds(5), Duration.ofMillis(250), Duration.ofSeconds(75),
                minimumSecondsPerMeter, maximumSecondsPerMeter,
                250, 2_000, Duration.ofSeconds(75), List.of("972:1"));
    }
}
