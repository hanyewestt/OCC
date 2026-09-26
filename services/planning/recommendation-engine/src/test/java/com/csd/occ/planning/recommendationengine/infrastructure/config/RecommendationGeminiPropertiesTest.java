package com.csd.occ.planning.recommendationengine.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RecommendationGeminiPropertiesTest {

    @Test
    void configuredApiKeyIsAvailableWithoutAppearingInTextRepresentation() {
        RecommendationGeminiProperties properties = properties("synthetic-gemini-key");

        assertThat(properties.requiredApiKey()).isEqualTo("synthetic-gemini-key");
        assertThat(properties.requestTimeoutMillis()).isEqualTo(30_000);
        assertThat(properties.toString())
                .contains("apiKey=<redacted>", "model=gemini-2.5-flash")
                .doesNotContain("synthetic-gemini-key");
    }

    @Test
    void placeholderOrMissingApiKeyIsRejectedOnlyWhenProviderIsCreated() {
        assertThatIllegalStateException()
                .isThrownBy(() -> properties("").requiredApiKey())
                .withMessageContaining("OCC_RECOMMENDATION_GEMINI_API_KEY");
        assertThatIllegalStateException()
                .isThrownBy(() -> properties("change-me").requiredApiKey())
                .withMessageContaining("OCC_RECOMMENDATION_GEMINI_API_KEY");
    }

    @Test
    void invalidOperationalSettingsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RecommendationGeminiProperties(
                "synthetic-key", "gemini-2.5-flash", 1.1, 512, Duration.ofSeconds(30), 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new RecommendationGeminiProperties(
                "synthetic-key", "gemini-2.5-flash", 0.0, 0, Duration.ofSeconds(30), 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new RecommendationGeminiProperties(
                "synthetic-key", "gemini-2.5-flash", 0.0, 512, Duration.ZERO, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new RecommendationGeminiProperties(
                "synthetic-key", "gemini-2.5-flash", 0.0, 512, Duration.ofSeconds(30), 0));
    }

    private RecommendationGeminiProperties properties(String apiKey) {
        return new RecommendationGeminiProperties(
                apiKey,
                "gemini-2.5-flash",
                0.0,
                512,
                Duration.ofSeconds(30),
                2);
    }
}
