package com.csd.occ.disruption.infrastructure.config;

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
        assertThat(binder.bind("occ.disruption.poll-interval", Duration.class).get()).isEqualTo(Duration.ofSeconds(1));
        assertThat(binder.bind("occ.disruption.planning.delivery-interval", Duration.class).get()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void canonicalEnvironmentOverridesDefaults() {
        Binder binder = configuration(Map.of("OCC_DISRUPTION_POLLINTERVAL", "45s"));
        assertThat(binder.bind("occ.disruption.poll-interval", Duration.class).get()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void existingAliasesRemainSupported() {
        Binder binder = configuration(Map.of("OCC_DISRUPTION_POLL_INTERVAL", "45s",
                "OCC_DISRUPTION_PLANNING_DELIVERY_INTERVAL", "25s", "SUPABASE_JWT_ALLOWED_ALGORITHMS", "RS256"));
        assertThat(binder.bind("occ.disruption.poll-interval", Duration.class).get()).isEqualTo(Duration.ofSeconds(45));
        assertThat(binder.bind("occ.disruption.planning.delivery-interval", Duration.class).get()).isEqualTo(Duration.ofSeconds(25));
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
