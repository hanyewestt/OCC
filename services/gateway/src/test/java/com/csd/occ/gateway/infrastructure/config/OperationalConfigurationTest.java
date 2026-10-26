package com.csd.occ.gateway.infrastructure.config;

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
        assertThat(binder.bind("occ.frontend.controller-refresh-interval", Duration.class).get()).isEqualTo(Duration.ofSeconds(30));
        assertThat(binder.bind("occ.frontend.ingestion-poll-interval", Duration.class).get()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void canonicalEnvironmentOverridesDefaults() {
        Binder binder = configuration(Map.of("OCC_FRONTEND_CONTROLLERREFRESHINTERVAL", "45s"));
        assertThat(binder.bind("occ.frontend.controller-refresh-interval", Duration.class).get()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void existingAliasesRemainSupported() {
        Binder binder = configuration(Map.of("OCC_CONTROLLER_DATA_REFRESH_INTERVAL", "45s",
                "OCC_ROUTE_INCIDENT_GAP_TOLERANCE", "25s", "SUPABASE_JWT_ALLOWED_ALGORITHMS", "RS256"));
        assertThat(binder.bind("occ.frontend.controller-refresh-interval", Duration.class).get()).isEqualTo(Duration.ofSeconds(45));
        assertThat(binder.bind("occ.frontend.route-incident-gap-tolerance", Duration.class).get()).isEqualTo(Duration.ofSeconds(25));
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
