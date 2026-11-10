package com.csd.occ.ingestion.infrastructure.config;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class IdentityProviderPropertiesTest {
    @Test
    void disabledIdentityNeedsNoSecretOrProjectConfiguration() {
        assertThatCode(() -> new IdentityProviderProperties(false, null, "", Duration.ofSeconds(5), Duration.ofMinutes(5)))
                .doesNotThrowAnyException();
    }

    @Test
    void enabledIdentityRequiresAProjectAndMountedSecretPath() {
        assertThatThrownBy(() -> new IdentityProviderProperties(true, null, "", Duration.ofSeconds(5), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentityProviderProperties(true, URI.create("https://provider.test"), "", Duration.ofSeconds(5), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidTimeoutsIntervalsAndNonHttpUrlsAreRejected() {
        assertThatThrownBy(() -> new IdentityProviderProperties(true, URI.create("file:///tmp/secret"), "mounted", Duration.ofSeconds(5), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentityProviderProperties(false, null, "", Duration.ZERO, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentityProviderProperties(false, null, "", Duration.ofSeconds(5), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
