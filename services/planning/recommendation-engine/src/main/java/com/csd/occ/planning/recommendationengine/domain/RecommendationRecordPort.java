package com.csd.occ.planning.recommendationengine.domain;

import java.util.Optional;
import java.util.UUID;

public interface RecommendationRecordPort {

    Optional<RecommendationRecord> findByIdempotencyKey(UUID idempotencyKey);

    Optional<RecommendationRecord> findByBunchingEventId(UUID bunchingEventId);

    boolean saveIfAbsent(RecommendationRecord record);
}
