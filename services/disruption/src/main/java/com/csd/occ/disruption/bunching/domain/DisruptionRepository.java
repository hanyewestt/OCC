package com.csd.occ.disruption.bunching.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DisruptionRepository extends JpaRepository<Disruption, UUID> {
    @Modifying
    @Query(value = """
            INSERT INTO disruption.disruption (id, kind, classification, demo_run_id, status, opened_at, updated_at, cleared_at)
            VALUES (:id, :kind, :classification, :demoRunId, :status, :openedAt, :updatedAt, :clearedAt)
            ON CONFLICT (id) DO UPDATE SET
                status = EXCLUDED.status,
                updated_at = GREATEST(disruption.disruption.updated_at, EXCLUDED.updated_at),
                cleared_at = EXCLUDED.cleared_at
            """, nativeQuery = true)
    int recordTransition(@Param("id") UUID id, @Param("kind") String kind,
            @Param("classification") String classification, @Param("demoRunId") UUID demoRunId,
            @Param("status") String status, @Param("openedAt") Instant openedAt,
            @Param("updatedAt") Instant updatedAt, @Param("clearedAt") Instant clearedAt);

    @Modifying
    @Query(value = """
            INSERT INTO disruption.disruption (id, kind, classification, demo_run_id, status, opened_at, updated_at, cleared_at)
            VALUES (:id, 'BUNCHING', :classification, :demoRunId, :status, :openedAt, :updatedAt, :clearedAt)
            ON CONFLICT (id) DO UPDATE SET
                status = EXCLUDED.status,
                updated_at = GREATEST(disruption.disruption.updated_at, EXCLUDED.updated_at),
                cleared_at = EXCLUDED.cleared_at
            """, nativeQuery = true)
    int recordBunchingTransition(@Param("id") UUID id, @Param("classification") String classification,
            @Param("demoRunId") UUID demoRunId, @Param("status") String status,
            @Param("openedAt") Instant openedAt, @Param("updatedAt") Instant updatedAt,
            @Param("clearedAt") Instant clearedAt);
}
