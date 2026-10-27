package com.csd.occ.gateway.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FrontendPropertiesTest {
    @Test
    void acceptsPublicPublishableKeysAndAnUnconfiguredBrowserLogin() {
        assertThatCode(() -> new FrontendProperties(URI.create("https://example.supabase.co"),
                "sb_publishable_fixture", Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(20), Duration.ofSeconds(5), Duration.ofSeconds(30))).doesNotThrowAnyException();
        assertThatCode(() -> new FrontendProperties(URI.create("https://example.supabase.co"), "",
                Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(20), Duration.ofSeconds(5), Duration.ofSeconds(30)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSecretAndLegacyKeysBeforeTheyCanReachTheBrowser() {
        for (String key : new String[] {"sb_secret_forbidden", "eyJhbGciOiJIUzI1NiJ9.legacy.signature"}) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new FrontendProperties(URI.create("https://example.supabase.co"), key,
                            Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(20), Duration.ofSeconds(5), Duration.ofSeconds(30)));
        }
    }

    @Test
    void rejectsIntervalsBelowOneSecond() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FrontendProperties(
                URI.create("https://example.supabase.co"), "sb_publishable_fixture", Duration.ZERO,
                Duration.ofSeconds(60), Duration.ofSeconds(20), Duration.ofSeconds(5), Duration.ofSeconds(30)));
        assertThatIllegalArgumentException().isThrownBy(() -> new FrontendProperties(
                URI.create("https://example.supabase.co"), "sb_publishable_fixture", Duration.ofSeconds(30),
                Duration.ofMillis(500), Duration.ofSeconds(20), Duration.ofSeconds(5), Duration.ofSeconds(30)));
        assertThatIllegalArgumentException().isThrownBy(() -> new FrontendProperties(
                URI.create("https://example.supabase.co"), "sb_publishable_fixture", Duration.ofSeconds(30),
                Duration.ofSeconds(60), Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofSeconds(30)));
    }
}
