package com.csd.occ.disruption.bunching.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DisruptionRepository extends JpaRepository<Disruption, UUID> {
    String CURRENT_HAZARDS = """
        select h.disruption_id,h.updated_at from disruption.hazard_state h
        where h.status <> 'CLEARED' and h.kind=coalesce(:kind,h.kind)
          and (h.classification='OBSERVATION_ONLY' or h.demo_run_id=:runId)
          and (h.facts->>'effectiveUntil' is null or
            case when jsonb_typeof(h.facts->'effectiveUntil')='number'
              then to_timestamp((h.facts->>'effectiveUntil')::double precision)
              else cast(h.facts->>'effectiveUntil' as timestamptz) end > :now)
        union all
        select t.disruption_id,t.updated_at from disruption.traffic_incident_state t
        where t.status='ACTIVE' and coalesce(:kind,'TRAFFIC_INCIDENT')='TRAFFIC_INCIDENT'
          and (t.classification='OBSERVATION_ONLY' or t.demo_run_id=:runId)
        """;
    @Query(value = "select c.disruption_id from (" + CURRENT_HAZARDS + ") c order by c.updated_at desc,c.disruption_id",
            countQuery = "select count(*) from (" + CURRENT_HAZARDS + ") c", nativeQuery = true)
    org.springframework.data.domain.Page<UUID> findCurrentHazardIds(String kind, UUID runId, Instant now,
                                                                  org.springframework.data.domain.Pageable page);
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
