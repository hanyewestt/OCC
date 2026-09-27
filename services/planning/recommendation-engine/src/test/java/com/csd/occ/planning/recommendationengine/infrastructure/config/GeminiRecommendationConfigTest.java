package com.csd.occ.planning.recommendationengine.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.Client;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;

class GeminiRecommendationConfigTest {

    private final GeminiRecommendationConfig config = new GeminiRecommendationConfig();

    @Test
    void createsGoogleModelFromTypedSettingsWithoutCallingProvider() throws Exception {
        RecommendationGeminiProperties properties = new RecommendationGeminiProperties(
                "synthetic-gemini-key",
                "gemini-3.1-flash-lite",
                1.0,
                512,
                GoogleGenAiThinkingLevel.MINIMAL,
                Duration.ofSeconds(30),
                2);

        try (Client client = config.recommendationGeminiClient(properties)) {
            GoogleGenAiChatModel model = config.recommendationGeminiChatModel(client, properties);

            assertThat(model.getOptions().getModel()).isEqualTo("gemini-3.1-flash-lite");
            assertThat(model.getOptions().getTemperature()).isEqualTo(1.0);
            assertThat(model.getOptions().getMaxOutputTokens()).isEqualTo(512);
            assertThat(model.getOptions().getThinkingLevel()).isEqualTo(GoogleGenAiThinkingLevel.MINIMAL);
            assertThat(model.getOptions().getIncludeThoughts()).isFalse();
            assertThat(model.getOptions().getCandidateCount()).isEqualTo(1);
        }
    }
}
