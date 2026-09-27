package com.csd.occ.planning.recommendationengine.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;

class RecommendationGeminiPropertiesTest {

    @Test
    void configuredApiKeyIsAvailableWithoutAppearingInTextRepresentation() {
        RecommendationGeminiProperties properties = properties("synthetic-gemini-key");

        assertThat(properties.requiredApiKey()).isEqualTo("synthetic-gemini-key");
        assertThat(properties.requestTimeoutMillis()).isEqualTo(30_000);
        assertThat(properties.toString())
                .contains("apiKey=<redacted>", "model=gemini-3.1-flash-lite")
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
                "synthetic-key", "gemini-3.1-flash-lite", 1.1, 512, GoogleGenAiThinkingLevel.MINIMAL, Duration.ofSeconds(30), 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new RecommendationGeminiProperties(
                "synthetic-key", "gemini-3.1-flash-lite", 1.0, 0, GoogleGenAiThinkingLevel.MINIMAL, Duration.ofSeconds(30), 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new RecommendationGeminiProperties(
                "synthetic-key", "gemini-3.1-flash-lite", 1.0, 512, GoogleGenAiThinkingLevel.MINIMAL, Duration.ZERO, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new RecommendationGeminiProperties(
                "synthetic-key", "gemini-3.1-flash-lite", 1.0, 512, GoogleGenAiThinkingLevel.MINIMAL, Duration.ofSeconds(30), 0));
    }

    private RecommendationGeminiProperties properties(String apiKey) {
        return new RecommendationGeminiProperties(
                apiKey,
                "gemini-3.1-flash-lite",
                1.0,
                512,
                GoogleGenAiThinkingLevel.MINIMAL,
                Duration.ofSeconds(30),
                2);
    }
}
