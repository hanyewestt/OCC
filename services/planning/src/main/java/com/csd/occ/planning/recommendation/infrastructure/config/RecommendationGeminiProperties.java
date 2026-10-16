package com.csd.occ.planning.recommendation.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;

@ConfigurationProperties("occ.recommendation.ai.gemini")
public record RecommendationGeminiProperties(
        String apiKey,
        String model,
        double temperature,
        int maxOutputTokens,
        GoogleGenAiThinkingLevel thinkingLevel,
        Duration requestTimeout,
        int retryAttempts) {

    private static final int MAXIMUM_OUTPUT_TOKENS = 4_096;
    private static final int MAXIMUM_RETRY_ATTEMPTS = 5;

    public RecommendationGeminiProperties {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        if (temperature < 0.0 || temperature > 1.0) {
            throw new IllegalArgumentException("temperature must be between zero and one");
        }
        if (maxOutputTokens < 1 || maxOutputTokens > MAXIMUM_OUTPUT_TOKENS) {
            throw new IllegalArgumentException("maxOutputTokens must be between one and 4096");
        }
        if (thinkingLevel == null) throw new IllegalArgumentException("thinkingLevel is required for Gemini 3 models");
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (requestTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("requestTimeout must fit in milliseconds");
        }
        if (retryAttempts < 1 || retryAttempts > MAXIMUM_RETRY_ATTEMPTS) {
            throw new IllegalArgumentException("retryAttempts must be between one and five");
        }
    }

    public String requiredApiKey() {
        if (apiKey == null || apiKey.isBlank() || apiKey.equals("change-me")) {
            throw new IllegalStateException(
                    "OCC_PLANNING_GEMINI_API_KEY must be configured when AI generation is enabled");
        }
        return apiKey;
    }

    public int requestTimeoutMillis() {
        return Math.toIntExact(requestTimeout.toMillis());
    }

    @Override
    public String toString() {
        return "RecommendationGeminiProperties[apiKey=<redacted>, model=" + model
                + ", temperature=" + temperature
                + ", maxOutputTokens=" + maxOutputTokens
                + ", thinkingLevel=" + thinkingLevel
                + ", requestTimeout=" + requestTimeout
                + ", retryAttempts=" + retryAttempts + "]";
    }
}
