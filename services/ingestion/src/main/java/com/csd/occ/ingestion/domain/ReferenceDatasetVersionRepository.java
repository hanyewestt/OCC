package com.csd.occ.ingestion.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReferenceDatasetVersionRepository extends JpaRepository<ReferenceDatasetVersion, UUID> {
    Optional<ReferenceDatasetVersion> findByDatasetAndStatus(String dataset, String status);
}
