package com.csd.occ.diagnosis.infrastructure.config;

import java.time.Duration;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.diagnosis.gemini")
public record DiagnosisGeminiProperties(String apiKey, String model, double temperature,
        GoogleGenAiThinkingLevel thinkingLevel, int maxOutputTokens, Duration requestTimeout, int retryAttempts) {
    public DiagnosisGeminiProperties {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model is required");
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2)
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        if (thinkingLevel == null) throw new IllegalArgumentException("thinking-level is required");
        if (maxOutputTokens < 1 || maxOutputTokens > 4096)
            throw new IllegalArgumentException("max-output-tokens must be between 1 and 4096");
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                || requestTimeout.toMillis() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("request-timeout must be positive and fit in milliseconds");
        if (retryAttempts < 1 || retryAttempts > 5) throw new IllegalArgumentException("retry-attempts must be 1..5");
    }
    public String requiredApiKey() {
        if (apiKey == null || apiKey.isBlank() || "change-me".equals(apiKey))
            throw new IllegalStateException("OCC_DIAGNOSIS_GEMINI_API_KEY must be configured for root-cause diagnosis");
        return apiKey;
    }
    public int requestTimeoutMillis() { return Math.toIntExact(requestTimeout.toMillis()); }
    @Override public String toString() {
        return "DiagnosisGeminiProperties[apiKey=<redacted>, model=" + model + ", temperature=" + temperature
                + ", thinkingLevel=" + thinkingLevel + ", maxOutputTokens=" + maxOutputTokens
                + ", requestTimeout=" + requestTimeout + ", retryAttempts=" + retryAttempts + "]";
    }
}
