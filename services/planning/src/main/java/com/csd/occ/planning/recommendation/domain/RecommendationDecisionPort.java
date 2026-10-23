package com.csd.occ.planning.recommendation.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecommendationDecisionPort {
    Optional<RecommendationDecision> findByRecommendationId(UUID recommendationId);

    Optional<RecommendationDecision> recordIfAbsent(RecommendationDecision decision);

    List<RecommendationDecision> findAll(int offset, int limit);

    long count();
}
