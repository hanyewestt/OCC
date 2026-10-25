package com.csd.occ.disruption.hazard.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface HazardStateRepository extends JpaRepository<HazardState, UUID> {
    java.util.Optional<HazardState> findByDisruptionId(UUID disruptionId);
    @Query("""
        select h from HazardState h where h.status <> 'CLEARED' and h.kind=coalesce(:kind,h.kind)
        and (h.effectiveUntil is null or h.effectiveUntil > :now)
        and (h.facts is not null)
        and (h.classification='OBSERVATION_ONLY' or h.demoRunId=:runId)
        order by h.updatedAt desc,h.id
        """)
    Page<HazardState> findVisible(String kind, UUID runId, Instant now, Pageable page);
    @Query("""
        select h from HazardState h where h.source=:source and h.scope=:scope and h.classification=:classification
        and h.demoRunId is not distinct from :runId
        """)
    List<HazardState> findScope(String source, String scope, String classification, UUID runId);
    Page<HazardState> findAllByOrderByUpdatedAtDesc(Pageable page);
    List<HazardState> findByStatusIn(List<String> statuses);
    @Modifying @Query(value = """
        insert into disruption.disruption_event
        (id,disruption_id,kind,action,classification,demo_run_id,source_observation_id,poll_run_id,fetched_at,triggered_at,evidence)
        values (:id,:disruptionId,:kind,:action,:classification,:runId,:observationId,:pollId,:fetchedAt,:at,cast(:payload as jsonb))
        """, nativeQuery = true)
    int recordEvent(UUID id, UUID disruptionId, String kind, String action, String classification, UUID runId,
                    UUID observationId, UUID pollId, Instant fetchedAt, Instant at, String payload);
}
