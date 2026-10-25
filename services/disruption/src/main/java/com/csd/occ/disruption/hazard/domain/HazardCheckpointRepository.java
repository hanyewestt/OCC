package com.csd.occ.disruption.hazard.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface HazardCheckpointRepository extends JpaRepository<HazardCheckpoint, String> {
    java.util.List<HazardCheckpoint> findAllByOrderByKindAsc();
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from HazardCheckpoint c where c.kind = :kind")
    Optional<HazardCheckpoint> findForUpdate(String kind);
}
