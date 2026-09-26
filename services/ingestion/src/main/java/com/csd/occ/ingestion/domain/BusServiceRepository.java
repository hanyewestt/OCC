package com.csd.occ.ingestion.domain;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusServiceRepository extends JpaRepository<BusService, BusServiceId> {
    Page<BusService> findByIdReferenceVersionId(UUID referenceVersionId, Pageable pageable);
}
