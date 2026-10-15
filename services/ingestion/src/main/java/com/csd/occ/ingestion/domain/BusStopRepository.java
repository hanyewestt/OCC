package com.csd.occ.ingestion.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusStopRepository extends JpaRepository<BusStop, BusStopId> {
    long deleteByIdReferenceVersionId(UUID referenceVersionId);
    Page<BusStop> findByIdReferenceVersionId(UUID referenceVersionId, Pageable pageable);
    Optional<BusStop> findByIdReferenceVersionIdAndIdStopCode(UUID referenceVersionId, String stopCode);
}
