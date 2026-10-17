package com.csd.occ.gateway.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import org.junit.jupiter.api.Test;

class FrontendPropertiesTest {
    @Test
    void acceptsPublicPublishableKeysAndAnUnconfiguredBrowserLogin() {
        assertThatCode(() -> new FrontendProperties(URI.create("https://example.supabase.co"),
                "sb_publishable_fixture")).doesNotThrowAnyException();
        assertThatCode(() -> new FrontendProperties(URI.create("https://example.supabase.co"), ""))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSecretAndLegacyKeysBeforeTheyCanReachTheBrowser() {
        for (String key : new String[] {"sb_secret_forbidden", "eyJhbGciOiJIUzI1NiJ9.legacy.signature"}) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new FrontendProperties(URI.create("https://example.supabase.co"), key));
        }
    }
}
