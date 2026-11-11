package com.csd.occ.planning.domain.audit;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;

public interface ApplicationAuditMetadataRepository extends JpaRepository<ApplicationAuditMetadata, Short> {
    @Modifying
    @Query(value = "insert into planning.application_audit_metadata (id, collection_started_at) values (1, :startedAt) on conflict (id) do nothing", nativeQuery = true)
    int initialize(@Param("startedAt") Instant startedAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select metadata from ApplicationAuditMetadata metadata where metadata.id = 1")
    Optional<ApplicationAuditMetadata> lockSingleton();
}
