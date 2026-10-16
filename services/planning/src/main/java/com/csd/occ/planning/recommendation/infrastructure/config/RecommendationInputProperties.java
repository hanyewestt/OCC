package com.csd.occ.planning.recommendation.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("occ.recommendation.input")
public record RecommendationInputProperties(@Min(8_192) @Max(262_144) long maxPromptInputBytes) {
    public RecommendationInputProperties {
        if (maxPromptInputBytes < 8_192 || maxPromptInputBytes > 262_144) {
            throw new IllegalArgumentException("max-prompt-input-bytes must be 8192..262144");
        }
    }
}
