package com.csd.occ.planning.infrastructure.config;

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
        assertThat(binder.bind("occ.diagnosis.worker-interval", Duration.class).get()).isEqualTo(Duration.ofSeconds(2));
        assertThat(binder.bind("occ.recommendation.ai.gemini.request-timeout", Duration.class).get())
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void canonicalEnvironmentOverridesDefaults() {
        Binder binder = configuration(Map.of("OCC_DIAGNOSIS_WORKERINTERVAL", "4s"));
        assertThat(binder.bind("occ.diagnosis.worker-interval", Duration.class).get()).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void existingAliasesRemainSupported() {
        Binder binder = configuration(Map.of("OCC_PLANNING_DIAGNOSIS_WORKER_ENABLED", "false",
                "OCC_PLANNING_GEMINI_REQUEST_TIMEOUT", "60s", "SUPABASE_JWT_ALLOWED_ALGORITHMS", "RS256"));
        assertThat(binder.bind("occ.diagnosis.worker-enabled", Boolean.class).get()).isFalse();
        assertThat(binder.bind("occ.diagnosis.gemini.request-timeout", Duration.class).get()).isEqualTo(Duration.ofSeconds(60));
        assertThat(binder.bind("occ.recommendation.ai.gemini.request-timeout", Duration.class).get())
                .isEqualTo(Duration.ofSeconds(60));
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
