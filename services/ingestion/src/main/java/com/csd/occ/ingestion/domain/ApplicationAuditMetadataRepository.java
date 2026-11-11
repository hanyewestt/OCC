package com.csd.occ.ingestion.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApplicationAuditMetadataRepository extends JpaRepository<ApplicationAuditMetadata, Short> {
    @Modifying
    @Query(value = "insert into ingestion.application_audit_metadata (id, collection_started_at) values (1, :startedAt) on conflict (id) do nothing", nativeQuery = true)
    int initialize(@Param("startedAt") java.time.Instant startedAt);
}
