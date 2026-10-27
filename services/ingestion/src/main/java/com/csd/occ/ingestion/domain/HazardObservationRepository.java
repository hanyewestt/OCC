package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface HazardObservationRepository extends JpaRepository<HazardObservation, UUID> {
    List<HazardObservation> findByPollRunIdOrderById(UUID pollRunId);
    long deleteByDemoRunId(UUID demoRunId);
    @Modifying @Query("delete from HazardObservation h where h.classification = 'OBSERVATION_ONLY' and h.fetchedAt < :cutoff")
    int deleteExpiredLive(Instant cutoff);
}
