package com.csd.occ.ingestion.domain;

import java.util.Optional;
import java.util.UUID;

/** Read-only lookup for the recommendation produced by the simulation workflow. */
public interface RecommendationLookupPort {
    Optional<DemoRecommendationDetails> findByBunchingEventId(UUID bunchingEventId);
}
