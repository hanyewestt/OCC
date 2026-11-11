package com.csd.occ.ingestion.infrastructure.config;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class IdentityProviderPropertiesTest {
    @Test
    void environmentSecretNeedsNoMountedFileAndIsRedacted() {
        var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(java.util.Map.of(
                "occ.identity.enabled", "true", "occ.identity.project-url", "https://provider.test",
                "occ.identity.secret-key", "sb_secret_synthetic_environment",
                "occ.identity.timeout", "5s", "occ.identity.directory-interval", "5m"));
        assertThatCode(() -> {
            var settings = new org.springframework.boot.context.properties.bind.Binder(source)
                    .bind("occ.identity", org.springframework.boot.context.properties.bind.Bindable.of(IdentityProviderProperties.class)).get();
            assertThat(settings.toString()).doesNotContain("sb_secret_synthetic_environment");
        }).doesNotThrowAnyException();
    }

    @Test
    void environmentKeyRejectsPublicKeysAndDoesNotEchoInvalidValues() {
        assertThatThrownBy(() -> new IdentityProviderProperties(true, URI.create("https://provider.test"), "",
                Duration.ofSeconds(5), Duration.ofMinutes(5), "sb_publishable_synthetic"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("sb_publishable_synthetic");
        assertThatThrownBy(() -> new IdentityProviderProperties(true, URI.create("https://provider.test"), "",
                Duration.ofSeconds(5), Duration.ofMinutes(5), "sb_secret_synthetic invalid"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("sb_secret_synthetic invalid");
    }

    @Test
    void disabledIdentityNeedsNoSecretOrProjectConfiguration() {
        assertThatCode(() -> new IdentityProviderProperties(false, null, "", Duration.ofSeconds(5), Duration.ofMinutes(5), ""))
                .doesNotThrowAnyException();
    }

    @Test
    void enabledIdentityRequiresAProjectAndMountedSecretPath() {
        assertThatThrownBy(() -> new IdentityProviderProperties(true, null, "", Duration.ofSeconds(5), Duration.ofMinutes(5), ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentityProviderProperties(true, URI.create("https://provider.test"), "", Duration.ofSeconds(5), Duration.ofMinutes(5), ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidTimeoutsIntervalsAndNonHttpUrlsAreRejected() {
        assertThatThrownBy(() -> new IdentityProviderProperties(true, URI.create("file:///tmp/secret"), "mounted", Duration.ofSeconds(5), Duration.ofMinutes(5), ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentityProviderProperties(false, null, "", Duration.ZERO, Duration.ofMinutes(5), ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentityProviderProperties(false, null, "", Duration.ofSeconds(5), Duration.ZERO, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
