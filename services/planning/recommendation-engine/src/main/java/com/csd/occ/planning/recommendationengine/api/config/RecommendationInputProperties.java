package com.csd.occ.planning.recommendationengine.api.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("occ.recommendation.input")
public record RecommendationInputProperties(
        @Min(16_384) @Max(1_048_576) long maxRequestBodyBytes,
        @Min(8_192) @Max(262_144) long maxPromptInputBytes) {

    public RecommendationInputProperties {
        if (maxRequestBodyBytes < 16_384 || maxRequestBodyBytes > 1_048_576) {
            throw new IllegalArgumentException("max-request-body-bytes must be 16384..1048576");
        }
        if (maxPromptInputBytes < 8_192 || maxPromptInputBytes > 262_144) {
            throw new IllegalArgumentException("max-prompt-input-bytes must be 8192..262144");
        }
    }
}
