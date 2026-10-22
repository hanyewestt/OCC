package com.csd.occ.planning.recommendation.domain;

import java.util.Optional;
import java.util.UUID;

public interface RecommendationDecisionPort {
    Optional<RecommendationDecision> findByRecommendationId(UUID recommendationId);

    Optional<RecommendationDecision> recordIfAbsent(RecommendationDecision decision);
}
