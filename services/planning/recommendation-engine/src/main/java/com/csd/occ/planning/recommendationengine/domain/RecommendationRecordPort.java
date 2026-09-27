package com.csd.occ.planning.recommendationengine.domain;

import java.util.Optional;
import java.util.UUID;

public interface RecommendationRecordPort {

    Optional<RecommendationRecord> findByIdempotencyKey(UUID idempotencyKey);

    boolean saveIfAbsent(RecommendationRecord record);
}
