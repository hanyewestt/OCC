package com.csd.occ.disruption.hazard.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface HazardFeedHealthRepository extends JpaRepository<HazardFeedHealth, UUID> {
    List<HazardFeedHealth> findAllByOrderByKindAscSourceAscScopeAscClassificationAsc();
    @Modifying @Query("delete from HazardFeedHealth h where h.classification = 'SIMULATION' and h.lastAttemptAt < :cutoff")
    int deleteExpiredSimulationHealth(Instant cutoff);
}
