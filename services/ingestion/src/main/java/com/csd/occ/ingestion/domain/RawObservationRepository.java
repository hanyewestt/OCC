package com.csd.occ.ingestion.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import com.csd.occ.ingestion.domain.RawObservation;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;

public interface RawObservationRepository extends JpaRepository<RawObservation, UUID> {
    @Modifying
    long deleteByFetchedAtBefore(Instant cutoff);
}
