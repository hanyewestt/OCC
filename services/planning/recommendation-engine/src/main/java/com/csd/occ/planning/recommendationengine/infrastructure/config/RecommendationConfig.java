package com.csd.occ.planning.recommendationengine.infrastructure.config;

import com.csd.occ.planning.recommendationengine.domain.BunchingMitigationCandidateValidator;
import com.csd.occ.planning.recommendationengine.domain.BunchingRecommendationPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RecommendationConfig {

    @Bean
    BunchingMitigationCandidateValidator bunchingMitigationCandidateValidator() {
        return new BunchingMitigationCandidateValidator();
    }

    @Bean
    BunchingRecommendationPolicy bunchingRecommendationPolicy() {
        return new BunchingRecommendationPolicy();
    }
}
