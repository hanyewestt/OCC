package com.csd.occ.planning.recommendationengine.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.Client;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;

class GeminiRecommendationConfigTest {

    private final GeminiRecommendationConfig config = new GeminiRecommendationConfig();

    @Test
    void createsGoogleModelFromTypedSettingsWithoutCallingProvider() throws Exception {
        RecommendationGeminiProperties properties = new RecommendationGeminiProperties(
                "synthetic-gemini-key",
                "gemini-3.7-flash",
                0.0,
                512,
                0,
                Duration.ofSeconds(30),
                2);

        try (Client client = config.recommendationGeminiClient(properties)) {
            GoogleGenAiChatModel model = config.recommendationGeminiChatModel(client, properties);

            assertThat(model.getOptions().getModel()).isEqualTo("gemini-3.7-flash");
            assertThat(model.getOptions().getTemperature()).isZero();
            assertThat(model.getOptions().getMaxOutputTokens()).isEqualTo(512);
            assertThat(model.getOptions().getThinkingBudget()).isZero();
            assertThat(model.getOptions().getIncludeThoughts()).isFalse();
            assertThat(model.getOptions().getCandidateCount()).isEqualTo(1);
        }
    }
}
