package com.csd.occ.ingestion.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class OperationalConfigurationTest {

    @Test
    void omittedOperationalVariablesUseDefaults() {
        Binder binder = configuration(Map.of());
        HazardFeedProperties hazards = binder.bind("occ.ingestion.hazards", HazardFeedProperties.class).get();
        assertThat(hazards.flood().enabled()).isTrue();
        assertThat(hazards.rail().enabled()).isTrue();
        assertThat(hazards.roadworks().enabled()).isTrue();
        assertThat(binder.bind("occ.ingestion.schedule", IngestionScheduleProperties.class).get().busArrivalPollInterval())
                .isEqualTo(Duration.ofSeconds(60));
        assertThat(binder.bind("occ.ingestion.route-positions", RoutePositionProperties.class).get().clusterToleranceMeters())
                .isEqualTo(150);
    }

    @Test
    void canonicalEnvironmentOverridesDefaults() {
        Binder binder = configuration(Map.of("OCC_INGESTION_SCHEDULE_BUSARRIVALPOLLINTERVAL", "90s"));
        assertThat(binder.bind("occ.ingestion.schedule", IngestionScheduleProperties.class).get().busArrivalPollInterval())
                .isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void existingMultiwordAliasesRemainSupported() {
        Binder binder = configuration(Map.of("OCC_INGESTION_PACING_TARGET_HEADWAY", "240s"));
        assertThat(binder.bind("occ.ingestion.pacing.target-headway", Duration.class).get())
                .isEqualTo(Duration.ofSeconds(240));
    }

    @Test
    void existingAliasesRemainSupported() {
        Binder binder = configuration(Map.of("OCC_INGESTION_BUS_ARRIVAL_ENABLED", "false",
                "OCC_INGESTION_BUS_ARRIVAL_POLL_INTERVAL", "90s",
                "OCC_INGESTION_ROUTE_POSITION_CLUSTER_TOLERANCE_M", "200",
                "SUPABASE_JWT_ALLOWED_ALGORITHMS", "RS256"));
        IngestionScheduleProperties schedule = binder.bind("occ.ingestion.schedule", IngestionScheduleProperties.class).get();
        assertThat(schedule.busArrivalEnabled()).isFalse();
        assertThat(schedule.busArrivalPollInterval()).isEqualTo(Duration.ofSeconds(90));
        assertThat(binder.bind("occ.ingestion.route-positions", RoutePositionProperties.class).get().clusterToleranceMeters())
                .isEqualTo(200);
        assertThat(binder.bind("occ.security.supabase.allowed-algorithms", String.class).get()).isEqualTo("RS256");
    }

    private Binder configuration(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource("systemEnvironment", variables));
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return Binder.get(environment);
    }
}
