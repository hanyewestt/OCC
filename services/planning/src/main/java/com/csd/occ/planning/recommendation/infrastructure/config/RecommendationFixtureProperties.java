package com.csd.occ.planning.recommendation.infrastructure.config;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

@ConfigurationProperties("occ.recommendation.fixture")
public record RecommendationFixtureProperties(Resource location) {

    public RecommendationFixtureProperties {
        Objects.requireNonNull(location, "fixture location is required");
    }
}
