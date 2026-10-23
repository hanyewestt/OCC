package com.csd.occ.planning.recommendation.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecommendationRecordPort {

    Optional<RecommendationRecord> findByIdempotencyKey(UUID idempotencyKey);

    Optional<RecommendationRecord> findByBunchingEventId(UUID bunchingEventId);

    List<RecommendationRecord> findPending(int offset, int limit);

    long countPending();

    boolean saveIfAbsent(RecommendationRecord record);
}
