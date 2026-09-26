package com.csd.occ.planning.recommendationengine.infrastructure.config;

import com.csd.occ.planning.recommendationengine.domain.BunchingMitigationCandidateValidator;
import com.csd.occ.planning.recommendationengine.domain.BunchingRecommendationOrchestrator;
import com.csd.occ.planning.recommendationengine.domain.BunchingRecommendationPolicy;
import com.csd.occ.planning.recommendationengine.domain.MitigationCandidateGeneratorPort;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
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

    @Bean
    BunchingRecommendationOrchestrator bunchingRecommendationOrchestrator(
            RecommendationAiProperties aiProperties,
            ObjectProvider<MitigationCandidateGeneratorPort> candidateGenerator,
            BunchingMitigationCandidateValidator candidateValidator,
            BunchingRecommendationPolicy deterministicPolicy) {
        return new BunchingRecommendationOrchestrator(
                aiProperties.enabled(),
                Optional.ofNullable(candidateGenerator.getIfAvailable()),
                candidateValidator,
                deterministicPolicy);
    }
}
