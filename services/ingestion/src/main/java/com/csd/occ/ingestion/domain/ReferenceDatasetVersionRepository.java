package com.csd.occ.ingestion.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReferenceDatasetVersionRepository extends JpaRepository<ReferenceDatasetVersion, UUID> {
    Optional<ReferenceDatasetVersion> findByDatasetAndStatus(String dataset, String status);

    @Query(value = """
            select version.id from ingestion.reference_dataset_version version
            where version.status <> 'ACTIVE' and version.retired_at < :cutoff
              and not exists (select 1 from ingestion.bus_arrival_observation arrival where arrival.reference_version_id = version.id)
            """, nativeQuery = true)
    java.util.List<UUID> findExpiredUnreferencedIds(@org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff);
}
