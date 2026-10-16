package com.csd.occ.planning.recommendation.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.recommendation.ai")
public record RecommendationAiProperties(boolean enabled) {
}
